package com.fuzuyang.securities.order.domain;

import com.fuzuyang.securities.order.domain.ZeroRowClassifier.Kind;
import com.fuzuyang.securities.order.domain.ZeroRowClassifier.Verdict;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0 行分类矩阵逐格断言（§3.4）。
 */
class ZeroRowClassifierTest {

    @Test
    void notFound() {
        assertThat(ZeroRowClassifier.classify(Kind.ORDER_ACKED, null, false, false)).isEqualTo(Verdict.ORDER_NOT_FOUND);
        assertThat(ZeroRowClassifier.classify(Kind.ORDER_REJECTED, null, false, false)).isEqualTo(Verdict.ORDER_NOT_FOUND);
        assertThat(ZeroRowClassifier.classify(Kind.CANCEL_ACKED, null, false, false)).isEqualTo(Verdict.ORDER_NOT_FOUND);
        assertThat(ZeroRowClassifier.classify(Kind.EXECUTION, null, false, false)).isEqualTo(Verdict.ORDER_NOT_FOUND);
        // execId 被他单占用 → DATA_CONFLICT
        assertThat(ZeroRowClassifier.classify(Kind.EXECUTION, null, false, true)).isEqualTo(Verdict.DATA_CONFLICT);
    }

    @Test
    void submitted() {
        assertThat(ZeroRowClassifier.classify(Kind.ORDER_ACKED, OrderStatus.SUBMITTED, false, false))
                .isEqualTo(Verdict.APPLY);
        assertThat(ZeroRowClassifier.classify(Kind.ORDER_REJECTED, OrderStatus.SUBMITTED, false, false))
                .isEqualTo(Verdict.APPLY);
        assertThat(ZeroRowClassifier.classify(Kind.CANCEL_ACKED, OrderStatus.SUBMITTED, false, false))
                .isEqualTo(Verdict.ILLEGAL_TRANSITION);
        assertThat(ZeroRowClassifier.classify(Kind.EXECUTION, OrderStatus.SUBMITTED, false, false))
                .isEqualTo(Verdict.ILLEGAL_TRANSITION);
    }

    @Test
    void nonTerminal() {
        for (OrderStatus arrival : new OrderStatus[] {OrderStatus.ACKNOWLEDGED, OrderStatus.PARTIALLY_FILLED}) {
            assertThat(ZeroRowClassifier.classify(Kind.ORDER_ACKED, arrival, false, false))
                    .isEqualTo(Verdict.ILLEGAL_TRANSITION);
            assertThat(ZeroRowClassifier.classify(Kind.ORDER_REJECTED, arrival, false, false))
                    .isEqualTo(Verdict.ILLEGAL_TRANSITION);
            // 未全额成交 → 应用；非终态却 filled == quantity → ILLEGAL_TRANSITION
            assertThat(ZeroRowClassifier.classify(Kind.CANCEL_ACKED, arrival, false, false))
                    .isEqualTo(Verdict.APPLY);
            assertThat(ZeroRowClassifier.classify(Kind.CANCEL_ACKED, arrival, true, false))
                    .isEqualTo(Verdict.ILLEGAL_TRANSITION);
            // 非终态且 CAS① 0 行 ⇒ 容量守卫失败
            assertThat(ZeroRowClassifier.classify(Kind.EXECUTION, arrival, false, false))
                    .isEqualTo(Verdict.EXEC_OVERFILL);
        }
    }

    @Test
    void filled() {
        assertThat(ZeroRowClassifier.classify(Kind.ORDER_ACKED, OrderStatus.FILLED, false, false))
                .isEqualTo(Verdict.AFTER_TERMINAL);
        assertThat(ZeroRowClassifier.classify(Kind.ORDER_REJECTED, OrderStatus.FILLED, false, false))
                .isEqualTo(Verdict.AFTER_TERMINAL);
        assertThat(ZeroRowClassifier.classify(Kind.CANCEL_ACKED, OrderStatus.FILLED, false, false))
                .isEqualTo(Verdict.CANCEL_AFTER_FILL);
        assertThat(ZeroRowClassifier.classify(Kind.EXECUTION, OrderStatus.FILLED, false, false))
                .isEqualTo(Verdict.EXEC_AFTER_TERMINAL);
    }

    @Test
    void otherTerminalStates() {
        for (OrderStatus arrival : new OrderStatus[] {
                OrderStatus.CANCELED, OrderStatus.EXPIRED, OrderStatus.REJECTED}) {
            assertThat(ZeroRowClassifier.classify(Kind.ORDER_ACKED, arrival, false, false))
                    .isEqualTo(Verdict.AFTER_TERMINAL);
            assertThat(ZeroRowClassifier.classify(Kind.ORDER_REJECTED, arrival, false, false))
                    .isEqualTo(Verdict.AFTER_TERMINAL);
            assertThat(ZeroRowClassifier.classify(Kind.CANCEL_ACKED, arrival, false, false))
                    .isEqualTo(Verdict.AFTER_TERMINAL);
            assertThat(ZeroRowClassifier.classify(Kind.EXECUTION, arrival, false, false))
                    .isEqualTo(Verdict.EXEC_AFTER_TERMINAL);
        }
    }
}
