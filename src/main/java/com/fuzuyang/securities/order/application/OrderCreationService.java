package com.fuzuyang.securities.order.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuzuyang.securities.common.config.UpstreamProperties;
import com.fuzuyang.securities.common.error.ErrorCode;
import com.fuzuyang.securities.common.exception.BusinessException;
import com.fuzuyang.securities.common.metrics.AppMetrics;
import com.fuzuyang.securities.common.util.OrderNoGenerator;
import com.fuzuyang.securities.order.application.model.CreateOrderCommand;
import com.fuzuyang.securities.order.application.model.OrderView;
import com.fuzuyang.securities.order.domain.BusinessDateResolver;
import com.fuzuyang.securities.order.domain.ChangeType;
import com.fuzuyang.securities.order.domain.Money;
import com.fuzuyang.securities.order.domain.OrderStatus;
import com.fuzuyang.securities.order.infrastructure.entity.OrderDO;
import com.fuzuyang.securities.order.infrastructure.entity.UpstreamCommandDO;
import com.fuzuyang.securities.order.infrastructure.mapper.OrderMapper;
import com.fuzuyang.securities.order.infrastructure.mapper.UpstreamCommandMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 下单受理（幂等 + 订单号碰撞回查）。
 *
 * <p>幂等键 {@code uk(account_id, client_order_id)}：参数一致 → 200 返回原订单；不一致 → 409。
 *
 * <p>订单号唯一性由 {@code uk_order_no} 保证；生成器只降低碰撞率。{@code INSERT} 冲突时**回查区分两个唯一键**：
 * 命中 {@code uk_account_client} → 幂等分支；否则判定为订单号碰撞 → 重新生成，最多 3 次，耗尽 → 503。
 */
@Service
public class OrderCreationService {

    private static final int MAX_ORDER_NO_ATTEMPTS = 3;
    private static final Set<String> SIDES = Set.of("BUY", "SELL");
    private static final Set<String> ORDER_TYPES = Set.of("LIMIT", "MARKET");
    private static final String COMMAND_SUBMIT = "SUBMIT";

    private final OrderMapper orderMapper;
    private final UpstreamCommandMapper commandMapper;
    private final OrderNoGenerator orderNoGenerator;
    private final EventWriter eventWriter;
    private final UpstreamProperties upstreamProperties;
    private final AppMetrics metrics;
    private final ObjectMapper objectMapper;

    public OrderCreationService(OrderMapper orderMapper, UpstreamCommandMapper commandMapper,
            OrderNoGenerator orderNoGenerator, EventWriter eventWriter, UpstreamProperties upstreamProperties,
            AppMetrics metrics, ObjectMapper objectMapper) {
        this.orderMapper = orderMapper;
        this.commandMapper = commandMapper;
        this.orderNoGenerator = orderNoGenerator;
        this.eventWriter = eventWriter;
        this.upstreamProperties = upstreamProperties;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public OrderView create(CreateOrderCommand cmd) {
        validate(cmd);

        OrderDO existing = orderMapper.selectByAccountAndClientId(cmd.accountId(), cmd.clientOrderId());
        if (existing != null) {
            return replay(existing, cmd);
        }

        for (int attempt = 0; attempt < MAX_ORDER_NO_ATTEMPTS; attempt++) {
            String orderNo = orderNoGenerator.next();
            try {
                insertNew(orderNo, cmd);
            } catch (DuplicateKeyException e) {
                // 冲突后回查区分：命中账户幂等键 → 幂等分支；否则为订单号碰撞
                OrderDO raced = orderMapper.selectByAccountAndClientId(cmd.accountId(), cmd.clientOrderId());
                if (raced != null) {
                    return replay(raced, cmd);
                }
                metrics.orderNoCollision();
                continue;
            }
            OrderDO snapshot = orderMapper.selectByOrderNo(orderNo);
            eventWriter.writeOrderChanged(snapshot, ChangeType.SUBMITTED, Instant.now());
            insertCommand(orderNo, COMMAND_SUBMIT, submitPayload(orderNo, cmd));
            metrics.orderCreated();
            return OrderView.from(snapshot, "PENDING");
        }
        throw new BusinessException(ErrorCode.RETRY_EXHAUSTED, "订单号连续碰撞，重试耗尽");
    }

    private void insertNew(String orderNo, CreateOrderCommand cmd) {
        boolean limit = "LIMIT".equals(cmd.orderType());
        BigDecimal price = limit ? Money.scale(cmd.orderPrice()) : null;
        BigDecimal amount = limit ? Money.multiply(cmd.orderPrice(), cmd.quantity()) : null;

        orderMapper.insert(OrderDO.builder()
                .orderNo(orderNo)
                .accountId(cmd.accountId())
                .securityCode(cmd.securityCode())
                .side(cmd.side())
                .orderType(cmd.orderType())
                .orderPrice(price)
                .quantity(cmd.quantity())
                .amount(amount)
                .filledQuantity(0L)
                .filledAmount(Money.zero())
                .eventVersion(1L)
                .status(OrderStatus.SUBMITTED.name())
                .businessDate(BusinessDateResolver.today())
                .clientOrderId(cmd.clientOrderId())
                .build());
    }

    private void insertCommand(String orderNo, String commandType, Map<String, Object> payload) {
        commandMapper.insert(UpstreamCommandDO.builder()
                .orderNo(orderNo)
                .commandType(commandType)
                .transport(upstreamProperties.getCommandTransport())
                .payload(toJson(payload))
                .status("PENDING")
                .attemptCount(0)
                .build());
    }

    private Map<String, Object> submitPayload(String orderNo, CreateOrderCommand cmd) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderNo", orderNo);
        payload.put("accountId", cmd.accountId());
        // 上游按报文内幂等标识消化重复投递
        payload.put("clientOrderId", cmd.clientOrderId());
        payload.put("securityCode", cmd.securityCode());
        payload.put("side", cmd.side());
        payload.put("orderType", cmd.orderType());
        payload.put("orderPrice", cmd.orderPrice());
        payload.put("quantity", cmd.quantity());
        return payload;
    }

    private OrderView replay(OrderDO existing, CreateOrderCommand cmd) {
        if (!sameParameters(existing, cmd)) {
            throw new BusinessException(ErrorCode.IDEMPOTENT_REPLAY_CONFLICT, "同一 clientOrderId 的参数不一致");
        }
        metrics.orderIdempotentReplay();
        return OrderView.from(existing, commandStatus(existing.getOrderNo(), COMMAND_SUBMIT));
    }

    private boolean sameParameters(OrderDO existing, CreateOrderCommand cmd) {
        return Objects.equals(existing.getSecurityCode(), cmd.securityCode())
                && Objects.equals(existing.getSide(), cmd.side())
                && Objects.equals(existing.getOrderType(), cmd.orderType())
                && amountEquals(existing.getOrderPrice(), cmd.orderPrice())
                && Objects.equals(existing.getQuantity(), cmd.quantity());
    }

    private boolean amountEquals(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return left.compareTo(right) == 0;
    }

    private String commandStatus(String orderNo, String commandType) {
        UpstreamCommandDO command = commandMapper.selectByOrderNoAndType(orderNo, commandType);
        return command == null ? null : command.getStatus();
    }

    private void validate(CreateOrderCommand cmd) {
        if (cmd.quantity() == null || cmd.quantity() <= 0) {
            throw invalid("quantity", "quantity 必须为正");
        }
        if (!SIDES.contains(cmd.side())) {
            throw invalid("side", "side 必须为 BUY 或 SELL");
        }
        if (!ORDER_TYPES.contains(cmd.orderType())) {
            throw invalid("orderType", "orderType 必须为 LIMIT 或 MARKET");
        }
        if ("LIMIT".equals(cmd.orderType())) {
            if (cmd.orderPrice() == null || cmd.orderPrice().signum() <= 0) {
                throw invalid("orderPrice", "LIMIT 必须带正数 orderPrice");
            }
        } else if (cmd.orderPrice() != null) {
            throw invalid("orderPrice", "MARKET 不允许携带 orderPrice");
        }
    }

    private BusinessException invalid(String field, String message) {
        return new BusinessException(ErrorCode.INVALID_PARAM, message, List.of(field));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("指令 payload 序列化失败", e);
        }
    }
}
