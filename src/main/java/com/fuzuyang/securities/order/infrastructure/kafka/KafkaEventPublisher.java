package com.fuzuyang.securities.order.infrastructure.kafka;

import com.fuzuyang.securities.common.config.OutboxProperties;
import com.fuzuyang.securities.order.application.port.EventPublisher;
import com.fuzuyang.securities.order.infrastructure.entity.EventOutboxDO;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka 出站实现（中间件客户端只允许出现在本类）。
 */
@Component
@ConditionalOnProperty(prefix = "app.outbox", name = "publisher", havingValue = "KAFKA", matchIfMissing = true)
public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties props;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate, OutboxProperties props) {
        this.kafkaTemplate = kafkaTemplate;
        this.props = props;
    }

    @Override
    public void publish(EventOutboxDO event) throws Exception {
        kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload())
                .get(props.getSendTimeoutMs(), TimeUnit.MILLISECONDS);
    }
}
