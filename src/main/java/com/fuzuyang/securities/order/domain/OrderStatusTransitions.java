package com.fuzuyang.securities.order.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 状态迁移矩阵（DAG）。
 *
 * <p>用矩阵表驱动而非链式判断：{@code ACKNOWLEDGED→FILLED}、{@code ACKNOWLEDGED→CANCELED}、
 * {@code PARTIALLY_FILLED→CANCELED/EXPIRED} 都是一步迁移，链式写法会把合法迁移判成非法。
 *
 * <p>{@code PARTIALLY_FILLED→PARTIALLY_FILLED} 是自环（继续部分成交，只累加量额、状态不变）。
 */
public final class OrderStatusTransitions {

    private static final Map<OrderStatus, Set<OrderStatus>> MATRIX = Map.of(
            OrderStatus.SUBMITTED, EnumSet.of(
                    OrderStatus.ACKNOWLEDGED, OrderStatus.EXPIRED, OrderStatus.REJECTED),
            OrderStatus.ACKNOWLEDGED, EnumSet.of(
                    OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED, OrderStatus.CANCELED, OrderStatus.EXPIRED),
            OrderStatus.PARTIALLY_FILLED, EnumSet.of(
                    OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED, OrderStatus.CANCELED, OrderStatus.EXPIRED),
            OrderStatus.FILLED, EnumSet.noneOf(OrderStatus.class),
            OrderStatus.CANCELED, EnumSet.noneOf(OrderStatus.class),
            OrderStatus.EXPIRED, EnumSet.noneOf(OrderStatus.class),
            OrderStatus.REJECTED, EnumSet.noneOf(OrderStatus.class));

    private OrderStatusTransitions() {
    }

    public static boolean canTransition(OrderStatus from, OrderStatus to) {
        return MATRIX.getOrDefault(from, Set.of()).contains(to);
    }

    public static Set<OrderStatus> targets(OrderStatus from) {
        return Set.copyOf(MATRIX.getOrDefault(from, Set.of()));
    }
}
