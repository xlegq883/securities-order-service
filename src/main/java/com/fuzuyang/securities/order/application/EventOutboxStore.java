package com.fuzuyang.securities.order.application;

import com.fuzuyang.securities.common.metrics.AppMetrics;
import com.fuzuyang.securities.order.infrastructure.entity.EventOutboxDO;
import com.fuzuyang.securities.order.infrastructure.mapper.EventOutboxMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事件发件箱的短事务读写（三阶段投递的 ① 认领 与 ③ 落结果）。
 *
 * <p>认领在独立短事务内完成、提交后立即释放行锁；发送在事务之外由 relay 执行。
 */
@Service
public class EventOutboxStore {

    private final EventOutboxMapper mapper;
    private final AppMetrics metrics;

    public EventOutboxStore(EventOutboxMapper mapper, AppMetrics metrics) {
        this.mapper = mapper;
        this.metrics = metrics;
    }

    @Transactional
    public List<EventOutboxDO> claim(int batchSize, long leaseSeconds) {
        List<EventOutboxDO> rows = mapper.selectClaimable(leaseSeconds, batchSize);
        if (rows.isEmpty()) {
            return rows;
        }
        String owner = UUID.randomUUID().toString();
        List<Long> ids = new ArrayList<>(rows.size());
        for (EventOutboxDO row : rows) {
            if ("IN_PROGRESS".equals(row.getStatus())) {
                metrics.relayLeaseReclaim("event");
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
    public int markFailed(long id, String owner, String status, int retryCount, long backoffMicros) {
        return mapper.markFailed(id, owner, status, retryCount, backoffMicros);
    }
}
