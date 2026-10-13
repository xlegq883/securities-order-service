package com.fuzuyang.securities.execution.application.schedule;

import com.fuzuyang.securities.execution.application.GapRepairTask;
import com.fuzuyang.securities.execution.application.GapScanTask;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 缺口扫描 + 修复调度（条件化：{@code app.gap.scan-enabled}）。测试中关闭调度、手动 {@code runOnce()}。
 */
@Component
@ConditionalOnProperty(prefix = "app.gap", name = "scan-enabled", havingValue = "true", matchIfMissing = true)
public class GapScanScheduler {

    private final GapScanTask scanTask;
    private final GapRepairTask repairTask;

    public GapScanScheduler(GapScanTask scanTask, GapRepairTask repairTask) {
        this.scanTask = scanTask;
        this.repairTask = repairTask;
    }

    @Scheduled(fixedDelayString = "${app.gap.scan-interval-ms:10000}")
    public void tick() {
        scanTask.runOnce();
        repairTask.runOnce();
    }
}
