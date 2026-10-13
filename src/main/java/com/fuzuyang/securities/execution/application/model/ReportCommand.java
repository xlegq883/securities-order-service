package com.fuzuyang.securities.execution.application.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 回报统一入参（应用层模型）。四类回报共用，避免每类一个入口。
 */
public record ReportCommand(
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
