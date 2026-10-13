package com.fuzuyang.securities.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 成交冲突处理配置（{@code app.execution-conflict.*}）。
 */
@ConfigurationProperties(prefix = "app.execution-conflict")
public class ExecutionConflictProperties {

    /**
     * 是否启用 {@code uk_execution_id} 跨订单占用的**事前检查**。
     *
     * <p>默认 true（干净路径零回滚）；关闭后强制走"竞态穿透 → 主事务回滚 → REQUIRES_NEW 落差异"的兜底路径，
     * 供测试覆盖兜底分支。
     */
    private boolean precheckEnabled = true;

    public boolean isPrecheckEnabled() {
        return precheckEnabled;
    }

    public void setPrecheckEnabled(boolean precheckEnabled) {
        this.precheckEnabled = precheckEnabled;
    }
}
