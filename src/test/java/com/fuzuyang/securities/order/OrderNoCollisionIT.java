package com.fuzuyang.securities.order;

import com.fuzuyang.securities.common.util.OrderNoGenerator;
import com.fuzuyang.securities.order.application.OrderCreationService;
import com.fuzuyang.securities.order.application.model.CreateOrderCommand;
import com.fuzuyang.securities.order.application.model.OrderView;
import com.fuzuyang.securities.support.AbstractMySqlIT;
import com.fuzuyang.securities.support.ItSupportConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2 订单号碰撞：{@code INSERT} 冲突后回查**未命中账户幂等键** ⇒ 判定为订单号碰撞 ⇒ 重新生成订单号并重试。
 *
 * <p>用 {@code @Primary} 的脚本化生成器强制制造一次碰撞。
 */
@ActiveProfiles("test")
@Import({ItSupportConfig.class, OrderNoCollisionIT.ScriptedGeneratorConfig.class})
class OrderNoCollisionIT extends AbstractMySqlIT {

    @TestConfiguration
    static class ScriptedGeneratorConfig {

        @Bean
        @Primary
        ScriptedOrderNoGenerator scriptedOrderNoGenerator() {
            return new ScriptedOrderNoGenerator();
        }
    }

    static class ScriptedOrderNoGenerator extends OrderNoGenerator {

        private final List<String> script = new ArrayList<>();

        void script(String... values) {
            script.clear();
            script.addAll(List.of(values));
        }

        @Override
        public String next() {
            return script.isEmpty() ? super.next() : script.remove(0);
        }
    }

    @Autowired
    private ScriptedOrderNoGenerator generator;
    @Autowired
    private OrderCreationService creationService;
    @Autowired
    private MeterRegistry registry;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void orderNoCollisionRegeneratesAndRetries() {
        String occupied = "OCOLLIDE0001";
        jdbc.update("INSERT INTO t_order (order_no, account_id, security_code, side, order_type, order_price, "
                        + "quantity, amount, filled_quantity, filled_amount, last_exec_seq, event_version, status, "
                        + "business_date, exchange_order_id, cancel_requested_at, client_order_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, NULL, NULL, ?)",
                occupied, "ACC-PRE", "600000", "BUY", "LIMIT", new BigDecimal("10"), 100L, new BigDecimal("1000"),
                0L, BigDecimal.ZERO, 1L, "SUBMITTED", LocalDate.now(), "pre-1");

        double before = registry.counter("order.order_no.collision").count();

        generator.script(occupied, "OUNIQUE0002");
        OrderView view = creationService.create(new CreateOrderCommand(
                "ACC-COLLIDE", UUID.randomUUID().toString(), "600000", "BUY", "LIMIT", new BigDecimal("10"), 100L));

        assertThat(view.orderNo()).isEqualTo("OUNIQUE0002");
        assertThat(registry.counter("order.order_no.collision").count()).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_order WHERE order_no=?", Integer.class, "OUNIQUE0002"))
                .isEqualTo(1);
    }
}
