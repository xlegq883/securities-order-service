package com.fuzuyang.securities.execution.application;

import com.fuzuyang.securities.common.config.ExecutionConflictProperties;
import com.fuzuyang.securities.common.error.DiffType;
import com.fuzuyang.securities.common.metrics.AppMetrics;
import com.fuzuyang.securities.execution.domain.ExecutionReport;
import com.fuzuyang.securities.execution.infrastructure.entity.ExecutionDO;
import com.fuzuyang.securities.execution.infrastructure.mapper.ExecutionMapper;
import com.fuzuyang.securities.order.application.EventWriter;
import com.fuzuyang.securities.order.application.model.ReportOutcome;
import com.fuzuyang.securities.order.application.model.ReportOutcomes;
import com.fuzuyang.securities.order.domain.ChangeType;
import com.fuzuyang.securities.order.domain.Money;
import com.fuzuyang.securities.order.domain.OrderStatus;
import com.fuzuyang.securities.order.domain.ZeroRowClassifier;
import com.fuzuyang.securities.order.infrastructure.entity.OrderDO;
import com.fuzuyang.securities.order.infrastructure.mapper.OrderMapper;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成交应用。
 *
 * <p>事务顺序：事前检查（可选）→ CAS① → 插明细（{@code uk_execution_id} 兜底）→ CAS② →
 * 读回快照（必须在 CAS② 之后）→ 两条同版本事件。分支判定只以 **CAS①** 受影响行数为准；
 * CAS② 的 0 行是正常自环。
 */
@Service
public class ExecutionApplicationService {

    private final OrderMapper orderMapper;
    private final ExecutionMapper executionMapper;
    private final EventWriter eventWriter;
    private final AppMetrics metrics;
    private final ExecutionConflictProperties conflictProperties;

    public ExecutionApplicationService(OrderMapper orderMapper, ExecutionMapper executionMapper,
            EventWriter eventWriter, AppMetrics metrics, ExecutionConflictProperties conflictProperties) {
        this.orderMapper = orderMapper;
        this.executionMapper = executionMapper;
        this.eventWriter = eventWriter;
        this.metrics = metrics;
        this.conflictProperties = conflictProperties;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public ReportOutcome apply(ExecutionReport report) {
        if (conflictProperties.isPrecheckEnabled()) {
            String owner = executionMapper.selectOrderNoByExecutionId(report.executionId());
            if (owner != null && !owner.equals(report.orderNo())) {
                return new ReportOutcome.Rejected(report.orderNo(), DiffType.DATA_CONFLICT, report.executionId(),
                        "executionId 已被订单占用: " + owner);
            }
        }

        // 金额单一真源：报文带金额则原样采用（仅一次 scale），否则按价 × 量精确算一次
        BigDecimal amount = report.execAmount() != null
                ? Money.scale(report.execAmount())
                : Money.multiply(report.execPrice(), report.execQuantity());

        int cas1 = orderMapper.applyExecution(report.orderNo(), report.execQuantity(), amount, report.execSeq());
        if (cas1 == 0) {
            return classifyZeroRow(report);
        }

        OrderDO afterCas1 = orderMapper.selectByOrderNo(report.orderNo());
        ExecutionDO execution = ExecutionDO.builder()
                .executionId(report.executionId())
                .orderNo(report.orderNo())
                .execSeq(report.execSeq())
                .execQuantity(report.execQuantity())
                .execPrice(report.execPrice() == null ? null : Money.scale(report.execPrice()))
                .execAmount(amount)
                .execTime(report.execTime())
                .businessDate(afterCas1.getBusinessDate())
                .build();
        try {
            executionMapper.insert(execution);
        } catch (DuplicateKeyException e) {
            // 竞态穿透 uk_execution_id：主事务必须回滚（订单量额不落地），外层 REQUIRES_NEW 落差异
            throw new ExecutionDataConflictException(report.orderNo(), report.executionId());
        }

        int cas2 = orderMapper.deriveStatus(report.orderNo());
        if (cas2 == 1) {
            metrics.stateTransition();
        } else {
            metrics.stateUnchanged();
        }

        OrderDO snapshot = orderMapper.selectByOrderNo(report.orderNo());
        Instant occurredAt = Instant.now();
        boolean filled = OrderStatus.FILLED.name().equals(snapshot.getStatus());
        eventWriter.writeOrderChanged(snapshot, ChangeType.forFill(filled), occurredAt);
        eventWriter.writeExecutionApplied(snapshot, execution, occurredAt);
        return new ReportOutcome.Applied(report.orderNo(), snapshot.getStatus(), snapshot.getFilledQuantity());
    }

    private ReportOutcome classifyZeroRow(ExecutionReport report) {
        OrderDO current = orderMapper.selectByOrderNo(report.orderNo());
        boolean ownedByOther = false;
        if (current == null) {
            String owner = executionMapper.selectOrderNoByExecutionId(report.executionId());
            ownedByOther = owner != null && !owner.equals(report.orderNo());
        }
        ZeroRowClassifier.Verdict verdict = ZeroRowClassifier.classify(ZeroRowClassifier.Kind.EXECUTION,
                current == null ? null : OrderStatus.valueOf(current.getStatus()),
                current != null && current.getFilledQuantity().equals(current.getQuantity()), ownedByOther);
        return ReportOutcomes.from(verdict, report.orderNo(), report.executionId(),
                "到达态=" + (current == null ? "NONE" : current.getStatus()));
    }
}
