package com.fuzuyang.securities.common.exception;

/**
 * 0 行分类矩阵兜底（行存在、非终态、非任何已命名情形）时抛出。
 *
 * <p>属"重试可解"的暂态不一致，交由外层 {@code RetryTemplate} 重试（≤3 次，耗尽 → 503）。
 * 因此本异常必须包含在重试白名单内。
 */
public class RetryableDispatchException extends RuntimeException {

    private final transient String orderNo;

    public RetryableDispatchException(String orderNo, String detail) {
        super(detail);
        this.orderNo = orderNo;
    }

    public String getOrderNo() {
        return orderNo;
    }
}
