package com.fuzuyang.securities.order.api.dto;

import com.fuzuyang.securities.order.application.model.CancelView;
import java.time.LocalDateTime;

/**
 * 撤单请求响应（幂等命中同样返回 {@code upstreamCommandStatus}）。
 */
public record CancelOrderResponse(
        String orderNo,
        String status,
        LocalDateTime cancelRequestedAt,
        String upstreamCommandStatus) {

    public static CancelOrderResponse from(CancelView view) {
        return new CancelOrderResponse(view.orderNo(), view.status(), view.cancelRequestedAt(),
                view.upstreamCommandStatus());
    }
}
