package com.fuzuyang.securities.order.application.model;

import com.fuzuyang.securities.common.error.DiffType;
import com.fuzuyang.securities.order.domain.ZeroRowClassifier;

/**
 * 分类结论 → 分发结果 的唯一映射（差异片段由调用方给出）。
 */
public final class ReportOutcomes {

    private ReportOutcomes() {
    }

    public static ReportOutcome from(ZeroRowClassifier.Verdict verdict, String orderNo, String fragment,
            String detail) {
        return switch (verdict) {
            case APPLY -> new ReportOutcome.Retryable(orderNo, detail);
            case ILLEGAL_TRANSITION -> rejected(orderNo, DiffType.ILLEGAL_TRANSITION, fragment, detail);
            case AFTER_TERMINAL -> rejected(orderNo, DiffType.AFTER_TERMINAL, fragment, detail);
            case CANCEL_AFTER_FILL -> rejected(orderNo, DiffType.CANCEL_AFTER_FILL, fragment, detail);
            case EXEC_AFTER_TERMINAL -> rejected(orderNo, DiffType.EXEC_AFTER_TERMINAL, fragment, detail);
            case EXEC_OVERFILL -> rejected(orderNo, DiffType.EXEC_OVERFILL, fragment, detail);
            case DATA_CONFLICT -> rejected(orderNo, DiffType.DATA_CONFLICT, fragment, detail);
            case ORDER_NOT_FOUND -> new ReportOutcome.NotFound(orderNo, fragment);
        };
    }

    private static ReportOutcome rejected(String orderNo, DiffType diffType, String fragment, String detail) {
        return new ReportOutcome.Rejected(orderNo, diffType, fragment, detail);
    }
}
