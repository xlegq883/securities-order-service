package com.fuzuyang.securities.execution.application;

/**
 * 竞态穿透 {@code uk_execution_id}（同 executionId 落到不同订单）时抛出。
 *
 * <p>必须**穿出**业务事务边界（使主事务回滚、订单量额不变），由外层用
 * {@code REQUIRES_NEW} 落 {@code DATA_CONFLICT} 差异并返回 200。
 */
public class ExecutionDataConflictException extends RuntimeException {

    private final String orderNo;
    private final String executionId;

    public ExecutionDataConflictException(String orderNo, String executionId) {
        super("executionId 已被其他订单占用: " + executionId);
        this.orderNo = orderNo;
        this.executionId = executionId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public String getExecutionId() {
        return executionId;
    }
}
