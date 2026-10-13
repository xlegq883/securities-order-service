package com.fuzuyang.securities;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1 纯逻辑单测：Spring 上下文可加载。
 *
 * <p>依赖 {@code application-local.yml} 的 {@code hikari.initialization-fail-timeout=-1}，
 * 因此无需真实 MySQL 即可启动。
 *
 * <p>此处排除 Kafka 自动配置：KafkaAdmin 会在启动时尝试创建 topic，单 broker 不可达时默认
 * operationTimeout 30s 会把纯逻辑测试拖慢；Kafka 的真实语义由 T3（EmbeddedKafka）覆盖。
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
@ActiveProfiles("local")
class ApplicationContextTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.getBean(SecuritiesOrderServiceApplication.class)).isNotNull();
    }

    @Test
    void eventTopicsDerivedDltNames() {
        assertThat(com.fuzuyang.securities.common.config.EventTopics.ORDER_EVENTS).isEqualTo("order-events");
        assertThat(com.fuzuyang.securities.common.config.EventTopics.dlt("order-events")).isEqualTo("order-events.DLT");
        assertThat(com.fuzuyang.securities.common.config.EventTopics.dlt("execution-events")).isEqualTo("execution-events.DLT");
    }
}
