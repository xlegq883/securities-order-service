package com.fuzuyang.securities.order.infrastructure.mapper;

import com.fuzuyang.securities.order.infrastructure.entity.OrderDO;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 订单聚合写表访问。
 *
 * <p>写语句硬规则：CAS① 只累加量额并 bump 版本；CAS② 只推导状态、**不 bump 版本**、0 行是自环。
 * 两者不可合并（禁止同语句跨列依赖）。
 */
@Mapper
public interface OrderMapper {

    int insert(OrderDO order);

    OrderDO selectByOrderNo(@Param("orderNo") String orderNo);

    OrderDO selectByAccountAndClientId(@Param("accountId") String accountId,
            @Param("clientOrderId") String clientOrderId);

    /** 已报回报：{@code SUBMITTED → ACKNOWLEDGED}。 */
    int markAcked(@Param("orderNo") String orderNo, @Param("exchangeOrderId") String exchangeOrderId);

    /** 拒单回报：{@code SUBMITTED → REJECTED}。 */
    int markRejected(@Param("orderNo") String orderNo);

    /**
     * CAS①：累加量额、{@code last_exec_seq = GREATEST(COALESCE(...))}、bump 一次 {@code event_version}。
     * 守卫状态与容量，受影响行数即分支判定依据。
     */
    int applyExecution(@Param("orderNo") String orderNo, @Param("quantity") long quantity,
            @Param("amount") BigDecimal amount, @Param("execSeq") long execSeq);

    /**
     * CAS②：状态推导（{@code FILLED} / {@code PARTIALLY_FILLED}），条件含 {@code status <> 目标态}。
     * 不 bump 版本；0 行是正常自环。
     */
    int deriveStatus(@Param("orderNo") String orderNo);

    /** 撤单请求：仅 ACK/PARTIALLY 且未请求过。 */
    int requestCancel(@Param("orderNo") String orderNo);

    /** 撤单确认：仅 ACK/PARTIALLY 且未全额成交。 */
    int confirmCancel(@Param("orderNo") String orderNo);
}
