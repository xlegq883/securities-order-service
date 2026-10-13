package com.fuzuyang.securities.support;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * T2 真库 / T3 真库+真 broker 测试基类。
 *
 * <p>只承载 {@code @SpringBootTest}、共享 MySQL 容器与 cwd 守卫；profile 由各子类声明
 * （{@code @ActiveProfiles} 是**合并**语义，基类声明会与子类叠加，故不上提）。
 *
 * <p>容器为**进程内单例**：启动一次、不按测试类停。若用 {@code @Testcontainers}/{@code @Container}，
 * 容器会在每个类结束时被停止，而 Spring 上下文可能被复用，导致后续用例连到已停容器。
 */
@SpringBootTest
public abstract class AbstractMySqlIT {

    protected static final String INIT_SCRIPT = "sql/V1__init.sql";

    @ServiceConnection
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("securities")
            // 与 compose 对齐：全链路 Asia/Shanghai（NOW(3) 必须是上海时间）
            .withCommand("--default-time-zone=+08:00", "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_unicode_ci")
            .withUrlParam("serverTimezone", "Asia/Shanghai")
            .withUrlParam("characterEncoding", "utf8")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(INIT_SCRIPT),
                    "/docker-entrypoint-initdb.d/V1__init.sql");

    static {
        if (!Files.exists(Path.of(INIT_SCRIPT))) {
            throw new IllegalStateException(
                    "找不到 SQL 单一来源文件 " + INIT_SCRIPT + "，当前工作目录=" + Path.of("").toAbsolutePath());
        }
        MYSQL.start();
        Runtime.getRuntime().addShutdownHook(new Thread(MYSQL::stop));
    }
}
