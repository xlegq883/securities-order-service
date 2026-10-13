package com.fuzuyang.securities.common.util;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

/**
 * 订单号生成器。
 *
 * <p>格式：{@code O + yyyyMMddHHmmssSSS + 6 位随机}（Asia/Shanghai）。
 *
 * <p><b>本类不保证唯一性</b> —— 唯一性永远由 {@code t_order.uk_order_no} 保证；
 * 生成器只负责降低碰撞概率。下单路径捕获唯一键冲突后必须回查区分两个唯一键
 * （命中 {@code uk_account_client} → 幂等分支；命中 {@code uk_order_no} → 重新生成并重试）。
 */
@Component
public class OrderNoGenerator {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final int RANDOM_BOUND = 1_000_000;

    private final SecureRandom random = new SecureRandom();

    public String next() {
        return "O" + LocalDateTime.now(ZONE).format(TIMESTAMP) + String.format("%06d", random.nextInt(RANDOM_BOUND));
    }
}
