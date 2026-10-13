package com.fuzuyang.securities.order.application;

import com.fuzuyang.securities.common.config.OutboxProperties;
import com.fuzuyang.securities.common.metrics.AppMetrics;
import com.fuzuyang.securities.order.application.port.EventPublisher;
import com.fuzuyang.securities.order.infrastructure.entity.EventOutboxDO;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 事件投递 relay。
 *
 * <p>语义 = **at-least-once**：租约过期重认领、或"已发出但未标 SENT 时崩溃"都会重复发送，
 * 这是接受的行为；事件重复由读侧幂等消化（{@code (aggregate_id, event_version)} 严格递增）。
 *
 * <p>bean 常驻、{@code runOnce()} 不标 {@code @Transactional}；调度条件化在 *Scheduler 上。
 */
@Service
public class EventOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(EventOutboxRelay.class);

    private final EventOutboxStore store;
    private final EventPublisher publisher;
    private final OutboxProperties props;
    private final AppMetrics metrics;

    public EventOutboxRelay(EventOutboxStore store, EventPublisher publisher, OutboxProperties props,
            AppMetrics metrics) {
        this.store = store;
        this.publisher = publisher;
        this.props = props;
        this.metrics = metrics;
    }

    public void runOnce() {
        List<EventOutboxDO> batch = store.claim(props.getBatchSize(), props.getClaimLeaseSeconds());
        for (EventOutboxDO row : batch) {
            try {
                publisher.publish(row);
                if (store.markSent(row.getId(), row.getOwner()) > 0) {
                    metrics.eventOutboxSent();
                }
            } catch (Exception ex) {
                long backoffMicros = backoff(attempts(row)).toMillis() * 1000L;
                store.markFailed(row.getId(), row.getOwner(), status(row), attempts(row), backoffMicros);
                log.warn("事件投递失败 defer id={} attempt={}", row.getId(), attempts(row), ex);
            }
        }
    }

    private int attempts(EventOutboxDO row) {
        return (row.getRetryCount() == null ? 0 : row.getRetryCount()) + 1;
    }

    private String status(EventOutboxDO row) {
        return attempts(row) >= props.getMaxRetries() ? "FAILED" : "PENDING";
    }

    private Duration backoff(int attempt) {
        long base = Math.max(1L, props.getBaseBackoff().toMillis());
        long capped = Math.min(props.getMaxBackoff().toMillis(), base * (1L << Math.min(attempt - 1, 16)));
        return Duration.ofMillis(capped);
    }
}
