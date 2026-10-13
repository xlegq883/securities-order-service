package com.fuzuyang.securities.order.infrastructure;

import com.fuzuyang.securities.order.application.port.UpstreamCommandSender;
import com.fuzuyang.securities.order.infrastructure.entity.UpstreamCommandDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 日志指令出站实现（{@code app.upstream.command-transport=LOG}，默认）。
 */
@Component
@ConditionalOnProperty(prefix = "app.upstream", name = "command-transport", havingValue = "LOG",
        matchIfMissing = true)
public class LoggingUpstreamCommandSender implements UpstreamCommandSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingUpstreamCommandSender.class);

    @Override
    public String transport() {
        return "LOG";
    }

    @Override
    public void send(UpstreamCommandDO command) {
        log.info("[command-log] type={} orderNo={} transport={} payload={}", command.getCommandType(),
                command.getOrderNo(), command.getTransport(), command.getPayload());
    }
}
