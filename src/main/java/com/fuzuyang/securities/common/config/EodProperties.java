package com.fuzuyang.securities.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 日终批处理配置（{@code app.eod.*}）。批次 3 使用。
 */
@ConfigurationProperties(prefix = "app.eod")
public class EodProperties {

    private long leaseSeconds = 60;
    private long heartbeatSeconds = 15;
    private int maxAttempts = 3;

    public long getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(long leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
    }

    public long getHeartbeatSeconds() {
        return heartbeatSeconds;
    }

    public void setHeartbeatSeconds(long heartbeatSeconds) {
        this.heartbeatSeconds = heartbeatSeconds;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }
}
