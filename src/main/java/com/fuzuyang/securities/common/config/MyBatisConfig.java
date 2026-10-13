package com.fuzuyang.securities.common.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis 配置。
 *
 * <p>Mapper 约定：接口放 {@code <context>.infrastructure.mapper}，XML 放 {@code resources/mapper/<context>/}。
 * {@code mapper-locations} 在 application.yml 配为 {@code classpath*:mapper/**&#47;*.xml}。
 */
@Configuration
@MapperScan("com.fuzuyang.securities.**.infrastructure.mapper")
public class MyBatisConfig {
}
