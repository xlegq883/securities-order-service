package com.fuzuyang.securities.order.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuzuyang.securities.common.config.EventTopics;
import com.fuzuyang.securities.common.util.DedupeKeys;
import com.fuzuyang.securities.execution.infrastructure.entity.ExecutionDO;
import com.fuzuyang.securities.order.domain.ChangeType;
import com.fuzuyang.securities.order.infrastructure.entity.EventOutboxDO;
import com.fuzuyang.securities.order.infrastructure.entity.OrderDO;
import com.fuzuyang.securities.order.infrastructure.mapper.EventOutboxMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事件写入。
 *
 * <p>每事务每 topic 至多一条：order 侧每个 {@code (order_no, event_version)} **恰好一条**
 * {@code ORDER_STATE_CHANGED}（性质放 {@code change_type}）；execution 侧每个版本至多一条
 * {@code EXECUTION_APPLIED}。同一事务可产出"同版本、跨 topic"两条事件。
 */
@Service
public class EventWriter {

    private static final String ORDER_STATE_CHANGED = "ORDER_STATE_CHANGED";
    private static final String EXECUTION_APPLIED = "EXECUTION_APPLIED";

    private final EventOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;

    public EventWriter(EventOutboxMapper outboxMapper, ObjectMapper objectMapper) {
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void writeOrderChanged(OrderDO snapshot, ChangeType changeType, Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order_no", snapshot.getOrderNo());
        payload.put("status", snapshot.getStatus());
        payload.put("filled_quantity", snapshot.getFilledQuantity());
        payload.put("filled_amount", snapshot.getFilledAmount());
        payload.put("cancel_requested_at", snapshot.getCancelRequestedAt());
        payload.put("event_version", snapshot.getEventVersion());
        payload.put("occurred_at", occurredAt.toString());
        payload.put("change_type", changeType.name());

        String dedupeKey = DedupeKeys.orderChanged(snapshot.getOrderNo(), snapshot.getEventVersion());
        outboxMapper.insert(EventOutboxDO.builder()
                .aggregateType("ORDER")
                .aggregateId(snapshot.getOrderNo())
                .eventVersion(snapshot.getEventVersion())
                .eventType(ORDER_STATE_CHANGED)
                .topic(EventTopics.ORDER_EVENTS)
                .messageKey(snapshot.getOrderNo())
                .payload(toJson(payload))
                .dedupeKey(dedupeKey)
                .status("PENDING")
                .retryCount(0)
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void writeExecutionApplied(OrderDO snapshot, ExecutionDO execution, Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order_no", snapshot.getOrderNo());
        payload.put("status", snapshot.getStatus());
        payload.put("filled_quantity", snapshot.getFilledQuantity());
        payload.put("filled_amount", snapshot.getFilledAmount());
        payload.put("cancel_requested_at", snapshot.getCancelRequestedAt());
        payload.put("event_version", snapshot.getEventVersion());
        payload.put("occurred_at", occurredAt.toString());
        payload.put("execution_id", execution.getExecutionId());
        payload.put("exec_seq", execution.getExecSeq());
        payload.put("exec_quantity", execution.getExecQuantity());
        payload.put("exec_price", execution.getExecPrice());
        payload.put("exec_amount", execution.getExecAmount());
        payload.put("exec_time", execution.getExecTime());

        String dedupeKey = DedupeKeys.executionApplied(snapshot.getOrderNo(), snapshot.getEventVersion(),
                execution.getExecutionId());
        outboxMapper.insert(EventOutboxDO.builder()
                .aggregateType("ORDER")
                .aggregateId(snapshot.getOrderNo())
                .eventVersion(snapshot.getEventVersion())
                .eventType(EXECUTION_APPLIED)
                .topic(EventTopics.EXECUTION_EVENTS)
                .messageKey(snapshot.getOrderNo())
                .payload(toJson(payload))
                .dedupeKey(dedupeKey)
                .status("PENDING")
                .retryCount(0)
                .build());
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件 payload 序列化失败", e);
        }
    }
}
