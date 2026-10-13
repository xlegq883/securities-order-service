package com.fuzuyang.securities.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 查询数据源配置（{@code app.query.*}）。批次 2/4 使用。
 */
@ConfigurationProperties(prefix = "app.query")
public class QueryProperties {

    /** write | read。 */
    private String source = "write";

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
