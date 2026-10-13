package com.fuzuyang.securities.execution.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 归一化的成交回报（域模型）。
 *
 * <p>{@code execSeq} 为订单内从 1 起、期望连续递增的成交序号；上游违约即数据完整性问题（缺口检测）。
 */
public record ExecutionReport(
        String orderNo,
        String executionId,
        Long exchangeSeq,
        long execSeq,
        long execQuantity,
        BigDecimal execPrice,
        BigDecimal execAmount,
        LocalDateTime execTime,
        String source) {
}
