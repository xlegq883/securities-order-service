package com.fuzuyang.securities.execution.application.model;

import com.fuzuyang.securities.common.error.DiffType;

/**
 * 回报处理结果（应用层模型）：统一 200 的响应体语义。
 */
public record ReportResult(
        String orderNo,
        boolean applied,
        DiffType diffType,
        Long filledQuantity,
        String status) {

    public static ReportResult applied(String orderNo, String status, Long filledQuantity) {
        return new ReportResult(orderNo, true, null, filledQuantity, status);
    }

    public static ReportResult rejected(String orderNo, DiffType diffType) {
        return new ReportResult(orderNo, false, diffType, null, null);
    }
}
