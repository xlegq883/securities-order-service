package com.fuzuyang.securities.execution.infrastructure;

import com.fuzuyang.securities.execution.application.port.ExecutionReportSource;
import com.fuzuyang.securities.execution.domain.ExecutionReport;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 缺口修复端口默认实现：不补拉（{@code app.upstream.execution-source.enabled=false} 或缺省）。
 *
 * <p>端口关闭时缺口保持 {@code OPEN}，由 Gauge 暴露，由人工关注。
 */
@Component
@ConditionalOnProperty(prefix = "app.upstream.execution-source", name = "enabled", havingValue = "false",
        matchIfMissing = true)
public class NoopExecutionReportSource implements ExecutionReportSource {

    @Override
    public List<ExecutionReport> pull(String orderNo, long fromSeq, int limit) {
        return List.of();
    }
}
