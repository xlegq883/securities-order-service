package com.fuzuyang.securities.order.infrastructure.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code t_reconcile_diff} 行映射。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconcileDiffDO {

    private Long id;
    private LocalDate businessDate;
    private String orderNo;
    private String diffType;
    private String detail;
    private String dedupeKey;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
}
