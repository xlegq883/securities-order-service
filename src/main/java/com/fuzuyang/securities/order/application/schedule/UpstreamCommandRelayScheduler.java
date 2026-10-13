package com.fuzuyang.securities.order.application.schedule;

import com.fuzuyang.securities.order.application.UpstreamCommandRelay;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 指令投递调度（条件化）。relay bean 常驻，只有调度被门控，测试中手动 {@code runOnce()}。
 */
@Component
@ConditionalOnProperty(prefix = "app.upstream", name = "command-relay-enabled", havingValue = "true",
        matchIfMissing = true)
public class UpstreamCommandRelayScheduler {

    private final UpstreamCommandRelay relay;

    public UpstreamCommandRelayScheduler(UpstreamCommandRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${app.upstream.scan-interval-ms:1000}")
    public void tick() {
        relay.runOnce();
    }
}
