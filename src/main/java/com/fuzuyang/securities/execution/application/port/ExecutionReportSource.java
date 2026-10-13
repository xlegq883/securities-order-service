package com.fuzuyang.securities.execution.application.port;

import com.fuzuyang.securities.execution.domain.ExecutionReport;
import java.util.List;

/**
 * 缺口修复补拉端口。
 *
 * <p>默认 {@code Noop}（{@code app.upstream.execution-source.enabled=false}）；测试用 Stub。
 * 补拉结果复用实时回报同一入口（{@code source=PULL}），重复由 {@code t_report_event.dedupe_key} 吸收。
 */
public interface ExecutionReportSource {

    /**
     * 拉取 {@code orderNo} 中 {@code seq >= fromSeq} 的成交回报（含边界），上限 {@code limit}。
     */
    List<ExecutionReport> pull(String orderNo, long fromSeq, int limit);
}
