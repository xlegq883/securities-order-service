package com.fuzuyang.securities.common.config;

import com.fuzuyang.securities.common.error.ErrorCode;
import com.fuzuyang.securities.common.exception.BusinessException;
import com.fuzuyang.securities.common.exception.RetryableDispatchException;
import com.fuzuyang.securities.common.metrics.AppMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.retry.support.RetryTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 重试白名单与耗尽语义。
 */
class RetryConfigTest {

    private final AppMetrics metrics = new AppMetrics(new SimpleMeterRegistry());
    private final RetryTemplate template = new RetryConfig().orderCommandRetryTemplate(metrics);
    private final OrderCommandRetry retry = new OrderCommandRetry(template, metrics);

    @Test
    void retriesWhitelistedDeadlock() {
        AtomicInteger calls = new AtomicInteger();
        Integer result = retry.execute(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new DeadlockLoserDataAccessException("deadlock", null);
            }
            return 42;
        });
        assertThat(result).isEqualTo(42);
        assertThat(calls).hasValue(3);
    }

    @Test
    void retriesWhitelistedLockTimeout() {
        AtomicInteger calls = new AtomicInteger();
        retry.execute(() -> {
            if (calls.incrementAndGet() < 2) {
                throw new CannotAcquireLockException("lock timeout");
            }
            return 1;
        });
        assertThat(calls).hasValue(2);
    }

    @Test
    void retriesRetryableDispatch() {
        AtomicInteger calls = new AtomicInteger();
        retry.execute(() -> {
            if (calls.incrementAndGet() < 2) {
                throw new RetryableDispatchException("O1", "暂态不一致");
            }
            return 1;
        });
        assertThat(calls).hasValue(2);
    }

    @Test
    void doesNotRetryUnlistedException() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> retry.execute(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void exhaustionMapsToRetryExhausted() {
        assertThatThrownBy(() -> retry.execute(() -> {
            throw new CannotAcquireLockException("always");
        }))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RETRY_EXHAUSTED);
    }
}
