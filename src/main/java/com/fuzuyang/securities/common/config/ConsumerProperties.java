package com.fuzuyang.securities.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 消费端配置（{@code app.consumer.*}）。批次 4 使用。
 */
@ConfigurationProperties(prefix = "app.consumer")
public class ConsumerProperties {

    private String ackMode = "MANUAL_IMMEDIATE";
    private int maxAttempts = 3;

    public String getAckMode() {
        return ackMode;
    }

    public void setAckMode(String ackMode) {
        this.ackMode = ackMode;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }
}
