package com.fuzuyang.securities.common.config;

/**
 * 事件 topic 名与派生命名的唯一来源。
 *
 * <p>topic 名与服务内事件映射、DLT 命名、消费者订阅三处强耦合，故收敛为代码常量，不做配置化。
 */
public final class EventTopics {

    /** 订单状态变更事件流：每 {@code (order_no, event_version)} 恰好一条 {@code ORDER_STATE_CHANGED}。 */
    public static final String ORDER_EVENTS = "order-events";

    /** 成交事件流：每 {@code (order_no, event_version)} 至多一条 {@code EXECUTION_APPLIED}。 */
    public static final String EXECUTION_EVENTS = "execution-events";

    /** 死信 topic 后缀。 */
    public static final String DLT_SUFFIX = ".DLT";

    private EventTopics() {
    }

    /**
     * 返回 topic 对应的死信 topic 名。
     *
     * @param topic 原 topic
     * @return {@code <topic>.DLT}
     */
    public static String dlt(String topic) {
        return topic + DLT_SUFFIX;
    }
}
