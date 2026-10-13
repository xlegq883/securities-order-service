package com.fuzuyang.securities.execution.infrastructure.entity;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code t_report_event} 行映射（回报一次性处理拦截 + 缺口"已收到"集合）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportEventDO {

    private Long id;
    private String reportType;
    private String orderNo;
    private String dedupeKey;
    private String exchangeOrderId;
    private Long exchangeSeq;
    private String executionId;
    private Long execSeq;
    private String source;
    private Boolean applied;
    private String payload;
    private LocalDateTime createdAt;
}
