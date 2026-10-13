package com.fuzuyang.securities.execution;

import com.fuzuyang.securities.common.error.DiffType;
import com.fuzuyang.securities.execution.application.ReportDispatcher;
import com.fuzuyang.securities.execution.application.model.ReportCommand;
import com.fuzuyang.securities.execution.application.model.ReportResult;
import com.fuzuyang.securities.order.application.OrderCreationService;
import com.fuzuyang.securities.order.application.model.CreateOrderCommand;
import com.fuzuyang.securities.support.AbstractMySqlIT;
import com.fuzuyang.securities.support.ItSupportConfig;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2 跨订单 {@code executionId} 冲突的**兜底路径**（关闭事前检查，强制走
 * "竞态穿透 → 主事务回滚 → REQUIRES_NEW 落差异"）：必须 200、无 500、无 UnexpectedRollbackException。
 */
@SpringBootTest(properties = {
        "app.execution-conflict.precheck-enabled=false",
        "app.upstream.command-transport=RECORDING",
        "app.outbox.publisher=LOG"
})
@ActiveProfiles("test")
@Import(ItSupportConfig.class)
class DataConflictFallbackIT extends AbstractMySqlIT {

    private static final AtomicLong SEQ = new AtomicLong(5000);

    @Autowired
    private OrderCreationService creationService;
    @Autowired
    private ReportDispatcher dispatcher;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void raceFallbackRollsBackMainTransactionAndRecordsDiff() {
        String orderA = create();
        dispatch(ack(orderA));
        dispatch(exec(orderA, "FB", 1, 10, "10"));

        String orderB = create();
        dispatch(ack(orderB));

        ReportResult result = dispatch(exec(orderB, "FB", 1, 10, "10"));
        assertThat(result.applied()).isFalse();
        assertThat(result.diffType()).isEqualTo(DiffType.DATA_CONFLICT);
        // 主事务回滚：B 的量额未变、明细无新行
        assertThat(jdbc.queryForObject("SELECT filled_quantity FROM t_order WHERE order_no=?", Long.class, orderB))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_execution WHERE order_no=?", Integer.class, orderB))
                .isZero();
        // REQUIRES_NEW 落差异
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_reconcile_diff WHERE diff_type='DATA_CONFLICT' AND order_no=?",
                Integer.class, orderB)).isEqualTo(1);

        // 重复投递同一脏回报：差异不重复
        ReportResult again = dispatch(exec(orderB, "FB", 1, 10, "10"));
        assertThat(again.diffType()).isEqualTo(DiffType.DATA_CONFLICT);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_reconcile_diff WHERE diff_type='DATA_CONFLICT' AND order_no=?",
                Integer.class, orderB)).isEqualTo(1);
    }

    private String create() {
        return creationService.create(new CreateOrderCommand("ACC-FB", UUID.randomUUID().toString(), "600000",
                "BUY", "LIMIT", new BigDecimal("10"), 100L)).orderNo();
    }

    private ReportResult dispatch(ReportCommand cmd) {
        return dispatcher.dispatch(cmd);
    }

    private ReportCommand ack(String orderNo) {
        return new ReportCommand("ORDER_ACKED", orderNo, "X" + orderNo, SEQ.getAndIncrement(), null, null, null,
                null, null, null, "REALTIME");
    }

    private ReportCommand exec(String orderNo, String executionId, long execSeq, long quantity, String price) {
        return new ReportCommand("EXECUTION", orderNo, null, SEQ.getAndIncrement(), executionId, execSeq,
                quantity, new BigDecimal(price), null, LocalDateTime.now(), "REALTIME");
    }
}
