package com.fuzuyang.securities.common.config;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 上游通道配置（{@code app.upstream.*}）。
 *
 * <p>{@code commandTransport} 同时决定"装配哪个 sender"与"写入指令行的 transport 取值"，
 * 二者同源，避免"落库取值与实现不匹配"。
 *
 * <p>硬约束（启动期校验）：{@code claim-lease-seconds > batch-size × send-timeout-ms}。
 */
@Validated
@ConfigurationProperties(prefix = "app.upstream")
public class UpstreamProperties {

    /** 指令投递是否启用定时（测试中关闭，手动 runOnce()）。 */
    private boolean commandRelayEnabled = true;

    /** 指令出站通道：LOG | HTTP | GRPC。 */
    private String commandTransport = "LOG";

    /** 指令投递轮询间隔（毫秒）。 */
    private long scanIntervalMs = 1000;

    /** 单轮认领上限。 */
    private int batchSize = 100;

    /** 认领租约（秒）。 */
    private long claimLeaseSeconds = 600;

    /** 单条发送超时（毫秒）。 */
    private long sendTimeoutMs = 3000;

    private final ExecutionSource executionSource = new ExecutionSource();

    @AssertTrue(message = "app.upstream.claim-lease-seconds 必须大于 batch-size × send-timeout-ms / 1000")
    public boolean isLeaseSufficient() {
        return claimLeaseSeconds * 1000L > (long) batchSize * sendTimeoutMs;
    }

    public boolean isCommandRelayEnabled() {
        return commandRelayEnabled;
    }

    public void setCommandRelayEnabled(boolean commandRelayEnabled) {
        this.commandRelayEnabled = commandRelayEnabled;
    }

    public String getCommandTransport() {
        return commandTransport;
    }

    public void setCommandTransport(String commandTransport) {
        this.commandTransport = commandTransport;
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

    public long getClaimLeaseSeconds() {
        return claimLeaseSeconds;
    }

    public void setClaimLeaseSeconds(long claimLeaseSeconds) {
        this.claimLeaseSeconds = claimLeaseSeconds;
    }

    public long getSendTimeoutMs() {
        return sendTimeoutMs;
    }

    public void setSendTimeoutMs(long sendTimeoutMs) {
        this.sendTimeoutMs = sendTimeoutMs;
    }

    public ExecutionSource getExecutionSource() {
        return executionSource;
    }

    /** 缺口修复补拉端口开关：默认关闭（Noop）。 */
    public static class ExecutionSource {

        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
