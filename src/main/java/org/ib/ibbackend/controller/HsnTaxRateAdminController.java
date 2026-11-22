package org.ib.ibbackend.controller;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import org.ib.ibbackend.dto.HsnTaxRateRequest;
import org.ib.ibbackend.entity.HsnTaxRate;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.HsnTaxRateRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.security.UserRolePolicy;
import org.ib.ibbackend.service.AuditLogService;
import org.ib.ibbackend.service.HsnTaxRateService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/hsn-tax-rates")
public class HsnTaxRateAdminController {
    private static final int MAX_PAGE_SIZE = 200;
    private static final long MAX_CSV_SIZE_BYTES = 5L * 1024 * 1024;
    private static final int MAX_CSV_ROWS = 5000;
    private static final Set<String> HSN_HEADERS = Set.of(
            "hsn", "hsncode", "hsnsac", "hsnsaccode", "sac", "saccode", "code");
    private static final Set<String> RATE_HEADERS = Set.of("gstrate", "gst", "rate", "taxrate");

    private final HsnTaxRateRepository rateRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public HsnTaxRateAdminController(
            HsnTaxRateRepository rateRepository,
            UserRepository userRepository,
            AuditLogService auditLogService) {
        this.rateRepository = rateRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public Page<HsnTaxRate> listRates(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        requireSystemAdmin();
        int safeSize = size <= 0 ? 100 : Math.min(size, MAX_PAGE_SIZE);
        return rateRepository.findAll(PageRequest.of(
                Math.max(page, 0), safeSize, Sort.by(Sort.Direction.ASC, "hsnCode")));
    }

    @PutMapping("/{hsnCode}")
    public HsnTaxRate saveRate(
            @PathVariable String hsnCode,
            @RequestBody HsnTaxRateRequest request) {
        requireSystemAdmin();
        String normalizedCode = HsnTaxRateService.normalizeCode(hsnCode);
        BigDecimal rate = validateRate(normalizedCode, request);

        HsnTaxRate saved = rateRepository.findByHsnCode(normalizedCode).orElseGet(HsnTaxRate::new);
        saved.setHsnCode(normalizedCode);
        saved.setGstRate(rate);
        HsnTaxRate result = rateRepository.save(saved);
        auditLogService.success("HSN_TAX_RATE_UPSERT", normalizedCode, "HSN/SAC tax rate updated");
        return result;
    }

    @PutMapping
    @Transactional
    public List<HsnTaxRate> saveRates(@RequestBody List<HsnTaxRateRequest> requests) {
        requireSystemAdmin();
        if (requests == null || requests.isEmpty() || requests.size() > 5000) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Provide between 1 and 5000 HSN/SAC rates per batch");
        }

        Set<String> seenCodes = new HashSet<>();
        List<HsnTaxRate> rates = new ArrayList<>(requests.size());
        for (HsnTaxRateRequest request : requests) {
            String code = request == null ? null : HsnTaxRateService.normalizeCode(request.getHsnCode());
            BigDecimal rate = validateRate(code, request);
            if (!seenCodes.add(code)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate HSN/SAC code in rate batch: " + code);
            }
            HsnTaxRate taxRate = new HsnTaxRate();
            taxRate.setHsnCode(code);
            taxRate.setGstRate(rate);
            rates.add(taxRate);
        }

        List<HsnTaxRate> saved = rateRepository.saveAll(rates);
        auditLogService.success("HSN_TAX_RATE_BULK_UPSERT", "batch", "HSN/SAC tax rates updated: " + saved.size());
        return saved;
    }

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public Map<String, Object> uploadRates(@RequestParam("file") MultipartFile file) {
        requireSystemAdmin();
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload a non-empty CSV file");
        }
        if (file.getSize() > MAX_CSV_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV file must not exceed 5 MB");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only CSV files are supported");
        }

        List<HsnTaxRateRequest> requests = parseRatesCsv(file);
        List<HsnTaxRate> saved = saveRates(requests);
        return Map.of("success", true, "importedCount", saved.size());
    }

    @DeleteMapping("/{hsnCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRate(@PathVariable String hsnCode) {
        requireSystemAdmin();
        String normalizedCode = HsnTaxRateService.normalizeCode(hsnCode);
        HsnTaxRate rate = rateRepository.findByHsnCode(normalizedCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "HSN/SAC tax rate not found"));
        rateRepository.delete(rate);
        auditLogService.success("HSN_TAX_RATE_DELETE", normalizedCode, "HSN/SAC tax rate deleted");
    }

    private User requireSystemAdmin() {
        String principal = SecurityContextHolder.getContext().getAuthentication() == null
                ? null
                : SecurityContextHolder.getContext().getAuthentication().getName();
        if (principal == null || principal.isBlank()) {
            throw new AccessDeniedException("Access denied");
        }
        User user = userRepository.findByEmail(principal)
                .or(() -> userRepository.findByUsername(principal))
                .orElseThrow(() -> new AccessDeniedException("Access denied"));
        if (!UserRolePolicy.isSystemAdmin(user.getRole())) {
            throw new AccessDeniedException("Only system admins can manage HSN/SAC tax rates");
        }
        return user;
    }

    private BigDecimal validateRate(String hsnCode, HsnTaxRateRequest request) {
        if (!HsnTaxRateService.isValidCode(hsnCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "HSN/SAC must contain 4, 6, or 8 digits");
        }
        BigDecimal rate = request == null ? null : request.getGstRate();
        if (rate == null || rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(new BigDecimal("100")) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "gstRate must be between 0 and 100 for HSN/SAC " + hsnCode);
        }
        try {
            return rate.setScale(2, java.math.RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "gstRate supports at most 2 decimal places for HSN/SAC " + hsnCode);
        }
    }

    private List<HsnTaxRateRequest> parseRatesCsv(MultipartFile file) {
        try (CSVReader reader = new CSVReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String[] headers = reader.readNext();
            if (headers == null || headers.length == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV file has no header row");
            }

            List<Integer> codeColumns = findColumns(headers, HSN_HEADERS);
            int rateColumn = findSingleColumn(headers, RATE_HEADERS, "GST rate");
            if (codeColumns.size() != 1) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "CSV must include exactly one HSN/SAC code column");
            }

            List<HsnTaxRateRequest> requests = new ArrayList<>();
            String[] row;
            int lineNumber = 1;
            while ((row = reader.readNext()) != null) {
                lineNumber++;
                if (isBlankRow(row)) {
                    continue;
                }
                if (requests.size() >= MAX_CSV_ROWS) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "CSV cannot contain more than " + MAX_CSV_ROWS + " data rows");
                }

                String code = cellValue(row, codeColumns.get(0));
                String rateValue = cellValue(row, rateColumn);
                if (code == null || rateValue.isBlank()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "CSV row " + lineNumber + " requires an HSN/SAC code and GST rate");
                }

                HsnTaxRateRequest request = new HsnTaxRateRequest();
                request.setHsnCode(code);
                try {
                    request.setGstRate(new BigDecimal(rateValue));
                } catch (NumberFormatException exception) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "CSV row " + lineNumber + " has an invalid GST rate");
                }
                try {
                    validateRate(HsnTaxRateService.normalizeCode(code), request);
                } catch (ResponseStatusException exception) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "CSV row " + lineNumber + ": " + exception.getReason());
                }
                requests.add(request);
            }

            if (requests.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV contains no rate rows");
            }
            return requests;
        } catch (CsvValidationException | IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the HSN/SAC rates CSV");
        }
    }

    private List<Integer> findColumns(String[] headers, Set<String> recognizedHeaders) {
        List<Integer> matches = new ArrayList<>();
        for (int index = 0; index < headers.length; index++) {
            if (recognizedHeaders.contains(normalizeHeader(headers[index]))) {
                matches.add(index);
            }
        }
        return matches;
    }

    private int findSingleColumn(String[] headers, Set<String> recognizedHeaders, String label) {
        List<Integer> matches = findColumns(headers, recognizedHeaders);
        if (matches.size() != 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "CSV must include exactly one " + label + " column");
        }
        return matches.get(0);
    }

    private String normalizeHeader(String header) {
        return header == null
                ? ""
                : header.replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String cellValue(String[] row, int column) {
        return column >= row.length || row[column] == null ? "" : row[column].trim();
    }

    private boolean isBlankRow(String[] row) {
        for (String value : row) {
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }
}
