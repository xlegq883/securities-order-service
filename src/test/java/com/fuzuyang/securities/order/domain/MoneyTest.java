package com.fuzuyang.securities.order.domain;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 金额口径与舍入。
 */
class MoneyTest {

    @Test
    void multiplyScalesToFour() {
        assertThat(Money.multiply(new BigDecimal("10.5"), 3)).isEqualByComparingTo("31.5000");
        assertThat(Money.multiply(new BigDecimal("0.3333"), 3)).isEqualByComparingTo("0.9999");
    }

    @Test
    void multiplyHalfUp() {
        // 0.00005 × 1 → HALF_UP 取 4 位
        assertThat(Money.multiply(new BigDecimal("0.00005"), 1)).isEqualByComparingTo("0.0001");
        assertThat(Money.multiply(new BigDecimal("0.00004"), 1)).isEqualByComparingTo("0.0000");
    }

    @Test
    void scaleNullSafe() {
        assertThat(Money.scale(null)).isNull();
        assertThat(Money.zero()).isEqualByComparingTo("0.0000");
    }

    @Test
    void changeTypeForFill() {
        assertThat(ChangeType.forFill(true)).isEqualTo(ChangeType.FILLED);
        assertThat(ChangeType.forFill(false)).isEqualTo(ChangeType.FILL_UPDATED);
    }

    @Test
    void changeTypeCoversSubmitted() {
        assertThat(ChangeType.valueOf("SUBMITTED")).isEqualTo(ChangeType.SUBMITTED);
    }
}
