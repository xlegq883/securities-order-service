package com.fuzuyang.securities.order.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuzuyang.securities.common.config.UpstreamProperties;
import com.fuzuyang.securities.common.error.ErrorCode;
import com.fuzuyang.securities.common.exception.BusinessException;
import com.fuzuyang.securities.common.metrics.AppMetrics;
import com.fuzuyang.securities.order.application.model.CancelView;
import com.fuzuyang.securities.order.application.model.ReportOutcome;
import com.fuzuyang.securities.order.application.model.ReportOutcomes;
import com.fuzuyang.securities.order.domain.ChangeType;
import com.fuzuyang.securities.order.domain.OrderStatus;
import com.fuzuyang.securities.order.domain.ZeroRowClassifier;
import com.fuzuyang.securities.order.infrastructure.entity.OrderDO;
import com.fuzuyang.securities.order.infrastructure.entity.UpstreamCommandDO;
import com.fuzuyang.securities.order.infrastructure.mapper.OrderMapper;
import com.fuzuyang.securities.order.infrastructure.mapper.UpstreamCommandMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 撤单请求与撤单确认。
 *
 * <p>请求：仅 {@code ACKNOWLEDGED}/{@code PARTIALLY_FILLED} 且未请求过可写 {@code cancel_requested_at}；
 * 0 行三分支：空 → 404；不可撤 → 400；已请求过 → 200 幂等成功（**不重新入队**）。
 *
 * <p>确认：{@code confirm} 由回报分发器调用，推进 {@code CANCELED}（守卫 {@code filled_quantity < quantity}）。
 */
@Service
public class CancelApplicationService {

    private static final String COMMAND_CANCEL = "CANCEL";

    private final OrderMapper orderMapper;
    private final UpstreamCommandMapper commandMapper;
    private final EventWriter eventWriter;
    private final UpstreamProperties upstreamProperties;
    private final AppMetrics metrics;
    private final ObjectMapper objectMapper;

    public CancelApplicationService(OrderMapper orderMapper, UpstreamCommandMapper commandMapper,
            EventWriter eventWriter, UpstreamProperties upstreamProperties, AppMetrics metrics,
            ObjectMapper objectMapper) {
        this.orderMapper = orderMapper;
        this.commandMapper = commandMapper;
        this.eventWriter = eventWriter;
        this.upstreamProperties = upstreamProperties;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CancelView request(String orderNo) {
        int rows = orderMapper.requestCancel(orderNo);
        if (rows == 1) {
            OrderDO snapshot = orderMapper.selectByOrderNo(orderNo);
            eventWriter.writeOrderChanged(snapshot, ChangeType.CANCEL_REQUESTED, Instant.now());
            insertCancelCommand(snapshot);
            metrics.cancelRequest();
            return new CancelView(orderNo, snapshot.getStatus(), snapshot.getCancelRequestedAt(), "PENDING");
        }

        OrderDO current = orderMapper.selectByOrderNo(orderNo);
        if (current == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (current.getCancelRequestedAt() != null) {
            // 已请求过：幂等成功，不重新入队
            return new CancelView(orderNo, current.getStatus(), current.getCancelRequestedAt(),
                    commandStatus(orderNo));
        }
        metrics.cancelNotCancelable();
        throw new BusinessException(ErrorCode.ORDER_NOT_CANCELABLE,
                "仅 ACKNOWLEDGED / PARTIALLY_FILLED 可撤，当前状态: " + current.getStatus());
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public ReportOutcome confirm(String orderNo, String fragment) {
        int rows = orderMapper.confirmCancel(orderNo);
        if (rows == 1) {
            OrderDO snapshot = orderMapper.selectByOrderNo(orderNo);
            eventWriter.writeOrderChanged(snapshot, ChangeType.CANCELED, Instant.now());
            metrics.stateTransition();
            return new ReportOutcome.Applied(orderNo, snapshot.getStatus(), snapshot.getFilledQuantity());
        }

        OrderDO current = orderMapper.selectByOrderNo(orderNo);
        ZeroRowClassifier.Verdict verdict = ZeroRowClassifier.classify(ZeroRowClassifier.Kind.CANCEL_ACKED,
                current == null ? null : OrderStatus.valueOf(current.getStatus()),
                current != null && current.getFilledQuantity().equals(current.getQuantity()), false);
        return ReportOutcomes.from(verdict, orderNo, fragment,
                "到达态=" + (current == null ? "NONE" : current.getStatus()));
    }

    private void insertCancelCommand(OrderDO snapshot) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderNo", snapshot.getOrderNo());
        payload.put("exchangeOrderId", snapshot.getExchangeOrderId());
        commandMapper.insert(UpstreamCommandDO.builder()
                .orderNo(snapshot.getOrderNo())
                .commandType(COMMAND_CANCEL)
                .transport(upstreamProperties.getCommandTransport())
                .payload(toJson(payload))
                .status("PENDING")
                .attemptCount(0)
                .build());
    }

    private String commandStatus(String orderNo) {
        UpstreamCommandDO command = commandMapper.selectByOrderNoAndType(orderNo, COMMAND_CANCEL);
        return command == null ? null : command.getStatus();
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("指令 payload 序列化失败", e);
        }
    }
}
