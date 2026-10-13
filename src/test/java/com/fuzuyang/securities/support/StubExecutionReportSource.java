package com.fuzuyang.securities.support;

import com.fuzuyang.securities.execution.application.port.ExecutionReportSource;
import com.fuzuyang.securities.execution.domain.ExecutionReport;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 缺口修复补拉 Stub（测试用）：按订单脚本化返回 {@code seq >= fromSeq}（含边界）。
 */
public class StubExecutionReportSource implements ExecutionReportSource {

    private final Map<String, List<ExecutionReport>> scripts = new ConcurrentHashMap<>();

    public void script(String orderNo, List<ExecutionReport> reports) {
        scripts.put(orderNo, reports);
    }

    public void clear() {
        scripts.clear();
    }

    @Override
    public List<ExecutionReport> pull(String orderNo, long fromSeq, int limit) {
        return scripts.getOrDefault(orderNo, List.of()).stream()
                .filter(report -> report.execSeq() >= fromSeq)
                .limit(limit)
                .toList();
    }
}
