package com.fuzuyang.securities.order.application.schedule;

import com.fuzuyang.securities.order.application.EventOutboxRelay;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 事件投递调度（条件化）。relay bean 常驻，只有调度被门控，测试中手动 {@code runOnce()}。
 */
@Component
@ConditionalOnProperty(prefix = "app.outbox", name = "relay-enabled", havingValue = "true", matchIfMissing = true)
public class EventOutboxRelayScheduler {

    private final EventOutboxRelay relay;

    public EventOutboxRelayScheduler(EventOutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${app.outbox.scan-interval-ms:1000}")
    public void tick() {
        relay.runOnce();
    }
}
