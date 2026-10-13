package com.fuzuyang.securities.order.application.model;

import java.math.BigDecimal;

/**
 * 下单用例入参（应用层模型）。
 */
public record CreateOrderCommand(
        String accountId,
        String clientOrderId,
        String securityCode,
        String side,
        String orderType,
        BigDecimal orderPrice,
        Long quantity) {
}
