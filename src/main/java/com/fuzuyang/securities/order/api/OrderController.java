package com.fuzuyang.securities.order.api;

import com.fuzuyang.securities.common.config.OrderCommandRetry;
import com.fuzuyang.securities.order.api.dto.CancelOrderResponse;
import com.fuzuyang.securities.order.api.dto.CreateOrderRequest;
import com.fuzuyang.securities.order.api.dto.OrderResponse;
import com.fuzuyang.securities.order.application.CancelApplicationService;
import com.fuzuyang.securities.order.application.OrderCreationService;
import com.fuzuyang.securities.order.application.model.CancelView;
import com.fuzuyang.securities.order.application.model.CreateOrderCommand;
import com.fuzuyang.securities.order.application.model.OrderView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 下单与撤单入口（REST）。
 *
 * <p>重试壳包住跨 Bean 的事务调用（事务之外重试）。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderCreationService orderCreationService;
    private final CancelApplicationService cancelApplicationService;
    private final OrderCommandRetry retry;

    public OrderController(OrderCreationService orderCreationService,
            CancelApplicationService cancelApplicationService, OrderCommandRetry retry) {
        this.orderCreationService = orderCreationService;
        this.cancelApplicationService = cancelApplicationService;
        this.retry = retry;
    }

    @PostMapping
    public OrderResponse create(@Valid @RequestBody CreateOrderRequest request) {
        CreateOrderCommand command = new CreateOrderCommand(request.accountId(), request.clientOrderId(),
                request.securityCode(), request.side(), request.orderType(), request.orderPrice(),
                request.quantity());
        OrderView view = retry.execute(() -> orderCreationService.create(command));
        return OrderResponse.from(view);
    }

    @PostMapping("/{orderNo}/cancel")
    public CancelOrderResponse cancel(@PathVariable String orderNo) {
        CancelView view = retry.execute(() -> cancelApplicationService.request(orderNo));
        return CancelOrderResponse.from(view);
    }
}
