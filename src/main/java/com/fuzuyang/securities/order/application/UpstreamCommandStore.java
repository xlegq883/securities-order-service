package com.fuzuyang.securities.order.application;

import com.fuzuyang.securities.common.error.DiffType;
import com.fuzuyang.securities.common.metrics.AppMetrics;
import com.fuzuyang.securities.order.infrastructure.entity.UpstreamCommandDO;
import com.fuzuyang.securities.order.infrastructure.mapper.UpstreamCommandMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 上游指令的短事务读写（三阶段投递的 ① 认领 与 ③ 落结果）。
 *
 * <p>达上限置 {@code FAILED} 时，在同一事务内落 {@code UPSTREAM_SUBMIT_FAILED} 差异
 * （失败事实由指令状态 + 差异表达，**不用 4xx/5xx**）。
 */
@Service
public class UpstreamCommandStore {

    private final UpstreamCommandMapper mapper;
    private final DiffService diffService;
    private final AppMetrics metrics;

    public UpstreamCommandStore(UpstreamCommandMapper mapper, DiffService diffService, AppMetrics metrics) {
        this.mapper = mapper;
        this.diffService = diffService;
        this.metrics = metrics;
    }

    @Transactional
    public List<UpstreamCommandDO> claim(int batchSize, long leaseSeconds) {
        List<UpstreamCommandDO> rows = mapper.selectClaimable(leaseSeconds, batchSize);
        if (rows.isEmpty()) {
            return rows;
        }
        String owner = UUID.randomUUID().toString();
        List<Long> ids = new ArrayList<>(rows.size());
        for (UpstreamCommandDO row : rows) {
            if ("IN_PROGRESS".equals(row.getStatus())) {
                metrics.relayLeaseReclaim("command");
            }
            row.setOwner(owner);
            row.setStatus("IN_PROGRESS");
            ids.add(row.getId());
        }
        mapper.markInProgress(ids, owner);
        return rows;
    }

    @Transactional
    public int markSent(long id, String owner) {
        return mapper.markSent(id, owner);
    }

    @Transactional
    public int markFailed(UpstreamCommandDO row, int attemptCount, String status, long backoffMicros,
            String lastError) {
        int rows = mapper.markFailed(row.getId(), row.getOwner(), status, attemptCount, backoffMicros, lastError);
        if (rows > 0 && "FAILED".equals(status)) {
            diffService.record(DiffType.UPSTREAM_SUBMIT_FAILED, row.getOrderNo(), row.getCommandType(), null, lastError);
            metrics.upstreamCommandFailed();
        }
        return rows;
    }
}
