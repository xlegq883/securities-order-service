package com.fuzuyang.securities.order.application.port;

import com.fuzuyang.securities.order.infrastructure.entity.UpstreamCommandDO;

/**
 * 上游指令出站端口。
 *
 * <p>{@link #transport()} 与指令行内 {@code transport} 取值对应；relay 按行内取值选择实现，
 * 找不到实现即该行失败，**不静默换通道**。
 */
public interface UpstreamCommandSender {

    String transport();

    void send(UpstreamCommandDO command) throws Exception;
}
