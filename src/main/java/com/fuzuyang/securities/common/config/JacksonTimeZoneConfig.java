package com.fuzuyang.securities.common.config;

import java.util.TimeZone;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 全链路时区固定为 Asia/Shanghai。
 *
 * <p>与 {@code spring.jackson.time-zone} 双保险：业务日期（{@code business_date}）取上海日期。
 */
@Configuration
public class JacksonTimeZoneConfig {

    /** 全链路统一时区。 */
    public static final String ZONE = "Asia/Shanghai";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonTimeZoneCustomizer() {
        return builder -> builder.timeZone(TimeZone.getTimeZone(ZONE));
    }
}
