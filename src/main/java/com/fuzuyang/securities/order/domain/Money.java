package com.fuzuyang.securities.order.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额口径工具。
 *
 * <p>金额列统一 {@code DECIMAL(18,4)}；小数数量 HALF_UP 取 4 位。
 * {@code exec_amount} 是唯一真源：对账禁止重算。整数数量下 {@code price × qty} 恰好 4 位、无需舍入。
 */
public final class Money {

    public static final int SCALE = 4;

    private Money() {
    }

    public static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** {@code exec_amount = exec_price × exec_quantity}，HALF_UP 取 4 位。 */
    public static BigDecimal multiply(BigDecimal price, long quantity) {
        return price.multiply(BigDecimal.valueOf(quantity)).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
