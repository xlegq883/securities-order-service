package com.fuzuyang.securities.execution.application.gap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 缺口检测与修复配置（{@code app.gap.*}）。
 *
 * <p>同时作为 {@link GapDetector} 纯函数的入参类型（可直接构造，无需 Spring）。
 */
@ConfigurationProperties(prefix = "app.gap")
public class GapProps {

    private boolean scanEnabled = true;
    private long scanIntervalMs = 10000;
    private long scanWindowSeconds = 300;
    private int scanBatchSize = 500;
    private int graceCount = 3;
    private long graceSeconds = 30;
    private int repairMaxAttempts = 3;
    private int repairBatchSize = 100;

    public boolean isScanEnabled() {
        return scanEnabled;
    }

    public void setScanEnabled(boolean scanEnabled) {
        this.scanEnabled = scanEnabled;
    }

    public long getScanIntervalMs() {
        return scanIntervalMs;
    }

    public void setScanIntervalMs(long scanIntervalMs) {
        this.scanIntervalMs = scanIntervalMs;
    }

    public long getScanWindowSeconds() {
        return scanWindowSeconds;
    }

    public void setScanWindowSeconds(long scanWindowSeconds) {
        this.scanWindowSeconds = scanWindowSeconds;
    }

    public int getScanBatchSize() {
        return scanBatchSize;
    }

    public void setScanBatchSize(int scanBatchSize) {
        this.scanBatchSize = scanBatchSize;
    }

    public int getGraceCount() {
        return graceCount;
    }

    public void setGraceCount(int graceCount) {
        this.graceCount = graceCount;
    }

    public long getGraceSeconds() {
        return graceSeconds;
    }

    public void setGraceSeconds(long graceSeconds) {
        this.graceSeconds = graceSeconds;
    }

    public int getRepairMaxAttempts() {
        return repairMaxAttempts;
    }

    public void setRepairMaxAttempts(int repairMaxAttempts) {
        this.repairMaxAttempts = repairMaxAttempts;
    }

    public int getRepairBatchSize() {
        return repairBatchSize;
    }

    public void setRepairBatchSize(int repairBatchSize) {
        this.repairBatchSize = repairBatchSize;
    }
}
