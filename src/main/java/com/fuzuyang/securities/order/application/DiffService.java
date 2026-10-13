package com.fuzuyang.securities.order.application;

import com.fuzuyang.securities.common.error.DiffType;
import com.fuzuyang.securities.order.infrastructure.entity.ReconcileDiffDO;
import com.fuzuyang.securities.order.infrastructure.mapper.ReconcileDiffMapper;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 对账差异落库。
 *
 * <p>重复拒绝不产生新行（{@code uk_dedupe_key} + {@code ON DUPLICATE KEY UPDATE}）。
 * 凡业务事务会回滚的路径（竞态穿透等）副作用必须走 {@link #recordRequiresNew}。
 */
@Service
public class DiffService {

    private final ReconcileDiffMapper mapper;

    public DiffService(ReconcileDiffMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(DiffType diffType, String orderNo, String fragment, LocalDate businessDate, String detail) {
        insert(diffType, orderNo, fragment, businessDate, detail);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRequiresNew(DiffType diffType, String orderNo, String fragment, LocalDate businessDate,
            String detail) {
        insert(diffType, orderNo, fragment, businessDate, detail);
    }

    private void insert(DiffType diffType, String orderNo, String fragment, LocalDate businessDate, String detail) {
        mapper.upsert(ReconcileDiffDO.builder()
                .businessDate(businessDate)
                .orderNo(orderNo)
                .diffType(diffType.name())
                .detail(detail)
                .dedupeKey(dedupeKey(diffType, orderNo, fragment))
                .status("OPEN")
                .attemptCount(0)
                .build());
    }

    /** 差异键：{@code {diffType}:{orderNo}[:{fragment}]}。成交类片段取 executionId，订单/撤单确认类取 exchangeSeq。 */
    public static String dedupeKey(DiffType diffType, String orderNo, String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return diffType.name() + ":" + orderNo;
        }
        return diffType.name() + ":" + orderNo + ":" + fragment;
    }
}
