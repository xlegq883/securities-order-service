package com.fuzuyang.securities.common.exception;

import com.fuzuyang.securities.common.error.ErrorCode;
import java.util.List;

/**
 * 业务异常：携带稳定 {@link ErrorCode}，由 {@link GlobalExceptionHandler} 映射为 HTTP 响应。
 *
 * <p>注意：**业务拒绝不使用本异常**（业务拒绝走 200 + applied:false + diffType）。
 * 本异常只用于参数非法、订单不存在、不可撤、幂等冲突、重试耗尽等需要非 200 出口的情形。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient List<String> fields;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage(), null);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BusinessException(ErrorCode errorCode, String message, List<String> fields) {
        super(message);
        this.errorCode = errorCode;
        this.fields = fields;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public List<String> getFields() {
        return fields;
    }
}
