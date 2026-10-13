package com.fuzuyang.securities.order;

import com.fuzuyang.securities.common.config.OutboxProperties;
import com.fuzuyang.securities.common.config.UpstreamProperties;
import com.fuzuyang.securities.common.error.DiffType;
import com.fuzuyang.securities.order.application.EventOutboxRelay;
import com.fuzuyang.securities.order.application.OrderCreationService;
import com.fuzuyang.securities.order.application.UpstreamCommandRelay;
import com.fuzuyang.securities.order.application.UpstreamCommandStore;
import com.fuzuyang.securities.order.application.model.CreateOrderCommand;
import com.fuzuyang.securities.order.infrastructure.entity.UpstreamCommandDO;
import com.fuzuyang.securities.support.AbstractMySqlIT;
import com.fuzuyang.securities.support.ItSupportConfig;
import com.fuzuyang.securities.support.RecordingEventPublisher;
import com.fuzuyang.securities.support.RecordingUpstreamCommandSender;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2 投递 relay：三阶段、至少一次、租约回收、owner 守卫、耗尽终态、参数硬约束。
 */
@SpringBootTest(properties = "app.upstream.command-transport=RECORDING")
@ActiveProfiles("test")
@Import(ItSupportConfig.class)
class RelayIT extends AbstractMySqlIT {

    @Autowired
    private OrderCreationService creationService;
    @Autowired
    private EventOutboxRelay eventRelay;
    @Autowired
    private UpstreamCommandRelay commandRelay;
    @Autowired
    private UpstreamCommandStore commandStore;
    @Autowired
    private RecordingEventPublisher publisher;
    @Autowired
    private RecordingUpstreamCommandSender sender;
    @Autowired
    private MeterRegistry registry;
    @Autowired
    private JdbcTemplate jdbc;

    /** relay 用例共享同一个库：每个用例前清空投递表，保证"批次内只有本用例的行"。
     *  顺带清空差异表，避免跨用例的 dedupe 干扰。 */
    @BeforeEach
    void isolate() {
        jdbc.update("DELETE FROM t_event_outbox");
        jdbc.update("DELETE FROM t_upstream_command");
        jdbc.update("DELETE FROM t_reconcile_diff");
        sender.reset();
        publisher.reset();
        sender.setOnSend(command -> { });
    }

    @Test
    void commandSendHappensOutsideClaimTransaction() {
        String orderNo = create();
        AtomicBoolean sawInProgress = new AtomicBoolean(false);
        sender.setOnSend(command -> {
            String status = jdbc.queryForObject("SELECT status FROM t_upstream_command WHERE order_no=?",
                    String.class, command.getOrderNo());
            sawInProgress.set("IN_PROGRESS".equals(status));
        });

        sender.reset();
        commandRelay.runOnce();

        assertThat(sender.sent()).hasSize(1);
        assertThat(commandStatus(orderNo)).isEqualTo("SENT");
        assertThat(sawInProgress).as("发送发生在认领事务之外，另一连接可读到 IN_PROGRESS").isTrue();
        sender.setOnSend(command -> { });
    }

    @Test
    void commandFailureRetriesThenReachesFailedTerminalState() {
        String orderNo = create();
        sender.reset();
        sender.failNext(3);

        commandRelay.runOnce();
        assertThat(commandStatus(orderNo)).isEqualTo("PENDING");
        assertThat(attempts(orderNo)).isEqualTo(1);

        forceCommandRetryNow(orderNo);
        commandRelay.runOnce();
        assertThat(attempts(orderNo)).isEqualTo(2);

        forceCommandRetryNow(orderNo);
        commandRelay.runOnce();
        assertThat(commandStatus(orderNo)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_reconcile_diff WHERE diff_type=? AND order_no=?",
                Integer.class, DiffType.UPSTREAM_SUBMIT_FAILED.name(), orderNo)).isEqualTo(1);
    }

    @Test
    void ownerGuardBlocksStaleHolder() {
        create();
        List<UpstreamCommandDO> claimed = commandStore.claim(100, 600);
        UpstreamCommandDO row = claimed.stream().filter(c -> "SUBMIT".equals(c.getCommandType())).findFirst()
                .orElseThrow();

        assertThat(commandStore.markSent(row.getId(), "other-owner")).isZero();
        assertThat(commandStore.markSent(row.getId(), row.getOwner())).isEqualTo(1);
    }

    @Test
    void leaseReclaimIncrementsMetricAndResends() {
        String orderNo = create();
        jdbc.update("UPDATE t_upstream_command SET status='IN_PROGRESS', owner='dead', "
                + "claimed_at = NOW(3) - INTERVAL 2 HOUR WHERE order_no=?", orderNo);

        double before = registry.counter("relay.lease.reclaim", "channel", "command").count();
        sender.reset();
        commandRelay.runOnce();

        assertThat(sender.sent()).hasSize(1);
        assertThat(registry.counter("relay.lease.reclaim", "channel", "command").count())
                .isEqualTo(before + 1);
    }

    @Test
    void eventRelaySendsAndMarksSent() {
        create();
        double before = registry.counter("event.outbox.sent").count();
        publisher.reset();

        eventRelay.runOnce();

        assertThat(publisher.published()).hasSize(1);
        assertThat(publisher.published().get(0).getEventType()).isEqualTo("ORDER_STATE_CHANGED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_event_outbox WHERE status='SENT'", Integer.class))
                .isGreaterThan(0);
        assertThat(registry.counter("event.outbox.sent").count()).isGreaterThan(before);
    }

    @Test
    void eventRelayFailureBacksOffToPending() {
        String orderNo = create();
        publisher.failNext(1);
        eventRelay.runOnce();

        assertThat(jdbc.queryForObject(
                "SELECT retry_count FROM t_event_outbox WHERE aggregate_id=? ORDER BY id DESC LIMIT 1",
                Integer.class, orderNo)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM t_event_outbox WHERE aggregate_id=? ORDER BY id DESC LIMIT 1",
                String.class, orderNo)).isEqualTo("PENDING");
    }

    @Test
    void leaseMustExceedBatchTimesTimeout() {
        assertThat(new OutboxProperties().isLeaseSufficient()).isTrue();
        OutboxProperties violated = new OutboxProperties();
        violated.setBatchSize(10_000);
        assertThat(violated.isLeaseSufficient()).isFalse();

        assertThat(new UpstreamProperties().isLeaseSufficient()).isTrue();
        UpstreamProperties violated2 = new UpstreamProperties();
        violated2.setClaimLeaseSeconds(1);
        assertThat(violated2.isLeaseSufficient()).isFalse();
    }

    // ---------- helpers ----------

    private String create() {
        return creationService.create(new CreateOrderCommand("ACC-RL", UUID.randomUUID().toString(), "600000",
                "BUY", "LIMIT", new BigDecimal("10"), 100L)).orderNo();
    }

    private String commandStatus(String orderNo) {
        return jdbc.queryForObject("SELECT status FROM t_upstream_command WHERE order_no=? AND command_type='SUBMIT'",
                String.class, orderNo);
    }

    private Integer attempts(String orderNo) {
        return jdbc.queryForObject(
                "SELECT attempt_count FROM t_upstream_command WHERE order_no=? AND command_type='SUBMIT'",
                Integer.class, orderNo);
    }

    private void forceCommandRetryNow(String orderNo) {
        jdbc.update("UPDATE t_upstream_command SET next_retry_at = NOW(3) - INTERVAL 10 SECOND WHERE order_no=?",
                orderNo);
    }
}
