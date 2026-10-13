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
 * <p>profile = {@code local}（提供 datasource 且 {@code hikari.initialization-fail-timeout=-1}，无需真实 MySQL）
 * + {@code test}（出站通道切 LOG、关闭 relay/gap 调度、排除 Kafka 自动配置，
 * 避免 KafkaAdmin 启动期建 topic 阻塞）。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class ApplicationContextTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.getBean(SecuritiesOrderServiceApplication.class)).isNotNull();
        // relay bean 常驻、runOnce() 可调用（回归用例）
        assertThat(applicationContext.containsBean("eventOutboxRelay")).isTrue();
        assertThat(applicationContext.containsBean("upstreamCommandRelay")).isTrue();
    }

    @Test
    void eventTopicsDerivedDltNames() {
        assertThat(com.fuzuyang.securities.common.config.EventTopics.ORDER_EVENTS).isEqualTo("order-events");
        assertThat(com.fuzuyang.securities.common.config.EventTopics.dlt("order-events")).isEqualTo("order-events.DLT");
        assertThat(com.fuzuyang.securities.common.config.EventTopics.dlt("execution-events")).isEqualTo("execution-events.DLT");
    }
}
