package com.fuzuyang.securities.execution.application;

import com.fuzuyang.securities.common.error.DiffType;
import com.fuzuyang.securities.common.metrics.AppMetrics;
import com.fuzuyang.securities.execution.application.gap.GapDetector;
import com.fuzuyang.securities.execution.application.gap.GapProps;
import com.fuzuyang.securities.execution.infrastructure.mapper.ExecutionMapper;
import com.fuzuyang.securities.execution.infrastructure.mapper.ReportEventMapper;
import com.fuzuyang.securities.order.application.DiffService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 缺口扫描。
 *
 * <p>驱动集按窗口取订单（不按状态过滤）；"已收到序号" = 已应用 ∪ 被拒。
 * 多洞只报最小：同时缺 4 与 8 → 只落 {@code EXEC_GAP:{orderNo}:4}。
 */
@Service
public class GapScanTask {

    private final ExecutionMapper executionMapper;
    private final ReportEventMapper reportEventMapper;
    private final DiffService diffService;
    private final GapProps gapProps;
    private final AppMetrics metrics;

    public GapScanTask(ExecutionMapper executionMapper, ReportEventMapper reportEventMapper,
            DiffService diffService, GapProps gapProps, AppMetrics metrics) {
        this.executionMapper = executionMapper;
        this.reportEventMapper = reportEventMapper;
        this.diffService = diffService;
        this.gapProps = gapProps;
        this.metrics = metrics;
    }

    public void runOnce() {
        List<String> orders = executionMapper.selectDrivingOrders(gapProps.getScanWindowSeconds(),
                gapProps.getScanBatchSize());
        Instant now = Instant.now();
        for (String orderNo : orders) {
            List<Long> received = reportEventMapper.selectReceivedSeqs(orderNo);
            if (!GapDetector.shouldReport(received, toInstant(reportEventMapper.selectLastExecutionReportAt(orderNo)),
                    now, gapProps)) {
                continue;
            }
            long missing = GapDetector.minMissingSeq(received);
            diffService.record(DiffType.EXEC_GAP, orderNo, String.valueOf(missing), null, "minMissingSeq=" + missing);
            metrics.execGap();
        }
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    }
}
