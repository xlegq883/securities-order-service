package com.fuzuyang.securities.execution.infrastructure.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code t_execution} 行映射（只存已应用成交）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionDO {

    private Long id;
    private String executionId;
    private String orderNo;
    private Long execSeq;
    private Long execQuantity;
    private BigDecimal execPrice;
    private BigDecimal execAmount;
    private LocalDateTime execTime;
    private LocalDate businessDate;
    private LocalDateTime createdAt;
}
