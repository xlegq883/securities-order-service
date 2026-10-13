package com.fuzuyang.securities.kafka;

import com.fuzuyang.securities.common.config.EventTopics;
import com.fuzuyang.securities.execution.application.ReportDispatcher;
import com.fuzuyang.securities.execution.application.model.ReportCommand;
import com.fuzuyang.securities.order.application.EventOutboxRelay;
import com.fuzuyang.securities.order.application.OrderCreationService;
import com.fuzuyang.securities.order.application.model.CreateOrderCommand;
import com.fuzuyang.securities.support.AbstractMySqlIT;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3 真 Kafka 语义：下单 → ACK → 全额成交 → relay 发出，断言两个 topic 的条数与内容。
 */
@ActiveProfiles("test-kafka")
@EmbeddedKafka(partitions = 1, topics = {EventTopics.ORDER_EVENTS, EventTopics.EXECUTION_EVENTS},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class EventPublishIT extends AbstractMySqlIT {

    private static final AtomicLong SEQ = new AtomicLong(1);

    @Autowired
    private EmbeddedKafkaBroker broker;
    @Autowired
    private OrderCreationService creationService;
    @Autowired
    private ReportDispatcher dispatcher;
    @Autowired
    private EventOutboxRelay eventRelay;
    @Autowired
    private MeterRegistry registry;
    @Autowired
    private JdbcTemplate jdbc;

    /** 共享库：先清空遗留 outbox，保证只发本用例产生的事件。 */
    @BeforeEach
    void isolate() {
        jdbc.update("DELETE FROM t_event_outbox");
    }

    @Test
    void publishesOrderAndExecutionEvents() {
        String orderNo = creationService.create(new CreateOrderCommand(
                "ACC-EVT", UUID.randomUUID().toString(), "600000", "BUY", "LIMIT", new BigDecimal("10"), 100L))
                .orderNo();
        dispatcher.dispatch(new ReportCommand("ORDER_ACKED", orderNo, "X" + orderNo, SEQ.getAndIncrement(),
                null, null, null, null, null, null, "REALTIME"));
        dispatcher.dispatch(new ReportCommand("EXECUTION", orderNo, null, SEQ.getAndIncrement(), "K1", 1L, 100L,
                new BigDecimal("10"), null, LocalDateTime.now(), "REALTIME"));

        double sentBefore = registry.counter("event.outbox.sent").count();
        eventRelay.runOnce();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_event_outbox WHERE status <> 'SENT'", Integer.class))
                .isZero();
        assertThat(registry.counter("event.outbox.sent").count()).isGreaterThan(sentBefore);

        List<ConsumerRecord<String, String>> orderRecords = consume(EventTopics.ORDER_EVENTS, 3).stream()
                .filter(r -> orderNo.equals(r.key()))
                .toList();
        assertThat(orderRecords).hasSize(3);
        assertThat(orderRecords.get(0).value()).contains("\"change_type\":\"SUBMITTED\"");
        assertThat(orderRecords.get(1).value()).contains("\"change_type\":\"ACKED\"");
        assertThat(orderRecords.get(2).value()).contains("\"change_type\":\"FILLED\"");

        List<ConsumerRecord<String, String>> executionRecords = consume(EventTopics.EXECUTION_EVENTS, 1).stream()
                .filter(r -> orderNo.equals(r.key()))
                .toList();
        assertThat(executionRecords).hasSize(1);
        assertThat(executionRecords.get(0).value()).contains("\"execution_id\":\"K1\"");
    }

    private List<ConsumerRecord<String, String>> consume(String topic, int expected) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("evt-" + UUID.randomUUID(), "true", broker);
        try (Consumer<String, String> consumer =
                     new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            broker.consumeFromAnEmbeddedTopic(consumer, topic);
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 15_000L;
            while (records.size() < expected && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> batch = consumer.poll(Duration.ofMillis(500));
                batch.forEach(records::add);
            }
            return records;
        }
    }
}
