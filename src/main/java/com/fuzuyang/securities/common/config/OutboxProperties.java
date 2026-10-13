package com.fuzuyang.securities.common.config;

import jakarta.validation.constraints.AssertTrue;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 事件发件箱投递配置（{@code app.outbox.*}）。
 *
 * <p>硬约束（启动期校验）：{@code claim-lease-seconds > batch-size × send-timeout-ms}。
 * 认领租约必须大于"一轮批次的发送时间上界"，否则同一批会被重复认领。
 */
@Validated
@ConfigurationProperties(prefix = "app.outbox")
public class OutboxProperties {

    /** topic 分区数（topic 名走 EventTopics 常量）。 */
    private int partitions = 3;

    /** 定时投递是否启用（测试中关闭，手动 runOnce()）。 */
    private boolean relayEnabled = true;

    /** 投递轮询间隔（毫秒）。 */
    private long scanIntervalMs = 1000;

    /** 单轮认领上限。 */
    private int batchSize = 100;

    /** 单行失败重试上限。 */
    private int maxRetries = 3;

    /** 首次退避。 */
    private Duration baseBackoff = Duration.ofMillis(200);

    /** 退避上限。 */
    private Duration maxBackoff = Duration.ofSeconds(5);

    /** 出站通道：KAFKA | LOG。 */
    private String publisher = "KAFKA";

    /** 单条发送超时（毫秒）。 */
    private long sendTimeoutMs = 3000;

    /** 认领租约（秒），必须大于 batch-size × send-timeout-ms（毫秒换算）。 */
    private long claimLeaseSeconds = 600;

    @AssertTrue(message = "app.outbox.claim-lease-seconds 必须大于 batch-size × send-timeout-ms / 1000")
    public boolean isLeaseSufficient() {
        return claimLeaseSeconds * 1000L > (long) batchSize * sendTimeoutMs;
    }

    public int getPartitions() {
        return partitions;
    }

    public void setPartitions(int partitions) {
        this.partitions = partitions;
    }

    public boolean isRelayEnabled() {
        return relayEnabled;
    }

    public void setRelayEnabled(boolean relayEnabled) {
        this.relayEnabled = relayEnabled;
    }

    public long getScanIntervalMs() {
        return scanIntervalMs;
    }

    public void setScanIntervalMs(long scanIntervalMs) {
        this.scanIntervalMs = scanIntervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Duration getBaseBackoff() {
        return baseBackoff;
    }

    public void setBaseBackoff(Duration baseBackoff) {
        this.baseBackoff = baseBackoff;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff;
    }

    public String getPublisher() {
        return publisher;
    }

    public void setPublisher(String publisher) {
        this.publisher = publisher;
    }

    public long getSendTimeoutMs() {
        return sendTimeoutMs;
    }

    public void setSendTimeoutMs(long sendTimeoutMs) {
        this.sendTimeoutMs = sendTimeoutMs;
    }

    public long getClaimLeaseSeconds() {
        return claimLeaseSeconds;
    }

    public void setClaimLeaseSeconds(long claimLeaseSeconds) {
        this.claimLeaseSeconds = claimLeaseSeconds;
    }
}
