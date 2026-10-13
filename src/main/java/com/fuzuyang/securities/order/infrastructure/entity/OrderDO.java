package com.fuzuyang.securities.order.infrastructure.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code t_order} 行映射。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDO {

    private Long id;
    private String orderNo;
    private String accountId;
    private String securityCode;
    private String side;
    private String orderType;
    private BigDecimal orderPrice;
    private Long quantity;
    private BigDecimal amount;
    private Long filledQuantity;
    private BigDecimal filledAmount;
    private Long lastExecSeq;
    private Long eventVersion;
    private String status;
    private LocalDate businessDate;
    private String exchangeOrderId;
    private LocalDateTime cancelRequestedAt;
    private String clientOrderId;
    private LocalDateTime createdAt;
}
