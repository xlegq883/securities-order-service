package com.fuzuyang.securities.common.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Gauge 周期采样器。
 *
 * <p>Gauge 只读内存中的 {@link AtomicLong}；**抓取时绝不查库**。真实计数由本组件按
 * {@code app.metrics.sample-interval-ms} 周期执行三条 {@code COUNT(*)} 后写入内存。
 * 采样 SQL 走 {@code idx_status_next}（投递表）与 {@code idx_diff_type_status}（差异表）。
 * 采样异常保留旧值并 WARN，不终止调度。
 */
@Component
public class MetricsSampler {

    private static final Logger log = LoggerFactory.getLogger(MetricsSampler.class);

    private final JdbcTemplate jdbcTemplate;
    private final AtomicLong upstreamCommandPending = new AtomicLong();
    private final AtomicLong eventOutboxPending = new AtomicLong();
    private final AtomicLong execGapOpen = new AtomicLong();
    private final AtomicLong sampledRounds = new AtomicLong();

    public MetricsSampler(JdbcTemplate jdbcTemplate, MeterRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        Gauge.builder(AppMetrics.GAUGE_UPSTREAM_COMMAND_PENDING, upstreamCommandPending, AtomicLong::get)
                .register(registry);
        Gauge.builder(AppMetrics.GAUGE_EVENT_OUTBOX_PENDING, eventOutboxPending, AtomicLong::get)
                .register(registry);
        Gauge.builder(AppMetrics.GAUGE_EXEC_GAP_OPEN, execGapOpen, AtomicLong::get).register(registry);
    }

    @Scheduled(fixedDelayString = "${app.metrics.sample-interval-ms:5000}")
    public void sample() {
        try {
            upstreamCommandPending.set(count("t_upstream_command", "status IN ('PENDING','IN_PROGRESS')"));
            eventOutboxPending.set(count("t_event_outbox", "status IN ('PENDING','IN_PROGRESS')"));
            execGapOpen.set(count("t_reconcile_diff", "diff_type = 'EXEC_GAP' AND status = 'OPEN'"));
            sampledRounds.incrementAndGet();
        } catch (Exception e) {
            log.warn("指标采样失败，保留旧值", e);
        }
    }

    private long count(String table, String predicate) {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + predicate, Long.class);
        return value == null ? 0L : value;
    }

    /** 采样轮次（测试观测用）。 */
    public long sampledRounds() {
        return sampledRounds.get();
    }

    public long upstreamCommandPendingValue() {
        return upstreamCommandPending.get();
    }

    public long eventOutboxPendingValue() {
        return eventOutboxPending.get();
    }

    public long execGapOpenValue() {
        return execGapOpen.get();
    }
}
