package com.fuzuyang.securities.execution.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 回报响应：统一 200，{@code {orderNo, applied, diffType?, filledQuantity?, status?}}。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportResponse(
        String orderNo,
        boolean applied,
        String diffType,
        Long filledQuantity,
        String status) {
}
