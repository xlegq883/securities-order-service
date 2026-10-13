package com.fuzuyang.securities.kafka;

import com.fuzuyang.securities.common.config.EventTopics;
import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3 真 Kafka 语义 smoke（EmbeddedKafka 真实 broker）。
 *
 * <p>不加载应用上下文，仅验证 broker 收发链路与 topic / key 一致；
 * 消费端语义（位点、DLT、重投）由批次 4 的 T3 覆盖。
 */
@ExtendWith(SpringExtension.class)
@EmbeddedKafka(partitions = 1, topics = {EventTopics.ORDER_EVENTS})
class KafkaSmokeIT {

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Test
    void produceAndConsumeSingleRecord() throws Exception {
        Map<String, Object> producerProps = KafkaTestUtils.producerProps(broker);
        try (Producer<String, String> producer =
                     new KafkaProducer<>(producerProps, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(EventTopics.ORDER_EVENTS, "order-1", "payload-1")).get();
        }

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("kafka-smoke", "true", broker);
        try (Consumer<String, String> consumer =
                     new KafkaConsumer<>(consumerProps, new StringDeserializer(), new StringDeserializer())) {
            broker.consumeFromAnEmbeddedTopic(consumer, EventTopics.ORDER_EVENTS);
            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(
                    consumer, EventTopics.ORDER_EVENTS, Duration.ofSeconds(10));

            assertThat(record.topic()).isEqualTo("order-events");
            assertThat(record.key()).isEqualTo("order-1");
            assertThat(record.value()).isEqualTo("payload-1");
        }
    }
}
