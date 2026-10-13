package com.fuzuyang.securities.order.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切日口径：business_date 取上海日期。
 */
class BusinessDateResolverTest {

    @Test
    void usesShanghaiZone() {
        assertThat(BusinessDateResolver.ZONE).isEqualTo(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void resolvesByShanghaiDate() {
        // 2026-01-01T16:30:00Z = 上海 2026-01-02 00:30
        assertThat(BusinessDateResolver.of(Instant.parse("2026-01-01T16:30:00Z")))
                .isEqualTo(LocalDate.of(2026, 1, 2));
        // 2026-01-01T15:59:59Z = 上海 2026-01-01 23:59:59
        assertThat(BusinessDateResolver.of(Instant.parse("2026-01-01T15:59:59Z")))
                .isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void todayMatchesShanghai() {
        assertThat(BusinessDateResolver.today()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Shanghai")));
    }
}
