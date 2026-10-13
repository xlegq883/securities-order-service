package com.fuzuyang.securities;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 证券订单与成交服务启动类。
 *
 * <p>四大限界上下文：order / execution / query / eod，各自按 api / application / domain / infrastructure
 * 分层，依赖单向，防腐层只在最外层。
 */
@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class SecuritiesOrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SecuritiesOrderServiceApplication.class, args);
    }
}
