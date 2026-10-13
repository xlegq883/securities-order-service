package com.fuzuyang.securities.order.application.port;

import com.fuzuyang.securities.order.infrastructure.entity.EventOutboxDO;

/**
 * 事件出站端口。
 *
 * <p>中间件客户端只允许出现在实现类（{@code KafkaEventPublisher}）；
 * relay 与 handler 只依赖本端口。
 */
public interface EventPublisher {

    void publish(EventOutboxDO event) throws Exception;
}
