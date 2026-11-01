package org.ib.ibbackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.audit.retention")
public class AuditRetentionProperties {

    private boolean enabled = true;
    private int activeRetentionDays = 180;
    private int archiveRetentionDays = 730;
    private int batchSize = 1000;
    private int maxBatchesPerRun = 20;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getActiveRetentionDays() {
        return activeRetentionDays;
    }

    public void setActiveRetentionDays(int activeRetentionDays) {
        this.activeRetentionDays = activeRetentionDays;
    }

    public int getArchiveRetentionDays() {
        return archiveRetentionDays;
    }

    public void setArchiveRetentionDays(int archiveRetentionDays) {
        this.archiveRetentionDays = archiveRetentionDays;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxBatchesPerRun() {
        return maxBatchesPerRun;
    }

    public void setMaxBatchesPerRun(int maxBatchesPerRun) {
        this.maxBatchesPerRun = maxBatchesPerRun;
    }
}
