package com.fuzuyang.securities.order.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 业务日期解析：全链路 Asia/Shanghai，{@code business_date} = 下单时刻的上海日期。
 */
public final class BusinessDateResolver {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private BusinessDateResolver() {
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public static LocalDate of(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }
}
