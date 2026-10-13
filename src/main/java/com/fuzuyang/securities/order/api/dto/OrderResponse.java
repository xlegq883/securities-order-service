package com.fuzuyang.securities.order.api.dto;

import com.fuzuyang.securities.order.application.model.OrderView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 订单响应。
 */
public record OrderResponse(
        String orderNo,
        String accountId,
        String clientOrderId,
        String securityCode,
        String side,
        String orderType,
        BigDecimal orderPrice,
        Long quantity,
        BigDecimal amount,
        Long filledQuantity,
        BigDecimal filledAmount,
        String status,
        LocalDate businessDate,
        String exchangeOrderId,
        LocalDateTime cancelRequestedAt,
        Long eventVersion,
        String upstreamCommandStatus) {

    public static OrderResponse from(OrderView view) {
        return new OrderResponse(view.orderNo(), view.accountId(), view.clientOrderId(), view.securityCode(),
                view.side(), view.orderType(), view.orderPrice(), view.quantity(), view.amount(),
                view.filledQuantity(), view.filledAmount(), view.status(), view.businessDate(),
                view.exchangeOrderId(), view.cancelRequestedAt(), view.eventVersion(), view.upstreamCommandStatus());
    }
}
