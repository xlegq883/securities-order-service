package com.fuzuyang.securities.execution.application;

import com.fuzuyang.securities.common.metrics.AppMetrics;
import com.fuzuyang.securities.execution.application.gap.GapProps;
import com.fuzuyang.securities.order.infrastructure.entity.ReconcileDiffDO;
import com.fuzuyang.securities.order.infrastructure.mapper.ReconcileDiffMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 缺口修复的短事务读写（认领 / 落结果）。对外拉取在事务之外。
 */
@Service
public class GapRepairStore {

    /** 退避序列 1s / 5s / 30s。 */
    private static final long[] BACKOFF_SECONDS = {1L, 5L, 30L};

    private final ReconcileDiffMapper mapper;
    private final GapProps gapProps;
    private final AppMetrics metrics;

    public GapRepairStore(ReconcileDiffMapper mapper, GapProps gapProps, AppMetrics metrics) {
        this.mapper = mapper;
        this.gapProps = gapProps;
        this.metrics = metrics;
    }

    /** 认领并推后 {@code next_attempt_at}（数据库时钟），避免处理期间被重复认领。 */
    @Transactional
    public List<ReconcileDiffDO> claim(int limit) {
        List<ReconcileDiffDO> rows = mapper.selectRepairableGaps(limit);
        for (ReconcileDiffDO row : rows) {
            mapper.markGapClaimed(row.getId());
        }
        return rows;
    }

    @Transactional
    public void finish(ReconcileDiffDO gap, boolean stillMissing) {
        if (!stillMissing) {
            mapper.markGapResolved(gap.getId());
            return;
        }
        int attempt = attemptOf(gap) + 1;
        if (attempt >= gapProps.getRepairMaxAttempts()) {
            mapper.markGapFailed(gap.getId(), attempt);
            metrics.execGapRepairExhausted();
        } else {
            mapper.markGapDeferred(gap.getId(), attempt, backoffSeconds(attempt));
        }
    }

    private int attemptOf(ReconcileDiffDO gap) {
        return gap.getAttemptCount() == null ? 0 : gap.getAttemptCount();
    }

    private long backoffSeconds(int attempt) {
        int index = Math.min(Math.max(attempt - 1, 0), BACKOFF_SECONDS.length - 1);
        return BACKOFF_SECONDS[index];
    }
}
