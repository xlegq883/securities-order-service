package com.fuzuyang.securities.execution;

import com.fuzuyang.securities.execution.application.GapRepairTask;
import com.fuzuyang.securities.execution.application.GapScanTask;
import com.fuzuyang.securities.execution.application.ReportDispatcher;
import com.fuzuyang.securities.execution.application.model.ReportCommand;
import com.fuzuyang.securities.execution.domain.ExecutionReport;
import com.fuzuyang.securities.execution.infrastructure.mapper.ExecutionMapper;
import com.fuzuyang.securities.order.application.OrderCreationService;
import com.fuzuyang.securities.order.application.model.CreateOrderCommand;
import com.fuzuyang.securities.support.AbstractMySqlIT;
import com.fuzuyang.securities.support.ItSupportConfig;
import com.fuzuyang.securities.support.StubExecutionReportSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
 * T2 缺口检测与修复（真库）。补拉端口开启（Stub）。
 */
@SpringBootTest(properties = {
        "app.upstream.execution-source.enabled=true",
        "app.upstream.command-transport=RECORDING"
})
@ActiveProfiles("test")
@Import(ItSupportConfig.class)
class GapIT extends AbstractMySqlIT {

    private static final AtomicLong SEQ = new AtomicLong(9000);

    @Autowired
    private OrderCreationService creationService;
    @Autowired
    private ReportDispatcher dispatcher;
    @Autowired
    private GapScanTask scanTask;
    @Autowired
    private GapRepairTask repairTask;
    @Autowired
    private StubExecutionReportSource source;
    @Autowired
    private ExecutionMapper executionMapper;
    @Autowired
    private MeterRegistry registry;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void internalGapDetected() {
        String orderNo = create(1000);
        dispatch(ack(orderNo));
        for (long seq : new long[] {1, 2, 4, 5, 6}) {
            dispatch(exec(orderNo, "G" + seq, seq, 10));
        }

        scanTask.runOnce();
        assertThat(diffCount(orderNo)).isEqualTo(1);
        assertThat(diffKeyExists(orderNo, 3)).isTrue();
    }

    @Test
    void rejectedSeqCountsAsReceivedNoPhantomGap() {
        String orderNo = create(100);
        dispatch(ack(orderNo));
        dispatch(exec(orderNo, "R1", 1, 100)); // 到量 → FILLED
        dispatch(exec(orderNo, "R2", 2, 1)); // 终态后被拒，仅进 report_event

        scanTask.runOnce();
        assertThat(diffCount(orderNo)).isZero();
    }

    @Test
    void startingGapOnlyByTime() {
        String orderNo = create(1000);
        dispatch(ack(orderNo));
        dispatch(exec(orderNo, "S5", 5, 10)); // 起始缺失（无 1..4）

        jdbc.update("UPDATE t_report_event SET created_at = NOW(3) - INTERVAL 60 SECOND WHERE order_no=?", orderNo);
        scanTask.runOnce();
        assertThat(diffKeyExists(orderNo, 1)).isTrue();
    }

    @Test
    void scanScopeHonorsWindow() {
        String recent = create(1000);
        dispatch(ack(recent));
        dispatch(exec(recent, "W1", 1, 10));

        String stale = create(1000);
        dispatch(ack(stale));
        dispatch(exec(stale, "W2", 1, 10));
        jdbc.update("UPDATE t_execution SET created_at = NOW(3) - INTERVAL 1 HOUR WHERE order_no=?", stale);

        List<String> driving = executionMapper.selectDrivingOrders(60, 100);
        assertThat(driving).contains(recent).doesNotContain(stale);
    }

    @Test
    void repairResolvesGap() {
        String orderNo = create(1000);
        dispatch(ack(orderNo));
        for (long seq : new long[] {1, 2, 4, 5, 6}) {
            dispatch(exec(orderNo, "P" + seq, seq, 10));
        }
        scanTask.runOnce();
        assertThat(diffKeyExists(orderNo, 3)).isTrue();

        source.clear();
        source.script(orderNo, List.of(new ExecutionReport(orderNo, "P3", null, 3, 10, new BigDecimal("10"), null,
                LocalDateTime.now(), "PULL")));
        repairTask.runOnce();

        assertThat(diffStatus(orderNo, 3)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_execution WHERE order_no=? AND exec_seq=3",
                Integer.class, orderNo)).isEqualTo(1);
    }

    @Test
    void repairExhaustedMarksFailed() {
        String orderNo = create(1000);
        dispatch(ack(orderNo));
        for (long seq : new long[] {1, 2, 4, 5, 6}) {
            dispatch(exec(orderNo, "X" + seq, seq, 10));
        }
        scanTask.runOnce();

        source.clear();
        double before = registry.counter("exec.gap.repair.exhausted").count();
        for (int i = 0; i < 3; i++) {
            repairTask.runOnce();
            jdbc.update("UPDATE t_reconcile_diff SET next_attempt_at = NOW(3) - INTERVAL 10 SECOND "
                    + "WHERE diff_type='EXEC_GAP' AND order_no=?", orderNo);
        }

        assertThat(diffStatus(orderNo, 3)).isEqualTo("FAILED");
        assertThat(registry.counter("exec.gap.repair.exhausted").count()).isGreaterThan(before);
    }

    @Test
    void gapQueriesUseIndexes() {
        assertThat(explainKey("SELECT DISTINCT order_no FROM t_execution "
                + "WHERE created_at >= NOW(3) - INTERVAL 60 SECOND LIMIT 100")).isEqualTo("idx_exec_created");

        jdbc.queryForList("EXPLAIN SELECT exec_seq FROM t_execution WHERE order_no='x' "
                + "UNION SELECT exec_seq FROM t_report_event WHERE order_no='x' AND report_type='EXECUTION' "
                + "AND exec_seq IS NOT NULL").forEach(row -> System.out.println("EXPLAIN " + row));
        // t_report_event 分支应命中 idx_report_exec（由 ReportEventMapper.selectReceivedSeqs 使用）
        List<Map<String, Object>> plan = jdbc.queryForList("EXPLAIN SELECT exec_seq FROM t_report_event "
                + "WHERE order_no='x' AND report_type='EXECUTION' AND exec_seq IS NOT NULL");
        assertThat(plan).anyMatch(row -> "idx_report_exec".equals(row.get("key")));
    }

    // ---------- helpers ----------

    private String explainKey(String sql) {
        List<Map<String, Object>> plan = jdbc.queryForList("EXPLAIN " + sql);
        return plan.isEmpty() ? null : (String) plan.get(0).get("key");
    }

    private String create(long quantity) {
        return creationService.create(new CreateOrderCommand("ACC-GAP", UUID.randomUUID().toString(), "600000",
                "BUY", "LIMIT", new BigDecimal("10"), quantity)).orderNo();
    }

    private void dispatch(ReportCommand cmd) {
        dispatcher.dispatch(cmd);
    }

    private ReportCommand ack(String orderNo) {
        return new ReportCommand("ORDER_ACKED", orderNo, "X" + orderNo, SEQ.getAndIncrement(), null, null, null,
                null, null, null, "REALTIME");
    }

    private ReportCommand exec(String orderNo, String executionId, long execSeq, long quantity) {
        return new ReportCommand("EXECUTION", orderNo, null, SEQ.getAndIncrement(), executionId, execSeq,
                quantity, new BigDecimal("10"), null, LocalDateTime.now(), "REALTIME");
    }

    private int diffCount(String orderNo) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_reconcile_diff WHERE diff_type='EXEC_GAP' AND order_no=?",
                Integer.class, orderNo);
    }

    private boolean diffKeyExists(String orderNo, long seq) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_reconcile_diff WHERE dedupe_key=?", Integer.class,
                "EXEC_GAP:" + orderNo + ":" + seq) > 0;
    }

    private String diffStatus(String orderNo, long seq) {
        return jdbc.queryForObject("SELECT status FROM t_reconcile_diff WHERE dedupe_key=?", String.class,
                "EXEC_GAP:" + orderNo + ":" + seq);
    }
}
