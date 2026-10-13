package com.fuzuyang.securities.order.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * 下单请求。{@code LIMIT} 必带正数 {@code orderPrice}；{@code MARKET} 不得携带价格（服务内校验）。
 */
public record CreateOrderRequest(
        @NotBlank String accountId,
        @NotBlank String clientOrderId,
        @NotBlank String securityCode,
        @NotBlank String side,
        @NotBlank String orderType,
        BigDecimal orderPrice,
        @NotNull @Positive Long quantity) {
}
