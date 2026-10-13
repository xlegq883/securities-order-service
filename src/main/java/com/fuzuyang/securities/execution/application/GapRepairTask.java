package com.fuzuyang.securities.execution.application;

import com.fuzuyang.securities.common.config.UpstreamProperties;
import com.fuzuyang.securities.execution.application.gap.GapDetector;
import com.fuzuyang.securities.execution.application.gap.GapProps;
import com.fuzuyang.securities.execution.application.model.ReportCommand;
import com.fuzuyang.securities.execution.application.port.ExecutionReportSource;
import com.fuzuyang.securities.execution.domain.ExecutionReport;
import com.fuzuyang.securities.execution.infrastructure.mapper.ReportEventMapper;
import com.fuzuyang.securities.order.infrastructure.entity.ReconcileDiffDO;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 缺口修复：认领 → **事务外**补拉 → 复用实时回报同一入口（{@code source=PULL}）→ 消退则 {@code RESOLVED}，
 * 仍在则退避累加，达上限 → {@code FAILED} + {@code exec.gap.repair.exhausted}。
 *
 * <p>端口默认关闭时**跳过、不累加**，缺口保持 {@code OPEN}。
 */
@Service
public class GapRepairTask {

    private static final String EXECUTION = "EXECUTION";
    private static final String PULL = "PULL";

    private final GapRepairStore store;
    private final ExecutionReportSource source;
    private final ReportDispatcher dispatcher;
    private final ReportEventMapper reportEventMapper;
    private final GapProps gapProps;
    private final UpstreamProperties upstreamProperties;

    public GapRepairTask(GapRepairStore store, ExecutionReportSource source, ReportDispatcher dispatcher,
            ReportEventMapper reportEventMapper, GapProps gapProps, UpstreamProperties upstreamProperties) {
        this.store = store;
        this.source = source;
        this.dispatcher = dispatcher;
        this.reportEventMapper = reportEventMapper;
        this.gapProps = gapProps;
        this.upstreamProperties = upstreamProperties;
    }

    public void runOnce() {
        if (!upstreamProperties.getExecutionSource().isEnabled()) {
            return;
        }
        List<ReconcileDiffDO> gaps = store.claim(gapProps.getRepairBatchSize());
        for (ReconcileDiffDO gap : gaps) {
            long fromSeq = missingSeqOf(gap);
            List<ExecutionReport> pulled = source.pull(gap.getOrderNo(), fromSeq, 100);
            for (ExecutionReport report : pulled) {
                dispatcher.dispatch(new ReportCommand(EXECUTION, report.orderNo(), null, report.exchangeSeq(),
                        report.executionId(), report.execSeq(), report.execQuantity(), report.execPrice(),
                        report.execAmount(), report.execTime(), PULL));
            }
            store.finish(gap, stillMissing(gap.getOrderNo()));
        }
    }

    private boolean stillMissing(String orderNo) {
        return GapDetector.minMissingSeq(reportEventMapper.selectReceivedSeqs(orderNo)) > 0;
    }

    private long missingSeqOf(ReconcileDiffDO gap) {
        String key = gap.getDedupeKey();
        int idx = key.lastIndexOf(':');
        return idx < 0 ? 1L : Long.parseLong(key.substring(idx + 1));
    }
}
