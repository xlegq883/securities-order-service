package com.fuzuyang.securities.order.application;

import com.fuzuyang.securities.common.config.UpstreamProperties;
import com.fuzuyang.securities.common.metrics.AppMetrics;
import com.fuzuyang.securities.order.application.port.UpstreamCommandSender;
import com.fuzuyang.securities.order.infrastructure.entity.UpstreamCommandDO;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 上游指令投递 relay（与事件 relay 同构的至少一次语义）。
 *
 * <p>按**行内** transport 选择 sender；找不到实现即该行直接 {@code FAILED} +
 * {@code UPSTREAM_SUBMIT_FAILED}，不静默换通道。
 *
 * <p>{@code SENT} = 已交给出站通道，**不代表上游受理**（受理由 ACKED 回报驱动）。
 */
@Service
public class UpstreamCommandRelay {

    private static final Logger log = LoggerFactory.getLogger(UpstreamCommandRelay.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 200L;
    private static final long MAX_BACKOFF_MS = 5_000L;

    private final UpstreamCommandStore store;
    private final UpstreamProperties props;
    private final AppMetrics metrics;
    private final Map<String, UpstreamCommandSender> senders;

    public UpstreamCommandRelay(UpstreamCommandStore store, List<UpstreamCommandSender> senderList,
            UpstreamProperties props, AppMetrics metrics) {
        this.store = store;
        this.props = props;
        this.metrics = metrics;
        this.senders = senderList.stream()
                .collect(Collectors.toMap(UpstreamCommandSender::transport, Function.identity()));
    }

    public void runOnce() {
        List<UpstreamCommandDO> batch = store.claim(props.getBatchSize(), props.getClaimLeaseSeconds());
        for (UpstreamCommandDO row : batch) {
            UpstreamCommandSender sender = senders.get(row.getTransport());
            if (sender == null) {
                store.markFailed(row, MAX_ATTEMPTS, "FAILED", 0L,
                        "无匹配 transport 的 sender: " + row.getTransport());
                continue;
            }
            try {
                sender.send(row);
                if (store.markSent(row.getId(), row.getOwner()) > 0) {
                    metrics.upstreamCommandSent();
                }
            } catch (Exception ex) {
                int attempt = (row.getAttemptCount() == null ? 0 : row.getAttemptCount()) + 1;
                boolean exhausted = attempt >= MAX_ATTEMPTS;
                store.markFailed(row, attempt, exhausted ? "FAILED" : "PENDING",
                        backoff(attempt).toMillis() * 1000L, ex.getMessage());
                log.warn("指令投递失败 orderNo={} type={} attempt={} exhausted={}", row.getOrderNo(),
                        row.getCommandType(), attempt, exhausted, ex);
            }
        }
    }

    private Duration backoff(int attempt) {
        long capped = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * (1L << Math.min(attempt - 1, 16)));
        return Duration.ofMillis(capped);
    }
}
