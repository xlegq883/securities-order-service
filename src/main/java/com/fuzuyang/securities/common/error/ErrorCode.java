package com.fuzuyang.securities.common.error;

import org.springframework.http.HttpStatus;

/**
 * 错误码（对外可辨识的稳定标识）。
 *
 * <p>响应边界：报文/字段校验失败 → 400 {@link #INVALID_PARAM} 且零留痕；
 * 业务/数据拒绝走 200 + diffType（不走错误码）；真并发冲突重试耗尽 → 503 {@link #RETRY_EXHAUSTED}。
 */
public enum ErrorCode {

    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "订单不存在"),
    ORDER_NOT_CANCELABLE(HttpStatus.BAD_REQUEST, "订单当前不可撤"),
    IDEMPOTENT_REPLAY_CONFLICT(HttpStatus.CONFLICT, "幂等重放参数不一致"),
    DUPLICATE_REPORT(HttpStatus.OK, "回报重复投递"),
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "非法游标"),
    INVALID_PARAM(HttpStatus.BAD_REQUEST, "参数非法"),
    REBUILD_IN_PROGRESS(HttpStatus.CONFLICT, "读模型重建进行中"),
    RETRY_EXHAUSTED(HttpStatus.SERVICE_UNAVAILABLE, "重试耗尽");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
