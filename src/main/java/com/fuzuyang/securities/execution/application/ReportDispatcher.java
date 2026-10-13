package com.fuzuyang.securities.execution.application;

import com.fuzuyang.securities.common.error.DiffType;
import com.fuzuyang.securities.common.metrics.AppMetrics;
import com.fuzuyang.securities.execution.application.model.ReportCommand;
import com.fuzuyang.securities.execution.application.model.ReportResult;
import com.fuzuyang.securities.order.application.DiffService;
import org.springframework.stereotype.Service;

/**
 * 回报分发器（编排层，**不**单独开事务）。
 *
 * <p>承接 {@code uk_execution_id} 的竞态兜底：执行业务事务抛 {@link ExecutionDataConflictException} 时
 * 主事务已回滚（订单量额不变），此处用 {@code REQUIRES_NEW} 落 {@code DATA_CONFLICT} 差异并返回 200。
 *
 * <p>外层 {@code RetryTemplate}（{@code OrderCommandRetry}）包住本方法调用，实现跨 Bean 重试。
 */
@Service
public class ReportDispatcher {

    private final ReportTxHandler txHandler;
    private final DiffService diffService;
    private final AppMetrics metrics;

    public ReportDispatcher(ReportTxHandler txHandler, DiffService diffService, AppMetrics metrics) {
        this.txHandler = txHandler;
        this.diffService = diffService;
        this.metrics = metrics;
    }

    public ReportResult dispatch(ReportCommand cmd) {
        try {
            return txHandler.handle(cmd);
        } catch (ExecutionDataConflictException e) {
            diffService.recordRequiresNew(DiffType.DATA_CONFLICT, e.getOrderNo(), e.getExecutionId(), null,
                    e.getMessage());
            metrics.reportRejected(DiffType.DATA_CONFLICT);
            return ReportResult.rejected(e.getOrderNo(), DiffType.DATA_CONFLICT);
        }
    }
}
