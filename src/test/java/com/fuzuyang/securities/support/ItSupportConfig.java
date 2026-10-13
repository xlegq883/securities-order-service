package com.fuzuyang.securities.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试支撑 bean：可观测出站实现与缺口补拉 Stub。
 *
 * <p>{@link RecordingEventPublisher} 用 {@code @Primary} 覆盖默认事件出站；
 * {@link RecordingUpstreamCommandSender} transport 为 {@code RECORDING}，只在测试把
 * {@code app.upstream.command-transport} 设为该值时被装配（避免与 LOG 实现重名）；
 * {@link StubExecutionReportSource} {@code @Primary} 覆盖默认 Noop 补拉端口。
 */
@TestConfiguration
public class ItSupportConfig {

    @Bean
    @Primary
    public RecordingEventPublisher recordingEventPublisher() {
        return new RecordingEventPublisher();
    }

    @Bean
    public RecordingUpstreamCommandSender recordingUpstreamCommandSender() {
        return new RecordingUpstreamCommandSender();
    }

    @Bean
    @Primary
    public StubExecutionReportSource stubExecutionReportSource() {
        return new StubExecutionReportSource();
    }
}
