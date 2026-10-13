package com.fuzuyang.securities.order.application.model;

import com.fuzuyang.securities.order.infrastructure.entity.OrderDO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 订单视图（应用层模型；含指令投递状态，供幂等命中响应使用）。
 */
public record OrderView(
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

    public static OrderView from(OrderDO order, String upstreamCommandStatus) {
        return new OrderView(
                order.getOrderNo(),
                order.getAccountId(),
                order.getClientOrderId(),
                order.getSecurityCode(),
                order.getSide(),
                order.getOrderType(),
                order.getOrderPrice(),
                order.getQuantity(),
                order.getAmount(),
                order.getFilledQuantity(),
                order.getFilledAmount(),
                order.getStatus(),
                order.getBusinessDate(),
                order.getExchangeOrderId(),
                order.getCancelRequestedAt(),
                order.getEventVersion(),
                upstreamCommandStatus);
    }
}
