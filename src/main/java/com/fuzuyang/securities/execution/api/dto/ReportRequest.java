package com.fuzuyang.securities.execution.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 回报请求（四类共用）。
 */
public record ReportRequest(
        String reportType,
        String orderNo,
        String exchangeOrderId,
        Long exchangeSeq,
        String executionId,
        Long execSeq,
        Long execQuantity,
        BigDecimal execPrice,
        BigDecimal execAmount,
        LocalDateTime execTime,
        String source) {
}
