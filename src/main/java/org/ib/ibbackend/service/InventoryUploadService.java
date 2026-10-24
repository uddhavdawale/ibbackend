package org.ib.ibbackend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVReader;
import org.apache.poi.ss.usermodel.*;
import org.ib.ibbackend.dto.InventoryUploadResult;
import org.ib.ibbackend.dto.InventoryUploadRow;
import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.exception.ValidationException;
import org.ib.ibbackend.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class InventoryUploadService {
    public static final long MAX_UPLOAD_SIZE_BYTES = 5L * 1024 * 1024;
    public static final int MAX_RECORDS_PER_UPLOAD_DEFAULT = 5000;
    public static final int MAX_RECORDS_PER_UPLOAD = MAX_RECORDS_PER_UPLOAD_DEFAULT;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("csv", "xls", "xlsx");
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "text/csv",
            "application/csv",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );

    @Value("${app.upload.max-size-bytes:5242880}")
    private long maxUploadSizeBytes = MAX_UPLOAD_SIZE_BYTES;

    @Value("${app.upload.max-records:5000}")
    private int maxRecordsPerUpload = MAX_RECORDS_PER_UPLOAD_DEFAULT;

    private final ProductRepository productRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InventoryUploadService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public InventoryUploadResult processUpload(MultipartFile file, String source, String recordsJson) {
        List<InventoryUploadRow> rows;

        if (file != null && !file.isEmpty()) {
            validateUploadFile(file);
            rows = parseFile(file);
        } else if (recordsJson != null && !recordsJson.isBlank()) {
            validateJsonPayload(recordsJson);
            rows = parseRecords(recordsJson);
        } else {
            throw new ValidationException("No file uploaded");
        }

        if (rows == null || rows.isEmpty()) {
            throw new ValidationException("No valid rows found in the uploaded file");
        }
        if (rows.size() > maxRecordsPerUpload) {
            throw new ValidationException("Too many rows in upload. Maximum supported rows is " + maxRecordsPerUpload);
        }

        List<String> errors = new ArrayList<>();
        for (InventoryUploadRow row : rows) {
            validateRow(row, errors);
        }

        if (!errors.isEmpty()) {
            throw new ValidationException("Inventory upload validation failed", errors);
        }

        int created = 0;
        int updated = 0;
        int skipped = 0;
        List<Map<String, Object>> savedRecords = new ArrayList<>();
        Set<String> seenSkus = new HashSet<>();

        for (InventoryUploadRow row : rows) {
            String sku = normalizeText(row.getSku());
            if (!seenSkus.add(sku)) {
                skipped++;
                continue;
            }

            Product existingProduct = productRepository.findBySku(sku).orElse(null);
            Product product = existingProduct != null ? existingProduct : new Product();
            product.setName(row.getProduct().trim());
            product.setSku(sku);
            product.setQuantity(row.getQty());
            product.setMinStock(row.getMinStock());
            product.setPrice(row.getPrice());
            product.setCategory(row.getCategory());

            Product saved = productRepository.save(product);
            if (saved == null) {
                saved = product;
            }
            if (existingProduct == null) {
                created++;
            } else {
                updated++;
            }

            Map<String, Object> recordMap = new LinkedHashMap<>();
            recordMap.put("id", saved.getId());
            recordMap.put("product", saved.getName());
            recordMap.put("sku", saved.getSku());
            recordMap.put("qty", saved.getQuantity());
            recordMap.put("minStock", saved.getMinStock());
            recordMap.put("price", saved.getPrice());
            recordMap.put("category", saved.getCategory());
            savedRecords.add(recordMap);
        }

        return new InventoryUploadResult(true, "Inventory uploaded successfully", created, updated, skipped, savedRecords);
    }

    private void validateUploadFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("No file uploaded");
        }
        long effectiveLimit = Math.min(maxUploadSizeBytes, MAX_UPLOAD_SIZE_BYTES);
        if (file.getSize() > effectiveLimit) {
            throw new ValidationException("Upload file is too large. Maximum supported size is " + (effectiveLimit / 1024 / 1024) + "MB");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new ValidationException("Uploaded file is missing a valid name");
        }

        String sanitizedName = originalName.replace("\\", "/");
        if (sanitizedName.contains("../") || sanitizedName.contains("..\\") || sanitizedName.contains("/")) {
            throw new ValidationException("Invalid file name");
        }

        String extension = originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ValidationException("Only CSV, XLS, and XLSX files are supported");
        }

        String contentType = file.getContentType();
        if (contentType != null && !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new ValidationException("Unsupported file content type");
        }
    }

    private void validateJsonPayload(String recordsJson) {
        if (recordsJson == null || recordsJson.isBlank()) {
            throw new ValidationException("No records provided");
        }
        if (recordsJson.length() > 2 * 1024 * 1024) {
            throw new ValidationException("Upload payload is too large");
        }
    }

    private List<InventoryUploadRow> parseFile(MultipartFile file) {
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new ValidationException("Uploaded file is missing a valid name");
        }

        String extension = originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);

        try {
            if ("csv".equals(extension)) {
                return parseCsv(file.getInputStream());
            }
            if ("xls".equals(extension) || "xlsx".equals(extension)) {
                return parseExcel(file.getInputStream(), extension);
            }
            throw new ValidationException("Only CSV, XLS, and XLSX files are supported");
        } catch (ValidationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ValidationException("Failed to parse uploaded file: " + ex.getMessage(), List.of(ex.getMessage()));
        }
    }

    private List<InventoryUploadRow> parseCsv(java.io.InputStream inputStream) throws Exception {
        List<InventoryUploadRow> rows = new ArrayList<>();
        try (CSVReader reader = new CSVReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String[] headers = reader.readNext();
            if (headers == null || headers.length == 0) {
                throw new ValidationException("Uploaded CSV file is empty");
            }

            Map<String, Integer> headerMap = mapHeaders(headers);
            validateHeaders(headerMap);

            String[] nextLine;
            int rowIndex = 0;
            while ((nextLine = reader.readNext()) != null) {
                rowIndex++;
                if (rowIndex > maxRecordsPerUpload) {
                    throw new ValidationException("Too many rows in upload. Maximum supported rows is " + maxRecordsPerUpload);
                }
                if (isBlankRow(nextLine)) {
                    continue;
                }

                InventoryUploadRow row = new InventoryUploadRow();
                row.setProduct(getCellValue(nextLine, headerMap, "product"));
                row.setSku(getCellValue(nextLine, headerMap, "sku"));
                row.setQty(parseInteger(getCellValue(nextLine, headerMap, "qty"), "Qty"));
                row.setMinStock(parseInteger(getCellValue(nextLine, headerMap, "minstock"), "Min Stock"));
                row.setPrice(parseBigDecimal(getCellValue(nextLine, headerMap, "price"), "Price"));
                row.setCategory(getCellValue(nextLine, headerMap, "category"));
                rows.add(row);
            }
        }
        return rows;
    }

    private List<InventoryUploadRow> parseExcel(java.io.InputStream inputStream, String extension) throws Exception {
        List<InventoryUploadRow> rows = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet.getPhysicalNumberOfRows() == 0) {
                throw new ValidationException("Uploaded Excel file is empty");
            }

            Row headerRow = sheet.getRow(0);
            Map<String, Integer> headerMap = mapHeaders(headerRow);
            validateHeaders(headerMap);

            int rowIndex = 0;
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                rowIndex++;
                if (rowIndex > maxRecordsPerUpload) {
                    throw new ValidationException("Too many rows in upload. Maximum supported rows is " + maxRecordsPerUpload);
                }
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }

                if (isBlankRow(row)) {
                    continue;
                }

                InventoryUploadRow uploadRow = new InventoryUploadRow();
                uploadRow.setProduct(getCellValue(row, headerMap, "product"));
                uploadRow.setSku(getCellValue(row, headerMap, "sku"));
                uploadRow.setQty(parseInteger(getCellValue(row, headerMap, "qty"), "Qty"));
                uploadRow.setMinStock(parseInteger(getCellValue(row, headerMap, "minstock"), "Min Stock"));
                uploadRow.setPrice(parseBigDecimal(getCellValue(row, headerMap, "price"), "Price"));
                uploadRow.setCategory(getCellValue(row, headerMap, "category"));
                rows.add(uploadRow);
            }
        }
        return rows;
    }

    private List<InventoryUploadRow> parseRecords(String recordsJson) {
        try {
            List<Map<String, Object>> rawRows = objectMapper.readValue(recordsJson, new TypeReference<>() {});
            List<InventoryUploadRow> rows = new ArrayList<>();
            for (Map<String, Object> raw : rawRows) {
                InventoryUploadRow row = new InventoryUploadRow();
                row.setProduct(stringValue(raw.get("product")));
                row.setSku(stringValue(raw.get("sku")));
                row.setQty(integerValue(raw.get("qty")));
                row.setMinStock(integerValue(raw.get("minStock")));
                row.setPrice(bigDecimalValue(raw.get("price")));
                row.setCategory(stringValue(raw.get("category")));
                rows.add(row);
            }
            return rows;
        } catch (Exception ex) {
            throw new ValidationException("Unable to parse records JSON: " + ex.getMessage());
        }
    }

    private void validateHeaders(Map<String, Integer> headerMap) {
        List<String> missing = new ArrayList<>();
        if (!headerMap.containsKey("product")) missing.add("Product");
        if (!headerMap.containsKey("sku")) missing.add("SKU");
        if (!headerMap.containsKey("qty")) missing.add("Qty");
        if (!headerMap.containsKey("minstock")) missing.add("Min Stock");
        if (!headerMap.containsKey("price")) missing.add("Price");
        if (!missing.isEmpty()) throw new ValidationException("Missing required columns: " + String.join(", ", missing));
    }

    private void validateRow(InventoryUploadRow row, List<String> errors) {
        if (row == null) {
            errors.add("Encountered an empty row");
            return;
        }

        if (row.getProduct() == null || row.getProduct().isBlank()) {
            errors.add("Column Product is required");
        } else if (row.getProduct().trim().length() > 200) {
            errors.add("Product name is too long");
        }

        if (row.getSku() == null || row.getSku().isBlank()) {
            errors.add("Column SKU is required");
        } else {
            String normalizedSku = normalizeText(row.getSku());
            if (normalizedSku.length() > 100) {
                errors.add("SKU is too long");
            }
            if (productRepository.existsBySku(normalizedSku)) {
                errors.add("SKU " + row.getSku() + " already exists");
            }
        }

        if (row.getQty() == null || row.getQty() < 0) {
            errors.add("Qty must be a non-negative number");
        }

        if (row.getMinStock() == null || row.getMinStock() < 0) {
            errors.add("Min Stock must be a non-negative number");
        }

        if (row.getPrice() == null || row.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("Price must be a positive number");
        }

        if (row.getCategory() == null || row.getCategory().isBlank()) {
            row.setCategory("General");
        } else if (row.getCategory().length() > 100) {
            errors.add("Category name is too long");
        }
    }

    private Map<String, Integer> mapHeaders(String[] headers) {
        Map<String, Integer> mapped = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            String normalized = normalizeHeader(headers[i]);
            if (!normalized.isBlank()) {
                mapped.put(normalized, i);
            }
        }
        return mapped;
    }

    private Map<String, Integer> mapHeaders(Row headerRow) {
        Map<String, Integer> mapped = new HashMap<>();
        for (Cell cell : headerRow) {
            String normalized = normalizeHeader(getCellText(cell));
            if (!normalized.isBlank()) {
                mapped.put(normalized, cell.getColumnIndex());
            }
        }
        return mapped;
    }

    private String normalizeHeader(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
        if (normalized.equals("name")) return "product";
        if (normalized.equals("productsku")) return "sku";
        if (normalized.equals("quantity")) return "qty";
        if (normalized.equals("minstock")) return "minstock";
        if (normalized.equals("unitprice")) return "price";
        if (normalized.equals("categories")) return "category";
        return normalized;
    }

    private String getCellValue(String[] row, Map<String, Integer> headerMap, String key) {
        Integer index = headerMap.get(key);
        if (index == null || index >= row.length) {
            return null;
        }
        return row[index] == null ? null : row[index].trim();
    }

    private String getCellValue(Row row, Map<String, Integer> headerMap, String key) {
        Integer index = headerMap.get(key);
        if (index == null) {
            return null;
        }
        Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return getCellText(cell);
    }

    private String getCellText(Cell cell) {
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> String.valueOf(cell.getStringCellValue());
            default -> "";
        };
    }

    private boolean isBlankRow(String[] row) {
        if (row == null) return true;
        for (String value : row) {
            if (value != null && !value.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private boolean isBlankRow(Row row) {
        if (row == null) return true;
        for (Cell cell : row) {
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                return false;
            }
        }
        return true;
    }

    private Integer parseInteger(String rawValue, String fieldName) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(rawValue.replaceAll(",", "").trim());
        } catch (NumberFormatException ex) {
            throw new ValidationException(fieldName + " must be a valid integer");
        }
    }

    private BigDecimal parseBigDecimal(String rawValue, String fieldName) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(rawValue.replaceAll(",", "").trim());
        } catch (NumberFormatException ex) {
            throw new ValidationException(fieldName + " must be a valid decimal number");
        }
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String stringValue(Object value) {
        if (value == null) return null;
        return value.toString();
    }

    private Integer integerValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private BigDecimal bigDecimalValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return new BigDecimal(value.toString());
    }
}
