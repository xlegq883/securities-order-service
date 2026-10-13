package com.fuzuyang.securities.schema;

import com.fuzuyang.securities.support.AbstractMySqlIT;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2 真库 schema 断言（Testcontainers mysql:8.0）。
 *
 * <p>SQL 只有一份单一来源 {@code sql/V1__init.sql}，compose 与测试引用同一路径；
 * 不用 {@code withInitScript}（按 classpath 查找，根目录 sql/ 不在测试 classpath 上），
 * 改为显式挂载到 {@code /docker-entrypoint-initdb.d/}。
 *
 * <p>禁止 H2（D22）。
 */
@ActiveProfiles("test")
class SchemaIT extends AbstractMySqlIT {

    private static final Set<String> EXPECTED_TABLES = Set.of(
            "t_order", "t_execution", "t_report_event", "t_upstream_command", "t_event_outbox",
            "t_order_query", "t_execution_query", "t_reconcile_diff",
            "t_eod_batch", "t_eod_batch_step", "t_eod_settlement", "t_distributed_lock");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void databaseIsSecurities() {
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("securities");
    }

    @Test
    void hasExactlyTwelveTables() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class);
        assertThat(tables).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
    }

    @Test
    void hasUniqueKeys() {
        Map<String, Set<String>> unique = uniqueIndexesByTable();

        assertThat(unique.get("t_order")).containsExactlyInAnyOrder("PRIMARY", "uk_order_no", "uk_account_client");
        assertThat(unique.get("t_execution")).containsExactlyInAnyOrder("PRIMARY", "uk_execution_id");
        assertThat(unique.get("t_report_event")).containsExactlyInAnyOrder("PRIMARY", "uk_dedupe_key");
        assertThat(unique.get("t_upstream_command")).containsExactlyInAnyOrder("PRIMARY", "uk_order_command");
        assertThat(unique.get("t_event_outbox")).containsExactlyInAnyOrder(
                "PRIMARY", "uk_dedupe_key", "uk_aggregate_topic_version");
        assertThat(unique.get("t_order_query")).containsExactly("PRIMARY");
        assertThat(unique.get("t_execution_query")).containsExactly("PRIMARY");
        assertThat(unique.get("t_reconcile_diff")).containsExactlyInAnyOrder("PRIMARY", "uk_dedupe_key");
        assertThat(unique.get("t_eod_batch")).containsExactlyInAnyOrder("PRIMARY", "uk_business_batch");
        assertThat(unique.get("t_eod_batch_step")).containsExactlyInAnyOrder("PRIMARY", "uk_batch_step");
        assertThat(unique.get("t_eod_settlement")).containsExactlyInAnyOrder("PRIMARY", "uk_business_account_security");
        assertThat(unique.get("t_distributed_lock")).containsExactly("PRIMARY");
    }

    @Test
    void hasKeySecondaryIndexes() {
        Map<String, Set<String>> indexes = indexesByTable();

        assertThat(indexes.get("t_order")).contains("idx_account_created_id", "idx_status");
        assertThat(indexes.get("t_execution")).contains("idx_order_seq", "idx_exec_created");
        assertThat(indexes.get("t_report_event")).contains("idx_report_exec");
        assertThat(indexes.get("t_upstream_command")).contains("idx_status_next");
        assertThat(indexes.get("t_event_outbox")).contains("idx_status_next");
        assertThat(indexes.get("t_reconcile_diff")).contains("idx_diff_type_status");
        assertThat(indexes.get("t_order_query")).contains("idx_account_created");
        assertThat(indexes.get("t_execution_query")).contains("idx_order_seq");
    }

    @Test
    void deliveryTablesHaveClaimColumns() {
        assertThat(columnsOf("t_event_outbox")).contains("owner", "claimed_at");
        assertThat(columnsOf("t_upstream_command")).contains("owner", "claimed_at");
        assertThat(columnsOf("t_report_event")).contains("order_no", "exec_seq");
        assertThat(columnsOf("t_reconcile_diff")).contains("attempt_count", "next_attempt_at");
    }

    @Test
    void distributedLockIsSeeded() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_distributed_lock WHERE lock_name = 'read_model_rebuild'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    private Set<String> columnsOf(String table) {
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ?",
                String.class, table));
    }

    private Map<String, Set<String>> indexesByTable() {
        return queryIndexes("SELECT table_name, index_name FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() GROUP BY table_name, index_name");
    }

    private Map<String, Set<String>> uniqueIndexesByTable() {
        return queryIndexes("SELECT table_name, index_name FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND non_unique = 0 GROUP BY table_name, index_name");
    }

    private Map<String, Set<String>> queryIndexes(String sql) {
        Map<String, Set<String>> result = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            result.computeIfAbsent(rs.getString("table_name"), k -> new LinkedHashSet<>())
                    .add(rs.getString("index_name"));
        });
        return result;
    }
}
