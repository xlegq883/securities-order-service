package com.fuzuyang.securities.common.config;

import com.fuzuyang.securities.common.exception.RetryableDispatchException;
import com.fuzuyang.securities.common.metrics.AppMetrics;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * 外层重试配置。
 *
 * <p>白名单仅 {@link DeadlockLoserDataAccessException} / {@link CannotAcquireLockException}；
 * 尝试 3 次、退避 50ms/200ms。{@code RetryTemplate} **包在跨 Bean 的事务调用之外**
 * （事务内 catch 再重试必然抛 {@code UnexpectedRollbackException}）。
 */
@Configuration
public class RetryConfig {

    public static final int MAX_ATTEMPTS = 3;

    /** 白名单（单一来源）：只有这些异常会被外层重试，也只有它们耗尽时映射为 503。 */
    public static boolean isRetryable(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        return throwable instanceof DeadlockLoserDataAccessException
                || throwable instanceof CannotAcquireLockException
                || throwable instanceof RetryableDispatchException;
    }

    @Bean
    public RetryTemplate orderCommandRetryTemplate(AppMetrics metrics) {
        RetryTemplate template = new RetryTemplate();

        Map<Class<? extends Throwable>, Boolean> retryable = new HashMap<>();
        retryable.put(DeadlockLoserDataAccessException.class, true);
        retryable.put(CannotAcquireLockException.class, true);
        // 0 行分类矩阵的兜底分支（批次 1 执行单 §3.4）：暂态不一致，交由外层重试
        retryable.put(RetryableDispatchException.class, true);
        // BinaryExceptionClassifier 默认 false：白名单之外的异常一律不重试
        template.setRetryPolicy(new SimpleRetryPolicy(MAX_ATTEMPTS, retryable));

        ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
        backOff.setInitialInterval(50L);
        backOff.setMultiplier(4.0);
        backOff.setMaxInterval(1000L);
        template.setBackOffPolicy(backOff);

        template.registerListener(new RetryListener() {
            @Override
            public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback,
                    Throwable throwable) {
                metrics.retryAttempt();
            }
        });
        return template;
    }
}
