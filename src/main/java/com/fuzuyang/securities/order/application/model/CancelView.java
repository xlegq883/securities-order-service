package com.fuzuyang.securities.order.application.model;

import java.time.LocalDateTime;

/**
 * 撤单结果视图。
 */
public record CancelView(
        String orderNo,
        String status,
        LocalDateTime cancelRequestedAt,
        String upstreamCommandStatus) {
}
