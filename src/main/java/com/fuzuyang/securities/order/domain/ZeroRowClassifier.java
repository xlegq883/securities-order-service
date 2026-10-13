package com.fuzuyang.securities.order.domain;

/**
 * 0 行分类矩阵（纯函数）：行 = 回读到的到达态，列 = 回报类型。
 *
 * <p>只以 **CAS①** 受影响行数为准；0 行时按到达态分类，供处理器产出差异或重试。
 */
public final class ZeroRowClassifier {

    private ZeroRowClassifier() {
    }

    /** 回报类型（与 {@code reportType} 取值一致）。 */
    public enum Kind {
        ORDER_ACKED, ORDER_REJECTED, CANCEL_ACKED, EXECUTION
    }

    /** 分类结论。 */
    public enum Verdict {
        /** 守卫本应命中（并发窗口读到旧态），交外层重试。 */
        APPLY,
        ILLEGAL_TRANSITION,
        AFTER_TERMINAL,
        CANCEL_AFTER_FILL,
        EXEC_AFTER_TERMINAL,
        EXEC_OVERFILL,
        ORDER_NOT_FOUND,
        DATA_CONFLICT
    }

    /**
     * @param kind                     回报类型
     * @param arrival                  回读到的到达态；{@code null} 表示订单不存在
     * @param fullyFilled              到达态未终态时是否已 {@code filled_quantity == quantity}
     * @param executionIdOwnedByOther  该 {@code executionId} 是否已被其他订单占用
     */
    public static Verdict classify(Kind kind, OrderStatus arrival, boolean fullyFilled,
            boolean executionIdOwnedByOther) {
        if (arrival == null) {
            return kind == Kind.EXECUTION && executionIdOwnedByOther ? Verdict.DATA_CONFLICT : Verdict.ORDER_NOT_FOUND;
        }
        if (arrival.isTerminal()) {
            if (kind == Kind.EXECUTION) {
                return Verdict.EXEC_AFTER_TERMINAL;
            }
            if (kind == Kind.CANCEL_ACKED && arrival == OrderStatus.FILLED) {
                return Verdict.CANCEL_AFTER_FILL;
            }
            return Verdict.AFTER_TERMINAL;
        }
        return switch (arrival) {
            case SUBMITTED -> (kind == Kind.ORDER_ACKED || kind == Kind.ORDER_REJECTED)
                    ? Verdict.APPLY : Verdict.ILLEGAL_TRANSITION;
            case ACKNOWLEDGED, PARTIALLY_FILLED -> classifyNonTerminal(kind, fullyFilled);
            default -> Verdict.AFTER_TERMINAL;
        };
    }

    private static Verdict classifyNonTerminal(Kind kind, boolean fullyFilled) {
        if (kind == Kind.CANCEL_ACKED) {
            return fullyFilled ? Verdict.ILLEGAL_TRANSITION : Verdict.APPLY;
        }
        if (kind == Kind.EXECUTION) {
            // 非终态且 CAS① 0 行 ⇒ 容量守卫失败
            return Verdict.EXEC_OVERFILL;
        }
        return Verdict.ILLEGAL_TRANSITION;
    }
}
