package com.fuzuyang.securities.order.application;

import com.fuzuyang.securities.common.metrics.AppMetrics;
import com.fuzuyang.securities.order.application.model.ReportOutcome;
import com.fuzuyang.securities.order.application.model.ReportOutcomes;
import com.fuzuyang.securities.order.domain.ChangeType;
import com.fuzuyang.securities.order.domain.OrderStatus;
import com.fuzuyang.securities.order.domain.ZeroRowClassifier;
import com.fuzuyang.securities.order.infrastructure.entity.OrderDO;
import com.fuzuyang.securities.order.infrastructure.mapper.OrderMapper;
import java.time.Instant;
import java.util.function.IntSupplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 已报 / 拒单回报处理（{@code SUBMITTED} 守卫）。
 *
 * <p>0 行时按 {@link ZeroRowClassifier} 分类矩阵产出差异；只以 CAS 受影响行数为准。
 */
@Service
public class OrderReportService {

    private final OrderMapper orderMapper;
    private final EventWriter eventWriter;
    private final AppMetrics metrics;

    public OrderReportService(OrderMapper orderMapper, EventWriter eventWriter, AppMetrics metrics) {
        this.orderMapper = orderMapper;
        this.eventWriter = eventWriter;
        this.metrics = metrics;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public ReportOutcome handleAcked(String orderNo, String exchangeOrderId, String fragment) {
        return handle(ZeroRowClassifier.Kind.ORDER_ACKED, orderNo, fragment, ChangeType.ACKED,
                () -> orderMapper.markAcked(orderNo, exchangeOrderId));
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public ReportOutcome handleRejected(String orderNo, String fragment) {
        return handle(ZeroRowClassifier.Kind.ORDER_REJECTED, orderNo, fragment, ChangeType.REJECTED,
                () -> orderMapper.markRejected(orderNo));
    }

    private ReportOutcome handle(ZeroRowClassifier.Kind kind, String orderNo, String fragment,
            ChangeType changeType, IntSupplier cas) {
        if (cas.getAsInt() == 1) {
            OrderDO snapshot = orderMapper.selectByOrderNo(orderNo);
            eventWriter.writeOrderChanged(snapshot, changeType, Instant.now());
            metrics.stateTransition();
            return new ReportOutcome.Applied(orderNo, snapshot.getStatus(), snapshot.getFilledQuantity());
        }
        OrderDO current = orderMapper.selectByOrderNo(orderNo);
        ZeroRowClassifier.Verdict verdict = ZeroRowClassifier.classify(kind,
                current == null ? null : OrderStatus.valueOf(current.getStatus()),
                current != null && current.getFilledQuantity().equals(current.getQuantity()), false);
        return ReportOutcomes.from(verdict, orderNo, fragment,
                "到达态=" + (current == null ? "NONE" : current.getStatus()));
    }
}
