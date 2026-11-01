package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.InventoryUploadResult;
import org.ib.ibbackend.exception.ValidationException;
import org.ib.ibbackend.service.AuditLogService;
import org.ib.ibbackend.service.InventoryUploadService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/inventory")
@CrossOrigin(originPatterns = "*")
public class InventoryController {

    private final InventoryUploadService inventoryUploadService;
    private final AuditLogService auditLogService;

    public InventoryController(InventoryUploadService inventoryUploadService,
                               AuditLogService auditLogService) {
        this.inventoryUploadService = inventoryUploadService;
        this.auditLogService = auditLogService;
    }

    private void auditSuccess(String action, String resourceId, String detail) {
        if (auditLogService != null) {
            auditLogService.success(action, resourceId, detail);
        }
    }

    private void auditFailure(String action, String resourceId, String detail) {
        if (auditLogService != null) {
            auditLogService.failure(action, resourceId, detail);
        }
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadInventory(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "tenantId", required = false) Long tenantId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "records", required = false) String recordsJson) {
        try {
            InventoryUploadResult result = inventoryUploadService.processUpload(file, source, recordsJson, tenantId, userId);
            String resourceId = source != null ? source : "upload";
                auditSuccess("INVENTORY_ADJUSTMENT", resourceId,
                "Inventory upload processed: created=" + result.getCreatedCount() +
                    ", updated=" + result.getUpdatedCount() +
                    ", skipped=" + result.getSkippedCount());
            return ResponseEntity.ok(Map.of(
                    "success", result.isSuccess(),
                    "message", result.getMessage(),
                    "created", result.getCreatedCount(),
                    "updated", result.getUpdatedCount(),
                    "skipped", result.getSkippedCount(),
                    "records", result.getRecords()
            ));
        } catch (ValidationException ex) {
            auditFailure("INVENTORY_ADJUSTMENT", "upload", "Inventory upload validation failed");
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", ex.getMessage(),
                    "errors", ex.getErrors()
            ));
        } catch (Exception ex) {
            auditFailure("INVENTORY_ADJUSTMENT", "upload", "Inventory upload failed");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "message", "Inventory upload failed",
                    "error", ex.getMessage()
            ));
        }
    }
}
