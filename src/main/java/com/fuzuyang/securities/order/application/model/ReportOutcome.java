package com.fuzuyang.securities.order.application.model;

import com.fuzuyang.securities.common.error.DiffType;

/**
 * 回报分发结果（应用层模型）。
 *
 * <p>被拒/未找到携带差异片段与明细，由分发器统一"改 applied=0 + 落差异"。
 * {@code Retryable} 表示矩阵未覆盖，交由外层重试（≤3 次），耗尽 → 503。
 */
public sealed interface ReportOutcome {

    record Applied(String orderNo, String status, long filledQuantity) implements ReportOutcome {
    }

    record Rejected(String orderNo, DiffType diffType, String fragment, String detail) implements ReportOutcome {
    }

    record NotFound(String orderNo, String fragment) implements ReportOutcome {
    }

    record Retryable(String orderNo, String detail) implements ReportOutcome {
    }
}
