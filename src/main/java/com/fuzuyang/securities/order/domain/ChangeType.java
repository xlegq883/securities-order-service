package com.fuzuyang.securities.order.domain;

/**
 * 事件变化性质。
 *
 * <p>只服务日志/指标/排查，**不参与读侧排序与去重**（读侧唯一口径是 {@code (aggregate_id, event_version)}）。
 *
 * <p>{@code SUBMITTED} 为下单创建时发出的初始事件（决策变更 4）：否则"已受理但尚未收到回报"的单
 * 在读模型里没有初始行。
 */
public enum ChangeType {

    SUBMITTED,
    ACKED,
    REJECTED,
    CANCEL_REQUESTED,
    CANCELED,
    EXPIRED,
    /** 部分成交自环：只累加量额、状态不变。 */
    FILL_UPDATED,
    /** 累计到量。 */
    FILLED;

    /** 由"是否累计到量"推导成交类 order 事件的 change_type。 */
    public static ChangeType forFill(boolean fullyFilled) {
        return fullyFilled ? FILLED : FILL_UPDATED;
    }
}
