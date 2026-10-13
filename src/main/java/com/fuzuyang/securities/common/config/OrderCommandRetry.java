package com.fuzuyang.securities.common.config;

import com.fuzuyang.securities.common.error.ErrorCode;
import com.fuzuyang.securities.common.exception.BusinessException;
import com.fuzuyang.securities.common.metrics.AppMetrics;
import java.util.function.Supplier;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

/**
 * 外层重试壳：跨 Bean 调用事务方法的唯一入口。
 *
 * <p>调用方传入的 {@code action} 必须是**另一个 Bean 的事务方法**（跨 Bean 才有代理与事务边界）；
 * 重试在此壳内、事务之外发生。
 *
 * <p>只有白名单异常（见 {@link RetryConfig#isRetryable}）耗尽时才映射为 503 {@code RETRY_EXHAUSTED}；
 * 非白名单异常原样抛出（例如业务异常、参数非法）。
 */
@Component
public class OrderCommandRetry {

    private final RetryTemplate orderCommandRetryTemplate;
    private final AppMetrics metrics;

    public OrderCommandRetry(RetryTemplate orderCommandRetryTemplate, AppMetrics metrics) {
        this.orderCommandRetryTemplate = orderCommandRetryTemplate;
        this.metrics = metrics;
    }

    public <T> T execute(Supplier<T> action) {
        return orderCommandRetryTemplate.execute(
                context -> action.get(),
                context -> {
                    Throwable cause = context.getLastThrowable();
                    if (RetryConfig.isRetryable(cause)) {
                        metrics.retryExhausted();
                        throw new BusinessException(ErrorCode.RETRY_EXHAUSTED,
                                cause == null ? ErrorCode.RETRY_EXHAUSTED.getDefaultMessage() : cause.getMessage());
                    }
                    if (cause instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    throw new IllegalStateException("重试失败", cause);
                });
    }
}
