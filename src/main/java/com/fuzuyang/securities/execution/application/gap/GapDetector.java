package com.fuzuyang.securities.execution.application.gap;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 缺口判定纯函数。
 *
 * <p>{@code exec_seq} 为订单内从 1 起、期望连续递增的成交序号。
 */
public final class GapDetector {

    private GapDetector() {
    }

    /**
     * {@code [1, max_seq]} 内最小未出现序号；无缺失返回 -1；集合为空返回 1。
     */
    public static long minMissingSeq(Collection<Long> receivedSeqs) {
        if (receivedSeqs == null || receivedSeqs.isEmpty()) {
            return 1L;
        }
        Set<Long> received = new HashSet<>(receivedSeqs);
        long max = received.stream().mapToLong(Long::longValue).max().orElse(0L);
        for (long seq = 1; seq <= max; seq++) {
            if (!received.contains(seq)) {
                return seq;
            }
        }
        return -1L;
    }

    /**
     * 触发条件：
     * <ul>
     *   <li>内部缺口：{@code minMissing > min_seen} 且 {@code max_seq - minMissing >= grace-count}；</li>
     *   <li>时间条件：{@code now - lastReportAt >= grace-seconds}（起始缺失只走时间条件）。</li>
     * </ul>
     * 无缺口（{@code minMissingSeq <= 0}）一律不报。
     */
    public static boolean shouldReport(Collection<Long> receivedSeqs, Instant lastReportAt, Instant now,
            GapProps props) {
        long missing = minMissingSeq(receivedSeqs);
        if (missing <= 0) {
            return false;
        }
        if (receivedSeqs != null && !receivedSeqs.isEmpty()) {
            long max = receivedSeqs.stream().mapToLong(Long::longValue).max().orElse(0L);
            long min = receivedSeqs.stream().mapToLong(Long::longValue).min().orElse(0L);
            if (missing > min && (max - missing) >= props.getGraceCount()) {
                return true;
            }
        }
        return lastReportAt != null && !now.isBefore(lastReportAt.plusSeconds(props.getGraceSeconds()));
    }
}
