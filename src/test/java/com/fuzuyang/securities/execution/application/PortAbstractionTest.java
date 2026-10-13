package com.fuzuyang.securities.execution.application;

import com.fuzuyang.securities.order.application.EventOutboxRelay;
import com.fuzuyang.securities.order.application.UpstreamCommandRelay;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端口化断言：relay 的构造参数与字段不得出现中间件客户端。
 */
class PortAbstractionTest {

    private static final String[] FORBIDDEN = {"KafkaTemplate", "KafkaProducer", "KafkaConsumer", "KafkaAdmin"};

    @Test
    void relaysDoNotDependOnKafkaClients() {
        assertNoKafkaClients(EventOutboxRelay.class);
        assertNoKafkaClients(UpstreamCommandRelay.class);
    }

    private void assertNoKafkaClients(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            assertThat(field.getType().getName())
                    .as("%s.%s 不得直接依赖中间件客户端", type.getSimpleName(), field.getName())
                    .doesNotContain(FORBIDDEN);
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertThat(parameter.getName())
                        .as("%s 构造参数不得直接依赖中间件客户端", type.getSimpleName())
                        .doesNotContain(FORBIDDEN);
            }
        }
    }
}
