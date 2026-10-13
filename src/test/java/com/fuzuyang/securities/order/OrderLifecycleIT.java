package com.fuzuyang.securities.order;

import com.fuzuyang.securities.common.config.OrderCommandRetry;
import com.fuzuyang.securities.common.error.DiffType;
import com.fuzuyang.securities.common.error.ErrorCode;
import com.fuzuyang.securities.common.exception.BusinessException;
import com.fuzuyang.securities.execution.api.ReportController;
import com.fuzuyang.securities.execution.api.dto.ReportRequest;
import com.fuzuyang.securities.execution.application.ReportDispatcher;
import com.fuzuyang.securities.execution.application.model.ReportCommand;
import com.fuzuyang.securities.execution.application.model.ReportResult;
import com.fuzuyang.securities.order.application.CancelApplicationService;
import com.fuzuyang.securities.order.application.OrderCreationService;
import com.fuzuyang.securities.order.application.model.CancelView;
import com.fuzuyang.securities.order.application.model.CreateOrderCommand;
import com.fuzuyang.securities.order.application.model.OrderView;
import com.fuzuyang.securities.support.AbstractMySqlIT;
import com.fuzuyang.securities.support.ItSupportConfig;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T2 订单生命周期、不变量、幂等与撤单（真库）。
 */
@ActiveProfiles("test")
@Import(ItSupportConfig.class)
class OrderLifecycleIT extends AbstractMySqlIT {

    private static final AtomicLong SEQ = new AtomicLong(1);

    @Autowired
    private OrderCreationService creationService;
    @Autowired
    private CancelApplicationService cancelService;
    @Autowired
    private ReportDispatcher dispatcher;
    @Autowired
    private ReportController reportController;
    @Autowired
    private OrderCommandRetry retry;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void partialThenFilledPreservesInvariantsAndEvents() {
        String orderNo = create("ACC-1", "LIMIT", new BigDecimal("10"), 100L, true);

        assertThat(statusOf(orderNo)).isEqualTo("SUBMITTED");
        dispatch(ack(orderNo));
        dispatch(exec(orderNo, "E1", 1, 40, "10"));
        dispatch(exec(orderNo, "E2", 2, 60, "10"));

        OrderRow row = orderRow(orderNo);
        assertThat(row.status()).isEqualTo("FILLED");
        assertThat(row.filledQuantity()).isEqualTo(100L);
        assertThat(row.quantity()).isEqualTo(100L);
        assertThat(row.eventVersion()).isEqualTo(4L); // v1 SUBMITTED, v2 ACKED, v3 FILL_UPDATED, v4 FILLED

        assertThat(row.filledAmount()).isEqualByComparingTo(sum("exec_amount", orderNo));
        assertThat(row.filledQuantity().longValue()).isEqualTo(sumLong("exec_quantity", orderNo));
        assertThat(count("t_execution", orderNo)).isEqualTo(2L);

        assertThat(eventVersions(orderNo, "ORDER_STATE_CHANGED")).containsExactly(1L, 2L, 3L, 4L);
        assertThat(eventVersions(orderNo, "EXECUTION_APPLIED")).containsExactly(3L, 4L);
        assertThat(countOutbox(orderNo, 3L, "ORDER_STATE_CHANGED")).isEqualTo(1);
        assertThat(maxEventVersion(orderNo)).isEqualTo(row.eventVersion());
    }

    @Test
    void marketOrderKeepsPriceAndAmountNull() {
        String orderNo = create("ACC-2", "MARKET", null, 100L, true);
        assertThat(orderRow(orderNo).orderPrice()).isNull();
        assertThat(orderRow(orderNo).amount()).isNull();

        dispatch(ack(orderNo));
        dispatch(exec(orderNo, "EM1", 1, 100, "10"));

        OrderRow filled = orderRow(orderNo);
        assertThat(filled.status()).isEqualTo("FILLED");
        assertThat(filled.amount()).isNull();
        assertThat(filled.filledAmount()).isEqualByComparingTo("1000.0000");
        assertThat(filled.filledQuantity()).isEqualTo(filled.quantity());
    }

    @Test
    void invalidReportIsRejectedWithZeroTrace() {
        long beforeReports = jdbc.queryForObject("SELECT COUNT(*) FROM t_report_event", Long.class);
        long beforeDiffs = jdbc.queryForObject("SELECT COUNT(*) FROM t_reconcile_diff", Long.class);

        assertThatThrownBy(() -> reportController.report(new ReportRequest(
                "NOT_A_TYPE", "O-unknown", null, 1L, null, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PARAM);
        assertThatThrownBy(() -> reportController.report(new ReportRequest(
                "ORDER_ACKED", "O-unknown", "X", null, null, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> reportController.report(new ReportRequest(
                "EXECUTION", "O-unknown", null, null, "E", 1L, 10L, null, null, LocalDateTime.now(), null)))
                .isInstanceOf(BusinessException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_report_event", Long.class)).isEqualTo(beforeReports);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_reconcile_diff", Long.class)).isEqualTo(beforeDiffs);
    }

    @Test
    void limitOrderRequiresPositivePrice() {
        assertThatThrownBy(() -> creationService.create(new CreateOrderCommand(
                "ACC-3", UUID.randomUUID().toString(), "600000", "BUY", "LIMIT", null, 100L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PARAM);
        assertThatThrownBy(() -> creationService.create(new CreateOrderCommand(
                "ACC-3", UUID.randomUUID().toString(), "600000", "BUY", "MARKET", new BigDecimal("9"), 100L)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void idempotentReplaySameAndConflict() {
        String client = UUID.randomUUID().toString();
        CreateOrderCommand cmd = new CreateOrderCommand(
                "ACC-4", client, "600000", "BUY", "LIMIT", new BigDecimal("10"), 100L);
        OrderView first = creationService.create(cmd);
        OrderView replay = creationService.create(cmd);
        assertThat(replay.orderNo()).isEqualTo(first.orderNo());

        assertThatThrownBy(() -> creationService.create(new CreateOrderCommand(
                "ACC-4", client, "600000", "BUY", "LIMIT", new BigDecimal("11"), 100L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.IDEMPOTENT_REPLAY_CONFLICT);
    }

    @Test
    void concurrentCreateSameKeyOnlyOneOrder() throws Exception {
        String client = UUID.randomUUID().toString();
        List<Object> outcomes = runConcurrently(8, () -> retry.execute(() -> creationService.create(
                new CreateOrderCommand("ACC-5", client, "600000", "BUY", "LIMIT", new BigDecimal("10"), 100L))
                .orderNo()));
        assertThat(outcomes).allMatch(o -> o instanceof String);
        assertThat(orderCount("ACC-5", client)).isEqualTo(1L);
    }

    @Test
    void concurrentCreateConflictingParamsNoUnexpectedRollback() throws Exception {
        String client = UUID.randomUUID().toString();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            BigDecimal price = i % 2 == 0 ? new BigDecimal("10") : new BigDecimal("11");
            tasks.add(() -> retry.execute(() -> creationService.create(
                    new CreateOrderCommand("ACC-6", client, "600000", "BUY", "LIMIT", price, 100L)).orderNo()));
        }
        List<Object> outcomes = runTasks(tasks);
        assertThat(outcomes).allMatch(o -> o instanceof String
                || (o instanceof BusinessException be && be.getErrorCode() == ErrorCode.IDEMPOTENT_REPLAY_CONFLICT));
        assertThat(orderCount("ACC-6", client)).isEqualTo(1L);
    }

    @Test
    void overfillRejectedButCommitted() {
        String orderNo = create("ACC-7", "LIMIT", new BigDecimal("10"), 100L, true);
        dispatch(ack(orderNo));
        ReportResult result = dispatch(exec(orderNo, "EO", 1, 150, "10"));

        assertThat(result.applied()).isFalse();
        assertThat(result.diffType()).isEqualTo(DiffType.EXEC_OVERFILL);
        assertThat(orderRow(orderNo).filledQuantity()).isZero();
        assertThat(count("t_execution", orderNo)).isZero();
        assertThat(diffCount(DiffType.EXEC_OVERFILL, orderNo)).isEqualTo(1);
        assertThat(appliedFlag("EXECUTION:" + orderNo + ":EO")).isZero();
    }

    @Test
    void duplicateExecutionReport() {
        String orderNo = create("ACC-8", "LIMIT", new BigDecimal("10"), 100L, true);
        dispatch(ack(orderNo));
        dispatch(exec(orderNo, "ED", 1, 10, "10"));
        ReportResult duplicate = dispatch(exec(orderNo, "ED", 1, 10, "10"));

        assertThat(duplicate.applied()).isFalse();
        assertThat(duplicate.diffType()).isEqualTo(DiffType.DUPLICATE_REPORT);
        assertThat(count("t_execution", orderNo)).isEqualTo(1L);
        assertThat(orderRow(orderNo).filledQuantity()).isEqualTo(10L);
    }

    @Test
    void cancelBranches() {
        String submitted = create("ACC-9", "LIMIT", new BigDecimal("10"), 100L, true);
        assertThatThrownBy(() -> cancelService.request(submitted))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_NOT_CANCELABLE);
        assertThatThrownBy(() -> cancelService.request("no-such-order"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_NOT_FOUND);

        String orderNo = create("ACC-10", "LIMIT", new BigDecimal("10"), 100L, true);
        dispatch(ack(orderNo));
        CancelView first = cancelService.request(orderNo);
        assertThat(first.cancelRequestedAt()).isNotNull();
        CancelView repeat = cancelService.request(orderNo);
        assertThat(repeat.cancelRequestedAt()).isEqualTo(first.cancelRequestedAt());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_upstream_command WHERE order_no=? AND command_type='CANCEL'", Long.class,
                orderNo)).isEqualTo(1L);
    }

    // ---------- helpers ----------

    private record OrderRow(String status, Long quantity, Long filledQuantity, BigDecimal filledAmount,
                            BigDecimal amount, BigDecimal orderPrice, Long eventVersion) {
    }

    private String create(String accountId, String orderType, BigDecimal price, long quantity, boolean viaRetry) {
        CreateOrderCommand cmd = new CreateOrderCommand(accountId, UUID.randomUUID().toString(), "600000",
                "BUY", orderType, price, quantity);
        OrderView view = viaRetry ? retry.execute(() -> creationService.create(cmd)) : creationService.create(cmd);
        return view.orderNo();
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

    private String statusOf(String orderNo) {
        return jdbc.queryForObject("SELECT status FROM t_order WHERE order_no=?", String.class, orderNo);
    }

    private OrderRow orderRow(String orderNo) {
        return jdbc.queryForObject("SELECT status, quantity, filled_quantity, filled_amount, amount, order_price, "
                + "event_version FROM t_order WHERE order_no=?", (rs, i) -> new OrderRow(
                        rs.getString("status"), rs.getLong("quantity"), rs.getLong("filled_quantity"),
                        rs.getBigDecimal("filled_amount"), rs.getBigDecimal("amount"), rs.getBigDecimal("order_price"),
                        rs.getLong("event_version")), orderNo);
    }

    private BigDecimal sum(String column, String orderNo) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(" + column + "),0) FROM t_execution WHERE order_no=?",
                BigDecimal.class, orderNo);
    }

    private long sumLong(String column, String orderNo) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(" + column + "),0) FROM t_execution WHERE order_no=?",
                Long.class, orderNo);
    }

    private long count(String table, String orderNo) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE order_no=?", Long.class, orderNo);
    }

    private long orderCount(String accountId, String clientOrderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_order WHERE account_id=? AND client_order_id=?",
                Long.class, accountId, clientOrderId);
    }

    private List<Long> eventVersions(String orderNo, String eventType) {
        return jdbc.queryForList("SELECT event_version FROM t_event_outbox WHERE aggregate_id=? AND event_type=? "
                + "ORDER BY event_version", Long.class, orderNo, eventType);
    }

    private int countOutbox(String orderNo, long version, String eventType) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_event_outbox WHERE aggregate_id=? AND event_version=? "
                + "AND event_type=?", Integer.class, orderNo, version, eventType);
    }

    private long maxEventVersion(String orderNo) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(event_version),0) FROM t_event_outbox WHERE aggregate_id=?",
                Long.class, orderNo);
    }

    private int diffCount(DiffType type, String orderNo) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_reconcile_diff WHERE diff_type=? AND order_no=?",
                Integer.class, type.name(), orderNo);
    }

    private int appliedFlag(String dedupeKey) {
        return jdbc.queryForObject("SELECT applied FROM t_report_event WHERE dedupe_key=?", Integer.class, dedupeKey);
    }

    private List<Object> runConcurrently(int threads, Callable<Object> task) throws Exception {
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(task);
        }
        return runTasks(tasks);
    }

    private List<Object> runTasks(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return task.call();
                } catch (RuntimeException e) {
                    return e;
                }
            }));
        }
        start.countDown();
        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> future : futures) {
            outcomes.add(future.get());
        }
        pool.shutdownNow();
        return outcomes;
    }
}
