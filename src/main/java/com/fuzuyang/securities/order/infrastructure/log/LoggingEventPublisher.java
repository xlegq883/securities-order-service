package com.fuzuyang.securities.order.infrastructure.log;

import com.fuzuyang.securities.order.application.port.EventPublisher;
import com.fuzuyang.securities.order.infrastructure.entity.EventOutboxDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 日志出站实现：离线/真库测试使用（{@code app.outbox.publisher=LOG}），不依赖 Kafka 自动配置。
 */
@Component
@ConditionalOnProperty(prefix = "app.outbox", name = "publisher", havingValue = "LOG")
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(EventOutboxDO event) {
        log.info("[outbox-log] topic={} key={} version={} payload={}", event.getTopic(), event.getMessageKey(),
                event.getEventVersion(), event.getPayload());
    }
}
