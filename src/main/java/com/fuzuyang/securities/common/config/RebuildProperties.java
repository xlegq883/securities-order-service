package com.fuzuyang.securities.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 读模型重建配置（{@code app.rebuild.*}）。批次 4 使用。
 */
@ConfigurationProperties(prefix = "app.rebuild")
public class RebuildProperties {

    private String lockName = "read_model_rebuild";

    public String getLockName() {
        return lockName;
    }

    public void setLockName(String lockName) {
        this.lockName = lockName;
    }
}
