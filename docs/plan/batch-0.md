# 批次 0 执行单 · 脚手架与测试基建（1 天）

> 本单为 2026-10-13 讨论后定稿版本，覆盖此前初稿。技术决策以 [`MASTER-SPEC.md`](MASTER-SPEC.md) + [`DECISIONS.md`](DECISIONS.md)（含变更 1）为准。
> **本单只做骨架、schema 与测试基建，不写任何业务逻辑。**

## 一、开工第一步：决策变更记录

- 在 [`DECISIONS.md`](DECISIONS.md)"变更记录"中已写入**变更 1：Redis 移出技术栈**。
- **MASTER-SPEC.md 保持冻结，不得修改**；实施与验收以"MASTER-SPEC + 变更记录"为准。

## 二、SQL 单一来源与 Testcontainers 接线（三个已知翻车点，逐条钉死）

1. **单一文件**：只有 `sql/V1__init.sql` 一份，compose 与测试**引用同一路径**，不往 `src/test/resources` 拷副本。
2. **不用 `withInitScript`**：它按 classpath 查找，根目录 `sql/` 不在测试 classpath 上，必然启动失败。改为**显式挂载**，与 compose 走同一条执行路径：

   ```java
   new MySQLContainer<>("mysql:8.0")
       .withDatabaseName("securities")
       .withUrlParam("serverTimezone", "Asia/Shanghai")
       .withUrlParam("characterEncoding", "utf8")
       .withCopyFileToContainer(
           MountableFile.forHostPath("sql/V1__init.sql"),
           "/docker-entrypoint-initdb.d/V1__init.sql")
   ```

3. **库名一致**：`withDatabaseName("securities")` 让 `getJdbcUrl()` 指向 `securities`；脚本内用 `CREATE DATABASE IF NOT EXISTS securities;` + `USE securities;`，与 compose 的 `MYSQL_DATABASE=securities` 语义一致；应用配置指向 `jdbc:mysql://localhost:3307/securities`。SchemaIT 另断言 `DATABASE()` 为 `securities`，把"库名不一致"钉死。
4. **脚本必须幂等**：`CREATE DATABASE IF NOT EXISTS` / `CREATE TABLE IF NOT EXISTS`，种子用 `INSERT ... ON DUPLICATE KEY UPDATE`。
5. **cwd 守卫**：SchemaIT 第一步断言 `Files.exists(Path.of("sql/V1__init.sql"))`，路径错时报清晰错误（surefire/failsafe 默认工作目录为 `${basedir}`）。
6. **接线方式**：引入 `org.springframework.boot:spring-boot-testcontainers`，用 `@Testcontainers` + `@Container @ServiceConnection MySQLContainer` 注入 DataSource，不手写 `@DynamicPropertySource`。
7. **操作陷阱**：entrypoint 脚本只在容器数据目录为空时执行 —— compose 场景改脚本后需 `docker compose down -v` 重建卷才生效；Testcontainers 每次新容器，不受影响。

## 三、产出文件

- **pom.xml**：parent `spring-boot-starter-parent:3.2.5`；`com.fuzuyang:securities-order-service:0.0.1-SNAPSHOT`；Java 17 + UTF-8。依赖：web、validation、spring-kafka、`mybatis-spring-boot-starter:3.0.3`、`mysql-connector-j`(runtime)、actuator、`micrometer-registry-prometheus`、lombok；测试：starter-test、spring-kafka-test、**spring-boot-testcontainers**、`org.testcontainers:mysql` + `junit-jupiter`（`testcontainers-bom` 统一版本）。**不引入 data-redis，不引入 h2**。插件：spring-boot-maven-plugin、maven-compiler-plugin(UTF-8)、**maven-failsafe-plugin**（绑定 `verify`，includes `**/*IT.java`）。
- **docker-compose.yml**：`mysql:8.0`→3307（`MYSQL_DATABASE=securities`、`TZ=Asia/Shanghai`、utf8mb4、挂载 `./sql/V1__init.sql`、healthcheck、持久卷）；`apache/kafka:3.7.0`→9092（KRaft 单节点，沿用老仓库已验证 env 块、healthcheck、持久卷）；**不启 Redis**；bridge 网络。
- **sql/V1__init.sql**：建库 + **12 张表一次建齐**（字段与唯一键以 MASTER-SPEC 为准）：`t_order`(`uk_order_no`、`uk(account_id, client_order_id)`、`idx_account_created_id`、`idx_status`)、`t_execution`(`uk_execution_id`、`idx_order_seq`)、`t_report_event`(`uk_dedupe_key`)、`t_upstream_command`(`uk(order_no, command_type)`)、`t_event_outbox`(`uk_dedupe_key`、`idx_status_next`)、`t_order_query`(PK `order_no`)、`t_execution_query`(PK `execution_id`)、`t_reconcile_diff`(`uk_dedupe_key`)、`t_eod_batch`(`uk(business_date, batch_no)`)、`t_eod_batch_step`(`uk(batch_id, step_name)`)、`t_eod_settlement`(`uk(business_date, account_id, security_code)`)、`t_distributed_lock`(PK `lock_name`)。种子仅 `t_distributed_lock` 一行 `('read_model_rebuild', NULL, NULL, NULL, NULL)`。
- **配置**：
  - `application.yml`：Jackson `time-zone: Asia/Shanghai`、`spring.sql.init.encoding: UTF-8`、Kafka producer `acks=all` + `max.block.ms=3000`、consumer `group-id` + `auto-offset-reset: earliest`、MyBatis `mapper-locations` + `map-underscore-to-camel-case`、`server.port=8080`、`app.*` 新键（`upstream.execution-source.enabled=false`、`outbox.{topic,partitions,relay-enabled,scan-interval-ms,batch-size,max-retries,base-backoff,max-backoff}`、`eod.{lease-seconds=60,heartbeat-seconds=15,max-attempts=3}`、`consumer.{ack-mode=MANUAL_IMMEDIATE,max-attempts=3}`、`query.source=write`、`rebuild.lock-name=read_model_rebuild`）。
  - `application-local.yml`：JDBC `jdbc:mysql://localhost:3307/securities?...&serverTimezone=Asia/Shanghai`、hikari `initialization-fail-timeout=-1` + `connection-timeout=3000` + `maximum-pool-size=30`、Kafka `localhost:9092`。
- **代码骨架**（只有骨架，无业务逻辑）：`com.fuzuyang.securities.{order|execution|query|eod}.{api|application|domain|infrastructure}` + `common.{api|error|exception|config|util}`，各上下文与层建 `package-info.java` 声明依赖方向；`SecuritiesOrderServiceApplication`；`common/config/{MyBatisConfig, JacksonTimeZoneConfig, KafkaTopicConfig}`（`NewTopic` 显式创建 `order-events`、`execution-events` 及各自 `.DLT`）。
- **CI**：`.github/workflows/verify.yml` —— ubuntu-latest + temurin 17 + maven cache，跑 `mvn -B -Dfile.encoding=UTF-8 verify`；**无 `services:` 块**（T2 用 Testcontainers，runner 自带 Docker；T3 用 EmbeddedKafka，无需容器）。
- **README.md** 定稿：启动方式、模块划分、表清单、契约指针（事件唯一性与"同版本跨 topic"、锁策略、重建五步）、三层测试说明与命令。

## 四、测试基建与三条 smoke

- **绑定**：`*Test` → surefire（T1 纯逻辑、无需中间件）；`*IT` → failsafe（T2 真 MySQL / T3 真 Kafka），绑定 `verify` 且**默认执行**。
- **T1 `ApplicationContextTest`**：上下文加载成功（本地/测试 profile 下 `initialization-fail-timeout=-1`）。
- **T2 `SchemaIT`**：按第二节接线 + 文件存在守卫；查 `information_schema` 断言 **12 张表、全部唯一键与关键索引**存在，并断言 `DATABASE()` 为 `securities`。
- **T3 `KafkaSmokeIT`**：`@EmbeddedKafka` 真实 broker 收发一条消息，断言 topic 与 key。
- **禁止 H2**（D22）：并发与 Kafka 语义断言只允许真库/真 broker。

## 五、验收

1. `docker compose up -d` 后 MySQL 与 Kafka 两个容器 healthy（不出现 Redis）。
2. `mvn verify` 全绿：T1 上下文、T2 12 表 schema 断言、T3 Kafka 收发三条 smoke 均真实执行。
3. `GET /actuator/health` 返回 UP。
4. `DECISIONS.md` 已有 Redis 变更记录；本执行单与实现一致。
5. 单独一次 commit（提交信息注明"批次 0"）。

## 六、假设、默认与风险

- Spring Boot 锁定 **3.2.5**、MyBatis 3.0.3、Lombok 保留；单模块 Maven；包结构"上下文在前、层在后"。
- 端口：HTTP 8080、MySQL 3307、Kafka 9092；**6379 不再占用**。
- 构建与测试必须在**本机非沙盒终端**执行（受限环境无网络、Docker API 不可达）；编码与静态自检可离线完成，`mvn verify` 结果需人工回贴。
- 首次构建需联网下载依赖与镜像；Docker 未运行则 T2 失败（T2 不允许降级为 H2/mock）。
- 本批不写业务逻辑；状态机、回报处理、日终、读模型全部留给后续批次。
