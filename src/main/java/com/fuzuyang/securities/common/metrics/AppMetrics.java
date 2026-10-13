package com.fuzuyang.securities.common.metrics;

import com.fuzuyang.securities.common.error.DiffType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 集中式指标门面。
 *
 * <p>Micrometer 点号名**不带 {@code _total}**，由 Prometheus 命名惯例渲染为规格名
 * （如 {@code order.created} → {@code order_created_total}）；导出名由断言测试钉住。
 *
 * <p>标签只允许低基数：{@code op}/{@code reason}/{@code report_type}/{@code diff_type}/{@code channel}。
 */
@Component
public class AppMetrics {

    /** Gauge 名（单一来源，采样器与导出名断言共用）。 */
    public static final String GAUGE_UPSTREAM_COMMAND_PENDING = "upstream.command.pending";
    public static final String GAUGE_EVENT_OUTBOX_PENDING = "event.outbox.pending";
    public static final String GAUGE_EXEC_GAP_OPEN = "exec.gap.open";

    private final MeterRegistry registry;

    public AppMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    private void count(String name) {
        registry.counter(name).increment();
    }

    private void count(String name, String tagKey, String tagValue) {
        registry.counter(name, tagKey, tagValue).increment();
    }

    public void orderCreated() {
        count("order.created");
    }

    public void orderIdempotentReplay() {
        count("order.idempotent.replay");
    }

    /** 订单号碰撞后重新生成（唯一性由 uk_order_no 保证，本计数度量生成器质量）。 */
    public void orderNoCollision() {
        count("order.order_no.collision");
    }

    public void reportApplied() {
        count("report.applied");
    }

    public void reportRejected(DiffType diffType) {
        count("report.rejected", "diff_type", diffType.name());
    }

    public void reportInvalid(String reason) {
        count("report.invalid", "reason", reason);
    }

    public void execDup() {
        count("exec.dup");
    }

    public void execOverfill() {
        count("exec.overfill");
    }

    public void execAfterTerminal() {
        count("exec.after.terminal");
    }

    public void illegalTransition() {
        count("illegal.transition");
    }

    public void stateTransition() {
        count("state.transition");
    }

    public void stateUnchanged() {
        count("state.unchanged");
    }

    public void cancelRequest() {
        count("cancel.request");
    }

    public void cancelNotCancelable() {
        count("cancel.not.cancelable");
    }

    public void execGap() {
        count("exec.gap");
    }

    public void execGapRepairExhausted() {
        count("exec.gap.repair.exhausted");
    }

    public void retryAttempt() {
        count("retry.attempt");
    }

    public void retryExhausted() {
        count("retry.exhausted");
    }

    public void relayLeaseReclaim(String channel) {
        count("relay.lease.reclaim", "channel", channel);
    }

    public void upstreamCommandSent() {
        count("upstream.command.sent");
    }

    public void upstreamCommandFailed() {
        count("upstream.command.failed");
    }

    public void eventOutboxSent() {
        count("event.outbox.sent");
    }

    /** 供导出名断言测试使用。 */
    public Counter counter(String name, String... tags) {
        return registry.counter(name, tags);
    }
}
