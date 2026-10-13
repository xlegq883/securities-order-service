package com.fuzuyang.securities.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 统一错误响应体：{@code {code, message, fields?}}。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, List<String> fields) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null);
    }
}
