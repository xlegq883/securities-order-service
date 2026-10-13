package com.fuzuyang.securities.common.metrics;

import com.fuzuyang.securities.common.error.DiffType;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 导出名断言：Micrometer 点号名（不带 {@code _total}）经 Prometheus 惯例渲染为规格名。
 */
class MetricsNamingTest {

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final AppMetrics metrics = new AppMetrics(registry);

    @Test
    void counterExportNames() {
        metrics.orderCreated();
        metrics.orderIdempotentReplay();
        metrics.orderNoCollision();
        metrics.reportApplied();
        metrics.reportRejected(DiffType.EXEC_OVERFILL);
        metrics.reportInvalid("missing_field");
        metrics.execDup();
        metrics.execOverfill();
        metrics.execAfterTerminal();
        metrics.illegalTransition();
        metrics.stateTransition();
        metrics.stateUnchanged();
        metrics.cancelRequest();
        metrics.cancelNotCancelable();
        metrics.execGap();
        metrics.execGapRepairExhausted();
        metrics.retryAttempt();
        metrics.retryExhausted();
        metrics.relayLeaseReclaim("event");
        metrics.relayLeaseReclaim("command");
        metrics.upstreamCommandSent();
        metrics.upstreamCommandFailed();
        metrics.eventOutboxSent();

        String scrape = registry.scrape();
        assertThat(scrape).contains("order_created_total");
        assertThat(scrape).contains("order_idempotent_replay_total");
        assertThat(scrape).contains("order_order_no_collision_total");
        assertThat(scrape).contains("report_applied_total");
        assertThat(scrape).contains("report_rejected_total");
        assertThat(scrape).contains("report_invalid_total");
        assertThat(scrape).contains("exec_dup_total");
        assertThat(scrape).contains("exec_overfill_total");
        assertThat(scrape).contains("exec_after_terminal_total");
        assertThat(scrape).contains("illegal_transition_total");
        assertThat(scrape).contains("state_transition_total");
        assertThat(scrape).contains("state_unchanged_total");
        assertThat(scrape).contains("cancel_request_total");
        assertThat(scrape).contains("cancel_not_cancelable_total");
        assertThat(scrape).contains("exec_gap_total");
        assertThat(scrape).contains("exec_gap_repair_exhausted_total");
        assertThat(scrape).contains("retry_attempt_total");
        assertThat(scrape).contains("retry_exhausted_total");
        assertThat(scrape).contains("relay_lease_reclaim_total");
        assertThat(scrape).contains("upstream_command_sent_total");
        assertThat(scrape).contains("upstream_command_failed_total");
        assertThat(scrape).contains("event_outbox_sent_total");
    }

    @Test
    void gaugeExportNames() {
        Gauge.builder(AppMetrics.GAUGE_UPSTREAM_COMMAND_PENDING, new AtomicLong(0), AtomicLong::get).register(registry);
        Gauge.builder(AppMetrics.GAUGE_EVENT_OUTBOX_PENDING, new AtomicLong(0), AtomicLong::get).register(registry);
        Gauge.builder(AppMetrics.GAUGE_EXEC_GAP_OPEN, new AtomicLong(0), AtomicLong::get).register(registry);

        String scrape = registry.scrape();
        assertThat(scrape).contains("upstream_command_pending");
        assertThat(scrape).contains("event_outbox_pending");
        assertThat(scrape).contains("exec_gap_open");
    }
}
