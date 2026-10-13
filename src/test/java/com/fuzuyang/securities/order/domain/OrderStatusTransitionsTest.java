package com.fuzuyang.securities.order.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移矩阵 7×7 全组合 + isTerminal。
 */
class OrderStatusTransitionsTest {

    private static final Map<OrderStatus, Set<OrderStatus>> EXPECTED = Map.of(
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

    @Test
    void fullMatrix() {
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                boolean expected = EXPECTED.get(from).contains(to);
                assertThat(OrderStatusTransitions.canTransition(from, to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void terminalStates() {
        assertThat(OrderStatus.FILLED.isTerminal()).isTrue();
        assertThat(OrderStatus.CANCELED.isTerminal()).isTrue();
        assertThat(OrderStatus.EXPIRED.isTerminal()).isTrue();
        assertThat(OrderStatus.REJECTED.isTerminal()).isTrue();
        assertThat(OrderStatus.SUBMITTED.isTerminal()).isFalse();
        assertThat(OrderStatus.ACKNOWLEDGED.isTerminal()).isFalse();
        assertThat(OrderStatus.PARTIALLY_FILLED.isTerminal()).isFalse();
    }

    @Test
    void partiallyFilledSelfLoopAllowed() {
        assertThat(OrderStatusTransitions.canTransition(OrderStatus.PARTIALLY_FILLED, OrderStatus.PARTIALLY_FILLED))
                .isTrue();
    }
}
