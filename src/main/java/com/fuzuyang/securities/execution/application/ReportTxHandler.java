package com.fuzuyang.securities.execution.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuzuyang.securities.common.error.DiffType;
import com.fuzuyang.securities.common.exception.RetryableDispatchException;
import com.fuzuyang.securities.common.metrics.AppMetrics;
import com.fuzuyang.securities.common.util.DedupeKeys;
import com.fuzuyang.securities.execution.application.model.ReportCommand;
import com.fuzuyang.securities.execution.application.model.ReportResult;
import com.fuzuyang.securities.execution.domain.ExecutionReport;
import com.fuzuyang.securities.execution.infrastructure.entity.ReportEventDO;
import com.fuzuyang.securities.execution.infrastructure.mapper.ReportEventMapper;
import com.fuzuyang.securities.order.application.CancelApplicationService;
import com.fuzuyang.securities.order.application.DiffService;
import com.fuzuyang.securities.order.application.OrderReportService;
import com.fuzuyang.securities.order.application.model.ReportOutcome;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回报分发的事务体（唯一入口的业务事务）。
 *
 * <p>横切三事：① 插 {@code t_report_event}（{@code applied=1}，{@code uk_dedupe_key} 冲突就地 catch →
 * {@code DUPLICATE_REPORT}）；② 同事务分发（handler 的 {@code REQUIRED} 加入本事务）；③ 被拒时统一
 * "改 {@code applied=0} + 落差异"。
 *
 * <p>catch **不可穿出代理**（否则 rollback-only → {@code UnexpectedRollbackException}）。
 */
@Service
public class ReportTxHandler {

    private static final String EXECUTION = "EXECUTION";
    private static final String ORDER_ACKED = "ORDER_ACKED";
    private static final String ORDER_REJECTED = "ORDER_REJECTED";
    private static final String CANCEL_ACKED = "CANCEL_ACKED";

    private final ReportEventMapper reportEventMapper;
    private final OrderReportService orderReportService;
    private final CancelApplicationService cancelApplicationService;
    private final ExecutionApplicationService executionApplicationService;
    private final DiffService diffService;
    private final AppMetrics metrics;
    private final ObjectMapper objectMapper;

    public ReportTxHandler(ReportEventMapper reportEventMapper, OrderReportService orderReportService,
            CancelApplicationService cancelApplicationService,
            ExecutionApplicationService executionApplicationService, DiffService diffService, AppMetrics metrics,
            ObjectMapper objectMapper) {
        this.reportEventMapper = reportEventMapper;
        this.orderReportService = orderReportService;
        this.cancelApplicationService = cancelApplicationService;
        this.executionApplicationService = executionApplicationService;
        this.diffService = diffService;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ReportResult handle(ReportCommand cmd) {
        String dedupeKey = DedupeKeys.report(cmd.reportType(), cmd.orderNo(), cmd.exchangeSeq(),
                cmd.executionId());
        try {
            reportEventMapper.insert(ReportEventDO.builder()
                    .reportType(cmd.reportType())
                    .orderNo(cmd.orderNo())
                    .dedupeKey(dedupeKey)
                    .exchangeOrderId(cmd.exchangeOrderId())
                    .exchangeSeq(cmd.exchangeSeq())
                    .executionId(cmd.executionId())
                    .execSeq(cmd.execSeq())
                    .source(cmd.source())
                    .applied(Boolean.TRUE)
                    .payload(toJson(cmd))
                    .build());
        } catch (DuplicateKeyException e) {
            if (EXECUTION.equals(cmd.reportType())) {
                metrics.execDup();
            }
            metrics.reportRejected(DiffType.DUPLICATE_REPORT);
            return ReportResult.rejected(cmd.orderNo(), DiffType.DUPLICATE_REPORT);
        }

        ReportOutcome outcome = route(cmd);
        if (outcome instanceof ReportOutcome.Applied applied) {
            metrics.reportApplied();
            return ReportResult.applied(applied.orderNo(), applied.status(), applied.filledQuantity());
        }
        if (outcome instanceof ReportOutcome.Rejected rejected) {
            reject(dedupeKey, rejected.diffType(), rejected.orderNo(), fragment(cmd, rejected.fragment()),
                    rejected.detail());
            return ReportResult.rejected(rejected.orderNo(), rejected.diffType());
        }
        if (outcome instanceof ReportOutcome.NotFound notFound) {
            reject(dedupeKey, DiffType.ORDER_NOT_FOUND, notFound.orderNo(), fragment(cmd, notFound.fragment()),
                    "订单不存在");
            return ReportResult.rejected(notFound.orderNo(), DiffType.ORDER_NOT_FOUND);
        }
        if (outcome instanceof ReportOutcome.Retryable retryable) {
            throw new RetryableDispatchException(retryable.orderNo(), retryable.detail());
        }
        throw new IllegalStateException("未覆盖的分发结果: " + outcome);
    }

    private ReportOutcome route(ReportCommand cmd) {
        return switch (cmd.reportType()) {
            case ORDER_ACKED -> orderReportService.handleAcked(cmd.orderNo(), cmd.exchangeOrderId(),
                    String.valueOf(cmd.exchangeSeq()));
            case ORDER_REJECTED -> orderReportService.handleRejected(cmd.orderNo(), String.valueOf(cmd.exchangeSeq()));
            case CANCEL_ACKED -> cancelApplicationService.confirm(cmd.orderNo(), String.valueOf(cmd.exchangeSeq()));
            case EXECUTION -> executionApplicationService.apply(new ExecutionReport(
                    cmd.orderNo(), cmd.executionId(), cmd.exchangeSeq(), cmd.execSeq(), cmd.execQuantity(),
                    cmd.execPrice(), cmd.execAmount(), cmd.execTime(), cmd.source()));
            default -> throw new RetryableDispatchException(cmd.orderNo(), "未知 reportType: " + cmd.reportType());
        };
    }

    private void reject(String dedupeKey, DiffType diffType, String orderNo, String fragment, String detail) {
        reportEventMapper.markAppliedZero(dedupeKey);
        diffService.record(diffType, orderNo, fragment, null, detail);
        metrics.reportRejected(diffType);
        switch (diffType) {
            case ILLEGAL_TRANSITION -> metrics.illegalTransition();
            case EXEC_OVERFILL -> metrics.execOverfill();
            case EXEC_AFTER_TERMINAL -> metrics.execAfterTerminal();
            default -> { }
        }
    }

    /** 差异键片段：成交类取 executionId；订单/撤单确认类取 exchangeSeq；ORDER_NOT_FOUND 至少含 orderNo。 */
    private String fragment(ReportCommand cmd, String fallback) {
        if (EXECUTION.equals(cmd.reportType())) {
            return cmd.executionId();
        }
        return cmd.exchangeSeq() == null ? fallback : String.valueOf(cmd.exchangeSeq());
    }

    private String toJson(ReportCommand cmd) {
        try {
            return objectMapper.writeValueAsString(Map.of("report", cmd.toString()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("回报 payload 序列化失败", e);
        }
    }
}
