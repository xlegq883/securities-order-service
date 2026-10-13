package com.fuzuyang.securities.execution;

import com.fuzuyang.securities.support.AbstractMySqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.UnexpectedRollbackException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T2 反向用例：事务内 catch 异常后继续 → 提交时必抛 {@code UnexpectedRollbackException}
 * （这正是"重试必须在事务之外"的根因，故 {@code OrderCommandRetry} 包在事务调用之外）。
 */
@ActiveProfiles("test")
@Import(TransactionBoundaryIT.RollbackConfig.class)
class TransactionBoundaryIT extends AbstractMySqlIT {

    @Autowired
    private OuterService outer;

    @Test
    void catchingInnerTransactionFailureInOuterMarksRollbackOnly() {
        assertThatThrownBy(() -> outer.run())
                .isInstanceOf(UnexpectedRollbackException.class);
    }

    @TestConfiguration
    static class RollbackConfig {

        @Bean
        OuterService outerService(JdbcTemplate jdbc, InnerService inner) {
            return new OuterService(jdbc, inner);
        }

        @Bean
        InnerService innerService() {
            return new InnerService();
        }
    }

    static class InnerService {

        @Transactional
        public void fail() {
            throw new IllegalStateException("inner failure");
        }
    }

    static class OuterService {

        private final JdbcTemplate jdbc;
        private final InnerService inner;

        OuterService(JdbcTemplate jdbc, InnerService inner) {
            this.jdbc = jdbc;
            this.inner = inner;
        }

        @Transactional
        public void run() {
            try {
                inner.fail(); // 内层事务标记 rollback-only
            } catch (RuntimeException ignored) {
                // 事务内 catch 后继续 —— 提交时必然抛 UnexpectedRollbackException
            }
            jdbc.update("INSERT INTO t_reconcile_diff (order_no, diff_type, dedupe_key, status, attempt_count) "
                    + "VALUES (?, ?, ?, 'OPEN', 0)", "TRANS-1", "READ_MODEL_DRIFT", "TRANS:1");
        }
    }
}
