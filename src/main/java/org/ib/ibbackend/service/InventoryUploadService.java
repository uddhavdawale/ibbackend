package org.ib.ibbackend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVReader;
import org.apache.poi.ss.usermodel.*;
import org.ib.ibbackend.dto.InventoryUploadResult;
import org.ib.ibbackend.dto.InventoryUploadRow;
import org.ib.ibbackend.entity.InventoryMovement;
import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.exception.ValidationException;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.InventoryMovementRepository;
import org.ib.ibbackend.repository.ProductRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
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
    private final UserRepository userRepository;
    private final InventoryMovementRepository movementRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InventoryUploadService(ProductRepository productRepository) {
        this(productRepository, null, null);
    }

    public InventoryUploadService(ProductRepository productRepository,
                                  UserRepository userRepository) {
        this(productRepository, userRepository, null);
    }

    @Autowired
    public InventoryUploadService(ProductRepository productRepository,
                                  UserRepository userRepository,
                                  InventoryMovementRepository movementRepository) {
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.movementRepository = movementRepository;
    }

    @Transactional
    public InventoryUploadResult processUpload(MultipartFile file, String source, String recordsJson) {
        return processUpload(file, source, recordsJson, null, null);
    }

    @Transactional
    public InventoryUploadResult processUpload(MultipartFile file, String source, String recordsJson, Long requestedTenantId, Long requestedUserId) {
        User authenticatedUser = null;
        Long resolvedTenantId = null;
        if (userRepository != null) {
            authenticatedUser = resolveAuthenticatedUser(requestedUserId);
            resolvedTenantId = resolveTenantId(authenticatedUser, requestedTenantId);
        }

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
            validateRow(row, errors, resolvedTenantId);
        }

        if (!errors.isEmpty()) {
            throw new ValidationException("Inventory upload validation failed", errors);
        }

        int created = 0;
        int updated = 0;
        int skipped = 0;
        List<Map<String, Object>> savedRecords = new ArrayList<>();
        Set<String> seenProductKeys = new HashSet<>();

        for (InventoryUploadRow row : rows) {
            String sku = normalizeText(row.getSku());
            String poNumber = normalizeText(row.getPoNumber());
            String productKey = buildProductIdentityKey(sku, poNumber);
            if (!seenProductKeys.add(productKey)) {
                skipped++;
                continue;
            }

            Product existingProduct = findExistingProductForUpload(row, resolvedTenantId);
            boolean sameItemDifferentPo = isSameItemDifferentPo(existingProduct, row);

            int beforeQty = existingProduct != null && existingProduct.getQuantity() != null
                    ? existingProduct.getQuantity()
                    : 0;

            Product product = existingProduct != null ? existingProduct : new Product();
            if (sameItemDifferentPo) {
                product.setQuantity((product.getQuantity() == null ? 0 : product.getQuantity()) + row.getQty());
                product.setMinStock(row.getMinStock());
                product.setPrice(row.getPrice());
                product.setMrp(row.getMrp());
                product.setPoNumber(poNumber.isBlank() ? product.getPoNumber() : poNumber);
                product.setUploadedBy(authenticatedUser);
            } else {
                product.setName(row.getProduct().trim());
                product.setSku(sku);
                product.setHsnSac(normalizeText(row.getHsnSac()));
                product.setQuantity(row.getQty());
                product.setMinStock(row.getMinStock());
                product.setPrice(row.getPrice());
                product.setMrp(row.getMrp());
                product.setSetSize(row.getSet());
                product.setPoNumber(poNumber);
                product.setCategory(row.getCategory());
                product.setUploadedBy(authenticatedUser);
            }
            product.setName(row.getProduct().trim());
            product.setSku(sku);
            product.setHsnSac(normalizeText(row.getHsnSac()));
            product.setSetSize(row.getSet());
            product.setCategory(row.getCategory());
            if (resolvedTenantId != null) {
                product.setTenant(toTenantReference(resolvedTenantId));
            } else if (authenticatedUser != null && authenticatedUser.getTenant() != null) {
                product.setTenant(authenticatedUser.getTenant());
            }

            Product saved = productRepository.save(product);
            if (saved == null) {
                saved = product;
            }

            int afterQty = saved.getQuantity() == null ? 0 : saved.getQuantity();
            int deltaQty = afterQty - beforeQty;
            if (deltaQty != 0 && movementRepository != null) {
                InventoryMovement movement = new InventoryMovement();
                movement.setProduct(saved);
                movement.setTenant(saved.getTenant());
                movement.setUser(authenticatedUser);
                movement.setMovementType(InventoryMovement.MovementType.UPLOAD);
                movement.setDeltaQty(deltaQty);
                movement.setBeforeQty(beforeQty);
                movement.setAfterQty(afterQty);
                movement.setReferenceType("INVENTORY_UPLOAD");
                movement.setReferenceId(source == null ? "upload" : source);
                movement.setNote("Stock updated via inventory upload");
                movementRepository.save(movement);
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
            recordMap.put("mrp", saved.getMrp());
            recordMap.put("set", saved.getSetSize());
            recordMap.put("poNumber", saved.getPoNumber());
            recordMap.put("hsnSac", saved.getHsnSac());
            recordMap.put("pricePerQty", row.getPricePerQty() != null ? row.getPricePerQty() : saved.getPrice());
            recordMap.put("category", saved.getCategory());
            savedRecords.add(recordMap);
        }

        return new InventoryUploadResult(true, "Inventory uploaded successfully", created, updated, skipped, savedRecords);
    }

    private User resolveAuthenticatedUser(Long requestedUserId) {
        if (userRepository == null) {
            throw new ValidationException("Authentication is required to upload inventory");
        }

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication != null ? authentication.getName() : null;

        User currentUser = null;
        if (email != null && !email.isBlank()) {
            currentUser = userRepository.findByEmail(email).orElse(null);
        }
        if (currentUser == null && requestedUserId != null) {
            currentUser = userRepository.findById(requestedUserId).orElse(null);
        }
        if (currentUser == null) {
            throw new ValidationException("Authenticated user could not be resolved");
        }

        if (requestedUserId != null && !Objects.equals(currentUser.getId(), requestedUserId)) {
            throw new ValidationException("User mismatch: provided userId does not match the authenticated user");
        }

        if (currentUser.getTenant() == null) {
            throw new ValidationException("User is not assigned to a tenant");
        }

        return currentUser;
    }

    private Long resolveTenantId(User authenticatedUser, Long requestedTenantId) {
        Long contextTenantId = TenantContext.getCurrentTenant();
        Long userTenantId = authenticatedUser.getTenant() != null ? (long) authenticatedUser.getTenant().getId() : null;
        Long resolvedTenantId = contextTenantId != null ? contextTenantId : userTenantId;

        if (resolvedTenantId == null) {
            throw new ValidationException("Tenant could not be resolved for the authenticated user");
        }

        if (requestedTenantId != null && !Objects.equals(requestedTenantId, resolvedTenantId)) {
            throw new ValidationException("Tenant mismatch: provided tenantId does not match the authenticated tenant");
        }

        return resolvedTenantId;
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
                String product = getCellValue(nextLine, headerMap, "product");
                String sku = getCellValue(nextLine, headerMap, "sku");
                String hsnSac = getCellValue(nextLine, headerMap, "hsnsac");
                String poNumber = getCellValue(nextLine, headerMap, "ponumber");

                row.setProduct(product);
                row.setHsnSac(hsnSac);
                row.setPoNumber(poNumber);
                row.setSku(deriveSku(sku, product, hsnSac, poNumber));
                row.setQty(parseInteger(getCellValue(nextLine, headerMap, "qty"), "Qty"));
                row.setMinStock(parseInteger(getCellValue(nextLine, headerMap, "minstock"), "Min Stock"));
                row.setSet(defaultSet(getCellValue(nextLine, headerMap, "set")));

                BigDecimal mrp = parseBigDecimal(getCellValue(nextLine, headerMap, "mrpitem"), "MRP/ Item");
                BigDecimal pricePerQty = parseBigDecimal(getCellValue(nextLine, headerMap, "price"), "Price/QTY");
                row.setMrp(mrp);
                row.setPricePerQty(pricePerQty);
                row.setPrice(pricePerQty != null ? pricePerQty : mrp);

                String category = getCellValue(nextLine, headerMap, "category");
                row.setCategory(category);

                if (row.getMinStock() == null) {
                    row.setMinStock(0);
                }
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
                String product = getCellValue(row, headerMap, "product");
                String sku = getCellValue(row, headerMap, "sku");
                String hsnSac = getCellValue(row, headerMap, "hsnsac");
                String poNumber = getCellValue(row, headerMap, "ponumber");

                uploadRow.setProduct(product);
                uploadRow.setHsnSac(hsnSac);
                uploadRow.setPoNumber(poNumber);
                uploadRow.setSku(deriveSku(sku, product, hsnSac, poNumber));
                uploadRow.setQty(parseInteger(getCellValue(row, headerMap, "qty"), "Qty"));
                uploadRow.setMinStock(parseInteger(getCellValue(row, headerMap, "minstock"), "Min Stock"));
                uploadRow.setSet(defaultSet(getCellValue(row, headerMap, "set")));

                BigDecimal mrp = parseBigDecimal(getCellValue(row, headerMap, "mrpitem"), "MRP/ Item");
                BigDecimal pricePerQty = parseBigDecimal(getCellValue(row, headerMap, "price"), "Price/QTY");
                uploadRow.setMrp(mrp);
                uploadRow.setPricePerQty(pricePerQty);
                uploadRow.setPrice(pricePerQty != null ? pricePerQty : mrp);

                String category = getCellValue(row, headerMap, "category");
                uploadRow.setCategory(category);

                if (uploadRow.getMinStock() == null) {
                    uploadRow.setMinStock(0);
                }
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
                String product = stringValue(getMappedValue(raw, "product", "item", "name"));
                String sku = stringValue(getMappedValue(raw, "sku", "product sku"));
                String hsnSac = stringValue(getMappedValue(raw, "hsn/sac", "hsn sac", "hsn"));
                String poNumber = stringValue(getMappedValue(raw, "po number", "po", "ponumber"));

                InventoryUploadRow row = new InventoryUploadRow();
                row.setProduct(product);
                row.setHsnSac(hsnSac);
                row.setPoNumber(poNumber);
                row.setSku(deriveSku(sku, product, hsnSac, poNumber));
                row.setQty(integerValue(getMappedValue(raw, "qty", "quantity")));
                row.setMinStock(integerValue(getMappedValue(raw, "min stock", "minstock")));
                row.setSet(defaultSet(stringValue(getMappedValue(raw, "set"))));

                BigDecimal mrp = bigDecimalValue(getMappedValue(raw, "mrp/ item", "mrp item", "mrp"));
                BigDecimal pricePerQty = bigDecimalValue(getMappedValue(raw, "price/qty", "price per qty", "price", "unit price"));
                row.setMrp(mrp);
                row.setPricePerQty(pricePerQty);
                row.setPrice(pricePerQty != null ? pricePerQty : mrp);

                String category = stringValue(getMappedValue(raw, "category", "categories"));
                row.setCategory(category);

                if (row.getMinStock() == null) {
                    row.setMinStock(0);
                }
                rows.add(row);
            }
            return rows;
        } catch (Exception ex) {
            throw new ValidationException("Unable to parse records JSON: " + ex.getMessage());
        }
    }

    private void validateHeaders(Map<String, Integer> headerMap) {
        List<String> missing = new ArrayList<>();
        if (!headerMap.containsKey("product")) missing.add("Item");
        if (!headerMap.containsKey("hsnsac")) missing.add("HSN/SAC");
        if (!headerMap.containsKey("mrpitem")) missing.add("MRP/ Item");
        if (!headerMap.containsKey("qty")) missing.add("QTY");
        if (!headerMap.containsKey("minstock")) missing.add("Min Stock");
        if (!missing.isEmpty()) throw new ValidationException("Missing required columns: " + String.join(", ", missing));
    }

    private void validateRow(InventoryUploadRow row, List<String> errors, Long currentTenantId) {
        if (row == null) {
            errors.add("Encountered an empty row");
            return;
        }

        if (row.getProduct() == null || row.getProduct().isBlank()) {
            errors.add("Column Item is required");
        } else if (row.getProduct().trim().length() > 200) {
            errors.add("Product name is too long");
        }

        if (row.getHsnSac() == null || row.getHsnSac().isBlank()) {
            errors.add("Column HSN/SAC is required");
        } else if (normalizeText(row.getHsnSac()).length() > 100) {
            errors.add("HSN/SAC is too long");
        }

        if (row.getSku() == null || row.getSku().isBlank()) {
            errors.add("Column SKU is required");
        } else {
            String normalizedSku = normalizeText(row.getSku());
            if (normalizedSku.length() > 100) {
                errors.add("SKU is too long");
            }

        }

        if (row.getQty() == null || row.getQty() < 0) {
            errors.add("Qty must be a non-negative number");
        }

        if (row.getMinStock() == null || row.getMinStock() < 0) {
            errors.add("Min Stock must be a non-negative number");
        }

        if (row.getMrp() == null || row.getMrp().compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("MRP/ Item must be a positive number");
        }

        if (row.getPricePerQty() != null && row.getPricePerQty().compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("Price/QTY must be a positive number");
        }

        if (row.getPrice() == null || row.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("Price must be a positive number");
        }

        if (row.getSet() == null || row.getSet().length() > 100) {
            errors.add("Set must be at most 100 characters");
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
        if (normalized.equals("item")) return "product";
        if (normalized.equals("productsku")) return "sku";
        if (normalized.equals("hsnsac")) return "hsnsac";
        if (normalized.equals("quantity")) return "qty";
        if (normalized.equals("set")) return "set";
        if (normalized.equals("minstock")) return "minstock";
        if (normalized.equals("mrpitem")) return "mrpitem";
        if (normalized.equals("mrp")) return "mrpitem";
        if (normalized.equals("priceqty")) return "price";
        if (normalized.equals("unitprice")) return "price";
        if (normalized.equals("ponumber")) return "ponumber";
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
            BigDecimal decimal = new BigDecimal(rawValue.replaceAll(",", "").trim());
            return decimal.intValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
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

    private String defaultSet(String value) {
        String normalized = normalizeText(value);
        return normalized.isBlank() ? "1" : normalized;
    }

    private String deriveSku(String sku, String product, String hsnSac, String poNumber) {
        String normalizedSku = normalizeText(sku);
        if (!normalizedSku.isBlank()) {
            return normalizedSku;
        }

        String normalizedHsn = normalizeText(hsnSac);
        if (!normalizedHsn.isBlank()) {
            return normalizedHsn;
        }

        String normalizedProduct = normalizeText(product);
        if (!normalizedProduct.isBlank()) {
            return normalizedProduct.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "-").replaceAll("(^-|-$)", "");
        }

        String normalizedPoNumber = normalizeText(poNumber);
        if (!normalizedPoNumber.isBlank()) {
            return normalizedPoNumber;
        }

        return "";
    }

    private Product findExistingProductForUpload(InventoryUploadRow row, Long tenantId) {
        String sku = normalizeText(row.getSku());
        if (sku.isBlank()) {
            return null;
        }

        List<Product> candidates;
        if (tenantId != null) {
            candidates = productRepository.findAllBySkuAndTenantId(sku, tenantId);
        } else {
            candidates = productRepository.findAllBySku(sku);
        }

        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        for (Product candidate : candidates) {
            if (isSameProductDetails(candidate, row)) {
                return candidate;
            }
        }

        return null;
    }

    private boolean isSameItemDifferentPo(Product existingProduct, InventoryUploadRow row) {
        if (existingProduct == null || row == null) {
            return false;
        }
        if (existingProduct.getSku() == null || row.getSku() == null) {
            return false;
        }
        if (!normalizeText(existingProduct.getSku()).equalsIgnoreCase(normalizeText(row.getSku()))) {
            return false;
        }
        if (!isSameProductDetails(existingProduct, row)) {
            return false;
        }
        String existingPo = normalizeText(existingProduct.getPoNumber());
        String incomingPo = normalizeText(row.getPoNumber());
        return !existingPo.equals(incomingPo);
    }

    private boolean isSameProductDetails(Product existingProduct, InventoryUploadRow row) {
        if (existingProduct == null || row == null) {
            return false;
        }
        BigDecimal existingPrice = existingProduct.getPrice();
        BigDecimal incomingPrice = row.getPrice();
        BigDecimal existingMrp = existingProduct.getMrp();
        BigDecimal incomingMrp = row.getMrp();

        return Objects.equals(normalizeText(existingProduct.getName()), normalizeText(row.getProduct()))
                && Objects.equals(normalizeText(existingProduct.getSku()), normalizeText(row.getSku()))
                && Objects.equals(normalizeText(existingProduct.getHsnSac()), normalizeText(row.getHsnSac()))
                && (existingPrice == null ? incomingPrice == null : existingPrice.compareTo(incomingPrice == null ? BigDecimal.ZERO : incomingPrice) == 0)
                && (existingMrp == null ? incomingMrp == null : existingMrp.compareTo(incomingMrp == null ? BigDecimal.ZERO : incomingMrp) == 0)
                && Objects.equals(normalizeText(existingProduct.getSetSize()), normalizeText(row.getSet()))
                && Objects.equals(normalizeText(existingProduct.getCategory()), normalizeText(row.getCategory()));
    }

    private String buildProductIdentityKey(String sku, String poNumber) {
        return normalizeText(sku) + "::" + normalizeText(poNumber);
    }

    private Object getMappedValue(Map<String, Object> raw, String... keys) {
        if (raw == null || raw.isEmpty() || keys == null || keys.length == 0) {
            return null;
        }

        for (String key : keys) {
            if (raw.containsKey(key)) {
                return raw.get(key);
            }
        }

        Map<String, Object> normalized = new HashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            normalized.put(normalizeHeader(entry.getKey()), entry.getValue());
        }

        for (String key : keys) {
            Object value = normalized.get(normalizeHeader(key));
            if (value != null) {
                return value;
            }
        }

        return null;
    }

    private Tenant toTenantReference(Long tenantId) {
        Tenant tenant = new Tenant();
        tenant.setId(Math.toIntExact(tenantId));
        return tenant;
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
