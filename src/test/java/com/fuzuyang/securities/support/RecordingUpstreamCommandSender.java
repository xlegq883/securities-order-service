package com.fuzuyang.securities.support;

import com.fuzuyang.securities.order.application.port.UpstreamCommandSender;
import com.fuzuyang.securities.order.infrastructure.entity.UpstreamCommandDO;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可观测 + 可注入失败的指令出站实现（测试用）。transport 固定为 {@code RECORDING}，
 * 测试把 {@code app.upstream.command-transport} 设为该值即可只装配本实现（避免与 LOG 实现重名）。
 */
public class RecordingUpstreamCommandSender implements UpstreamCommandSender {

    public static final String TRANSPORT = "RECORDING";

    private final List<UpstreamCommandDO> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresRemaining = new AtomicInteger();
    private volatile java.util.function.Consumer<UpstreamCommandDO> onSend = command -> { };

    /** 发送期钩子（用于在"发送发生时刻"用另一条连接查库观测状态）。 */
    public void setOnSend(java.util.function.Consumer<UpstreamCommandDO> onSend) {
        this.onSend = onSend;
    }

    public void failNext(int count) {
        failuresRemaining.set(count);
    }

    public void reset() {
        sent.clear();
        failuresRemaining.set(0);
    }

    public List<UpstreamCommandDO> sent() {
        return sent;
    }

    @Override
    public String transport() {
        return TRANSPORT;
    }

    @Override
    public void send(UpstreamCommandDO command) {
        if (failuresRemaining.getAndUpdate(v -> v > 0 ? v - 1 : 0) > 0) {
            throw new IllegalStateException("模拟指令发送失败");
        }
        onSend.accept(command);
        sent.add(command);
    }
}
