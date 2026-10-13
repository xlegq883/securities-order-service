package com.fuzuyang.securities.execution.api;

import com.fuzuyang.securities.common.config.OrderCommandRetry;
import com.fuzuyang.securities.common.error.ErrorCode;
import com.fuzuyang.securities.common.exception.BusinessException;
import com.fuzuyang.securities.common.metrics.AppMetrics;
import com.fuzuyang.securities.execution.api.dto.ReportRequest;
import com.fuzuyang.securities.execution.api.dto.ReportResponse;
import com.fuzuyang.securities.execution.application.ReportDispatcher;
import com.fuzuyang.securities.execution.application.model.ReportCommand;
import com.fuzuyang.securities.execution.application.model.ReportResult;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回报统一入口（HTTP 适配）。
 *
 * <p>只做报文校验（400 早退、零留痕、不插报告表/不落差异/不计 applied/rejected）与错误映射；
 * 业务与横切都交给 {@link ReportDispatcher}。{@code source} 为纯留痕字段，缺省 {@code REALTIME}。
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private static final Set<String> REPORT_TYPES =
            Set.of("ORDER_ACKED", "ORDER_REJECTED", "CANCEL_ACKED", "EXECUTION");
    private static final String EXECUTION = "EXECUTION";
    private static final String DEFAULT_SOURCE = "REALTIME";

    private final ReportDispatcher dispatcher;
    private final OrderCommandRetry retry;
    private final AppMetrics metrics;

    public ReportController(ReportDispatcher dispatcher, OrderCommandRetry retry, AppMetrics metrics) {
        this.dispatcher = dispatcher;
        this.retry = retry;
        this.metrics = metrics;
    }

    @PostMapping
    public ReportResponse report(@RequestBody ReportRequest request) {
        ReportCommand command = validate(request);
        ReportResult result = retry.execute(() -> dispatcher.dispatch(command));
        return new ReportResponse(result.orderNo(), result.applied(),
                result.diffType() == null ? null : result.diffType().name(), result.filledQuantity(),
                result.status());
    }

    private ReportCommand validate(ReportRequest request) {
        if (request.reportType() == null || !REPORT_TYPES.contains(request.reportType())) {
            throw invalid("reportType", "unknown_report_type", "reportType 非法");
        }
        if (request.orderNo() == null || request.orderNo().isBlank()) {
            throw invalid("orderNo", "missing_field", "orderNo 缺失");
        }
        String type = request.reportType();
        if (EXECUTION.equals(type)) {
            requireText(request.executionId(), "executionId");
            if (request.execSeq() == null) {
                throw invalid("execSeq", "missing_field", "execSeq 缺失");
            }
            if (request.execQuantity() == null || request.execQuantity() <= 0) {
                throw invalid("execQuantity", "quantity_non_positive", "execQuantity 必须为正");
            }
            if (request.execTime() == null) {
                throw invalid("execTime", "missing_field", "execTime 缺失");
            }
            if (request.execPrice() == null && request.execAmount() == null) {
                throw invalid("execAmount", "price_missing", "execPrice 与 execAmount 不能同时缺失");
            }
        } else if (request.exchangeSeq() == null) {
            throw invalid("exchangeSeq", "missing_field", "exchangeSeq 缺失");
        }

        String source = (request.source() == null || request.source().isBlank()) ? DEFAULT_SOURCE : request.source();
        return new ReportCommand(type, request.orderNo(), request.exchangeOrderId(), request.exchangeSeq(),
                request.executionId(), request.execSeq(), request.execQuantity(), request.execPrice(),
                request.execAmount(), request.execTime(), source);
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field, "missing_field", field + " 缺失");
        }
    }

    private BusinessException invalid(String field, String reason, String message) {
        metrics.reportInvalid(reason);
        return new BusinessException(ErrorCode.INVALID_PARAM, message, List.of(field));
    }
}
