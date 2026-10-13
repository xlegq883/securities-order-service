package com.fuzuyang.securities.common.exception;

import com.fuzuyang.securities.common.api.ErrorResponse;
import com.fuzuyang.securities.common.error.ErrorCode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常映射。
 *
 * <p>HTTP 出口的稳定契约：
 * <ul>
 *   <li>{@link BusinessException} → 按 {@link ErrorCode#getStatus()} 返回 {@code {code, message, fields?}}；</li>
 *   <li>报文/绑定失败 → 400 {@code INVALID_PARAM}（零留痕）；</li>
 *   <li>其它异常 → 500（事件发件箱唯一键冲突等程序缺陷走此出口，不进差异体系）。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex) {
        ErrorCode code = ex.getErrorCode();
        return ResponseEntity.status(code.getStatus())
                .body(new ErrorResponse(code.name(), ex.getMessage(), ex.getFields()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBeanValidation(MethodArgumentNotValidException ex) {
        List<String> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField())
                .distinct()
                .toList();
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(ErrorCode.INVALID_PARAM.name(), ErrorCode.INVALID_PARAM.getDefaultMessage(), fields));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleUnreadable(Exception ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ErrorCode.INVALID_PARAM.name(), ErrorCode.INVALID_PARAM.getDefaultMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        log.error("未预期的服务端异常", ex);
        return ResponseEntity.status(500).body(ErrorResponse.of("INTERNAL_ERROR", ex.getMessage()));
    }
}
