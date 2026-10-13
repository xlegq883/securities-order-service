package com.fuzuyang.securities.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 指标配置（{@code app.metrics.*}）。
 */
@ConfigurationProperties(prefix = "app.metrics")
public class MetricsProperties {

    /** Gauge 周期采样间隔（毫秒）。抓取时只读内存，绝不查库。 */
    private long sampleIntervalMs = 5000;

    public long getSampleIntervalMs() {
        return sampleIntervalMs;
    }

    public void setSampleIntervalMs(long sampleIntervalMs) {
        this.sampleIntervalMs = sampleIntervalMs;
    }
}
