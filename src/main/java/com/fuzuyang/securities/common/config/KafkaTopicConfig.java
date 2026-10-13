package com.fuzuyang.securities.common.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka topic 显式声明。
 *
 * <p>topic 名统一取自 {@link EventTopics}；DLT 用 {@code <topic>.DLT} 派生。
 * 单 broker 本地环境 replicas 固定为 1。
 */
@Configuration
public class KafkaTopicConfig {

    private final int partitions;

    public KafkaTopicConfig(@Value("${app.outbox.partitions:3}") int partitions) {
        this.partitions = partitions;
    }

    @Bean
    public NewTopic orderEventsTopic() {
        return topic(EventTopics.ORDER_EVENTS);
    }

    @Bean
    public NewTopic orderEventsDltTopic() {
        return topic(EventTopics.dlt(EventTopics.ORDER_EVENTS));
    }

    @Bean
    public NewTopic executionEventsTopic() {
        return topic(EventTopics.EXECUTION_EVENTS);
    }

    @Bean
    public NewTopic executionEventsDltTopic() {
        return topic(EventTopics.dlt(EventTopics.EXECUTION_EVENTS));
    }

    private NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(partitions).replicas(1).build();
    }
}
