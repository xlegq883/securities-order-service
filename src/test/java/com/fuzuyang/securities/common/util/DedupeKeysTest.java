package com.fuzuyang.securities.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两套去重键规则。
 */
class DedupeKeysTest {

    @Test
    void executionReportKeyUsesExecutionId() {
        assertThat(DedupeKeys.report("EXECUTION", "O1", 7L, "E1")).isEqualTo("EXECUTION:O1:E1");
    }

    @Test
    void otherReportKeysUseExchangeSeq() {
        assertThat(DedupeKeys.report("ORDER_ACKED", "O1", 7L, null)).isEqualTo("ORDER_ACKED:O1:7");
        assertThat(DedupeKeys.report("ORDER_REJECTED", "O1", 8L, null)).isEqualTo("ORDER_REJECTED:O1:8");
        assertThat(DedupeKeys.report("CANCEL_ACKED", "O1", 9L, null)).isEqualTo("CANCEL_ACKED:O1:9");
    }

    @Test
    void eventKeys() {
        assertThat(DedupeKeys.orderChanged("O1", 3L)).isEqualTo("O1:3:ORDER_STATE_CHANGED");
        assertThat(DedupeKeys.executionApplied("O1", 3L, "E1")).isEqualTo("O1:3:EXECUTION_APPLIED:E1");
    }
}
