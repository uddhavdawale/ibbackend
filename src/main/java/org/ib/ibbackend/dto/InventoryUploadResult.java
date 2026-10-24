package org.ib.ibbackend.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class InventoryUploadResult {
    private final boolean success;
    private final String message;
    private final int createdCount;
    private final int updatedCount;
    private final int skippedCount;
    private final List<Map<String, Object>> records;

    public InventoryUploadResult(boolean success, String message, int createdCount, int updatedCount, int skippedCount, List<Map<String, Object>> records) {
        this.success = success;
        this.message = message;
        this.createdCount = createdCount;
        this.updatedCount = updatedCount;
        this.skippedCount = skippedCount;
        this.records = records == null ? new ArrayList<>() : records;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public int getCreatedCount() {
        return createdCount;
    }

    public int getUpdatedCount() {
        return updatedCount;
    }

    public int getSkippedCount() {
        return skippedCount;
    }

    public List<Map<String, Object>> getRecords() {
        return records;
    }
}
