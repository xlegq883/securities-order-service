package com.fuzuyang.securities.common.metrics;

import com.fuzuyang.securities.support.AbstractMySqlIT;
import com.fuzuyang.securities.support.ItSupportConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * T2 Gauge 成本专项：抓取只读内存，采样按周期；采样异常保留旧值且不中断。
 */
@ActiveProfiles("test")
@Import(ItSupportConfig.class)
class MetricsSamplerIT extends AbstractMySqlIT {

    @Autowired
    private MetricsSampler sampler;
    @Autowired
    private MeterRegistry registry;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void gaugeReadsDoNotTriggerSampling() {
        long before = sampler.sampledRounds();

        for (int i = 0; i < 100; i++) {
            registry.get(AppMetrics.GAUGE_EVENT_OUTBOX_PENDING).gauge().value();
            registry.get(AppMetrics.GAUGE_UPSTREAM_COMMAND_PENDING).gauge().value();
            registry.get(AppMetrics.GAUGE_EXEC_GAP_OPEN).gauge().value();
        }
        assertThat(sampler.sampledRounds()).isEqualTo(before);

        sampler.sample();
        assertThat(sampler.sampledRounds()).isEqualTo(before + 1);
        assertThat(registry.get(AppMetrics.GAUGE_EVENT_OUTBOX_PENDING).gauge().value())
                .isEqualTo((double) pending("t_event_outbox"));
        assertThat(registry.get(AppMetrics.GAUGE_EXEC_GAP_OPEN).gauge().value())
                .isEqualTo((double) gapOpen());
    }

    @Test
    void sampleFailureKeepsOldValueAndDoesNotThrow() {
        MetricsSampler broken = new MetricsSampler(
                new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/none", "u", "p")),
                new SimpleMeterRegistry());

        assertThatCode(broken::sample).doesNotThrowAnyException();
        assertThat(broken.sampledRounds()).isZero();
    }

    private long pending(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE status IN ('PENDING','IN_PROGRESS')",
                Long.class);
    }

    private long gapOpen() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_reconcile_diff WHERE diff_type='EXEC_GAP' AND status='OPEN'", Long.class);
    }
}
