package com.fuzuyang.securities.order.domain;

/**
 * 订单状态（单线七态 DAG；后四者为终态）。
 */
public enum OrderStatus {

    SUBMITTED,
    ACKNOWLEDGED,
    PARTIALLY_FILLED,
    FILLED,
    CANCELED,
    EXPIRED,
    REJECTED;

    public boolean isTerminal() {
        return this == FILLED || this == CANCELED || this == EXPIRED || this == REJECTED;
    }
}
