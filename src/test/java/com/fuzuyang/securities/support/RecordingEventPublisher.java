package com.fuzuyang.securities.support;

import com.fuzuyang.securities.order.application.port.EventPublisher;
import com.fuzuyang.securities.order.infrastructure.entity.EventOutboxDO;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可观测 + 可注入失败的事件出站实现（测试用）。
 */
public class RecordingEventPublisher implements EventPublisher {

    private final List<EventOutboxDO> published = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresRemaining = new AtomicInteger();

    public void failNext(int count) {
        failuresRemaining.set(count);
    }

    public void reset() {
        published.clear();
        failuresRemaining.set(0);
    }

    public List<EventOutboxDO> published() {
        return published;
    }

    @Override
    public void publish(EventOutboxDO event) {
        if (failuresRemaining.getAndUpdate(v -> v > 0 ? v - 1 : 0) > 0) {
            throw new IllegalStateException("模拟出站失败");
        }
        published.add(event);
    }
}
