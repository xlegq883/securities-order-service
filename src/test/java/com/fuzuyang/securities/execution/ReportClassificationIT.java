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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2 回报分类：撤单确认分支、终态矩阵、跨订单 executionId 冲突（事前检查路径）。
 */
@ActiveProfiles("test")
@Import(ItSupportConfig.class)
class ReportClassificationIT extends AbstractMySqlIT {

    private static final AtomicLong SEQ = new AtomicLong(1000);

    @Autowired
    private OrderCreationService creationService;
    @Autowired
    private ReportDispatcher dispatcher;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void cancelAckBranches() {
        // ACK → 撤单确认 → CANCELED
        String ack = create();
        dispatch(ack(ack));
        ReportResult canceled = dispatch(cancelAck(ack));
        assertThat(canceled.applied()).isTrue();
        assertThat(statusOf(ack)).isEqualTo("CANCELED");

        // FILLED → CANCEL_AFTER_FILL
        String filled = create();
        dispatch(ack(filled));
        dispatch(exec(filled, "ECF", 1, 100, "10"));
        assertThat(dispatch(cancelAck(filled)).diffType()).isEqualTo(DiffType.CANCEL_AFTER_FILL);
        assertThat(statusOf(filled)).isEqualTo("FILLED");

        // CANCELED → AFTER_TERMINAL
        assertThat(dispatch(cancelAck(ack)).diffType()).isEqualTo(DiffType.AFTER_TERMINAL);

        // SUBMITTED → ILLEGAL_TRANSITION
        String submitted = create();
        assertThat(dispatch(cancelAck(submitted)).diffType()).isEqualTo(DiffType.ILLEGAL_TRANSITION);

        // 不存在 → ORDER_NOT_FOUND
        assertThat(dispatch(cancelAck("no-such")).diffType()).isEqualTo(DiffType.ORDER_NOT_FOUND);
    }

    @Test
    void afterTerminalMatrix() {
        String filled = create();
        dispatch(ack(filled));
        dispatch(exec(filled, "EAT", 1, 100, "10"));
        assertAfterTerminal(filled, "FILLED");

        String canceled = create();
        dispatch(ack(canceled));
        dispatch(cancelAck(canceled));
        assertAfterTerminal(canceled, "CANCELED");

        String rejected = create();
        dispatch(reject(rejected));
        assertAfterTerminal(rejected, "REJECTED");

        String expired = create();
        jdbc.update("UPDATE t_order SET status='EXPIRED', event_version=event_version+1 WHERE order_no=?", expired);
        assertAfterTerminal(expired, "EXPIRED");
    }

    @Test
    void executionIdOwnedByOtherOrderPrecheck() {
        String orderA = create();
        dispatch(ack(orderA));
        dispatch(exec(orderA, "SHARED", 1, 10, "10"));

        String orderB = create();
        dispatch(ack(orderB));
        ReportResult conflict = dispatch(exec(orderB, "SHARED", 1, 10, "10"));

        assertThat(conflict.applied()).isFalse();
        assertThat(conflict.diffType()).isEqualTo(DiffType.DATA_CONFLICT);
        assertThat(filledQuantity(orderB)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_execution WHERE order_no=? AND execution_id=?",
                Integer.class, orderB, "SHARED")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT applied FROM t_report_event WHERE dedupe_key=?", Integer.class,
                "EXECUTION:" + orderB + ":SHARED")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_reconcile_diff WHERE diff_type='DATA_CONFLICT' AND order_no=?",
                Integer.class, orderB)).isEqualTo(1);
    }

    // ---------- helpers ----------

    private void assertAfterTerminal(String orderNo, String expectedStatus) {
        long eventsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM t_event_outbox WHERE aggregate_id=?",
                Long.class, orderNo);

        assertThat(dispatch(ack(orderNo)).diffType()).isEqualTo(DiffType.AFTER_TERMINAL);
        assertThat(dispatch(reject(orderNo)).diffType()).isEqualTo(DiffType.AFTER_TERMINAL);
        assertThat(dispatch(exec(orderNo, "LATE-" + orderNo, 9, 1, "10")).diffType())
                .isEqualTo(DiffType.EXEC_AFTER_TERMINAL);

        assertThat(statusOf(orderNo)).isEqualTo(expectedStatus);
        assertThat(filledQuantity(orderNo)).isEqualTo("FILLED".equals(expectedStatus) ? 100L : 0L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_event_outbox WHERE aggregate_id=?", Long.class,
                orderNo)).isEqualTo(eventsBefore);
    }

    private String create() {
        return creationService.create(new CreateOrderCommand("ACC-RC", UUID.randomUUID().toString(), "600000",
                "BUY", "LIMIT", new BigDecimal("10"), 100L)).orderNo();
    }

    private ReportResult dispatch(ReportCommand cmd) {
        return dispatcher.dispatch(cmd);
    }

    private ReportCommand ack(String orderNo) {
        return new ReportCommand("ORDER_ACKED", orderNo, "X" + orderNo, SEQ.getAndIncrement(), null, null, null,
                null, null, null, "REALTIME");
    }

    private ReportCommand reject(String orderNo) {
        return new ReportCommand("ORDER_REJECTED", orderNo, null, SEQ.getAndIncrement(), null, null, null, null,
                null, null, "REALTIME");
    }

    private ReportCommand cancelAck(String orderNo) {
        return new ReportCommand("CANCEL_ACKED", orderNo, "X" + orderNo, SEQ.getAndIncrement(), null, null, null,
                null, null, null, "REALTIME");
    }

    private ReportCommand exec(String orderNo, String executionId, long execSeq, long quantity, String price) {
        return new ReportCommand("EXECUTION", orderNo, null, SEQ.getAndIncrement(), executionId, execSeq,
                quantity, new BigDecimal(price), null, LocalDateTime.now(), "REALTIME");
    }

    private String statusOf(String orderNo) {
        return jdbc.queryForObject("SELECT status FROM t_order WHERE order_no=?", String.class, orderNo);
    }

    private Long filledQuantity(String orderNo) {
        return jdbc.queryForObject("SELECT filled_quantity FROM t_order WHERE order_no=?", Long.class, orderNo);
    }
}
