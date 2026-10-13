package com.fuzuyang.securities.common.error;

/**
 * 对账差异类型。
 *
 * <p>业务/数据拒绝走 200 + {@code applied:false} + diffType，并通过差异表留痕。
 */
public enum DiffType {

    /** 状态机矩阵未覆盖的迁移。 */
    ILLEGAL_TRANSITION,
    /** 成交回报晚于终态。 */
    EXEC_AFTER_TERMINAL,
    /** 成交序号缺口。 */
    EXEC_GAP,
    /** 累加超量。 */
    EXEC_OVERFILL,
    /** 撤单确认晚于全额成交（交易所侧正常竞态）。 */
    CANCEL_AFTER_FILL,
    /** 写入列与明细汇总不一致（日终对账）。 */
    EXEC_SUM_MISMATCH,
    /** 读模型与写模型漂移（读模型对账）。 */
    READ_MODEL_DRIFT,
    /** 上游指令重试用尽。 */
    UPSTREAM_SUBMIT_FAILED,
    /** 回报重复投递。 */
    DUPLICATE_REPORT,
    /** 回报指向的订单不存在。 */
    ORDER_NOT_FOUND,
    /** 非成交回报晚于终态（统一兜底，区别于 EXEC_AFTER_TERMINAL 与 CANCEL_AFTER_FILL）。 */
    AFTER_TERMINAL,
    /** 同一 executionId 落到不同订单（上游脏数据）。 */
    DATA_CONFLICT
}
