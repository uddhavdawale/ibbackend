package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.InventoryUploadResult;
import org.ib.ibbackend.exception.ValidationException;
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

    public InventoryController(InventoryUploadService inventoryUploadService) {
        this.inventoryUploadService = inventoryUploadService;
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
            return ResponseEntity.ok(Map.of(
                    "success", result.isSuccess(),
                    "message", result.getMessage(),
                    "created", result.getCreatedCount(),
                    "updated", result.getUpdatedCount(),
                    "skipped", result.getSkippedCount(),
                    "records", result.getRecords()
            ));
        } catch (ValidationException ex) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", ex.getMessage(),
                    "errors", ex.getErrors()
            ));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "message", "Inventory upload failed",
                    "error", ex.getMessage()
            ));
        }
    }
}
