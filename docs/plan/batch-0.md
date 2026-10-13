# 批次 0 执行单 · 脚手架与测试基建（1 天）

## 输入

- [`MASTER-SPEC.md`](MASTER-SPEC.md)、[`DECISIONS.md`](DECISIONS.md)

## 本批要定稿的执行层决策

- **包结构**：上下文在前、层在后 —— `com.fuzuyang.securities.{order|execution|query|eod}.{api|application|domain|infrastructure}`，另有 `common.{error|exception|config|util}`；每个上下文建 `package-info.java` 声明依赖方向。
- **端口约定**：HTTP 8080、gRPC 9090（本批不启用）、MySQL 3307（本机 3306 已占用）、Redis 6379、Kafka 9092；容器与服务 `TZ=Asia/Shanghai`。
- **测试命名与阶段**：`*Test` 走 surefire（T1）；`*IT` 走 failsafe（T2/T3），绑定 `verify` 阶段并默认执行。
- **T2 载体**：Testcontainers `mysql:8.0`（镜像已在本地）；不可用时降级为 profile 指向 compose 的 3307，**不得降级为 H2/mock**。
- **T3 载体**：`spring-kafka-test` 的 `EmbeddedKafka`（进程内真实 broker，不需要 Docker）。
- **CI**：GitHub Actions 跑 `mvn verify`，用 `services: mysql:8.0` 提供 T2 依赖。

## 产出文件

- `pom.xml`：Spring Boot 3.2.x parent、Java 17；依赖 web / validation / data-redis / spring-kafka / mybatis-spring-boot-starter / mysql-connector-j / actuator / micrometer-prometheus；测试依赖 starter-test / testcontainers-mysql / spring-kafka-test；插件 spring-boot、maven-failsafe-plugin。
- `docker-compose.yml`：`mysql:8.0`→3307（挂载 `sql/init.sql`）、`redis:7`→6379、`apache/kafka:3.7.0`→9092（KRaft 单节点），含 healthcheck 与 `TZ=Asia/Shanghai`。
- `sql/init.sql`：**12 张表一次建齐**（`t_order`、`t_execution`、`t_report_event`、`t_upstream_command`、`t_event_outbox`、`t_order_query`、`t_execution_query`、`t_reconcile_diff`、`t_eod_batch`、`t_eod_batch_step`、`t_eod_settlement`、`t_distributed_lock`）+ seed（`t_distributed_lock` 一行 `('read_model_rebuild', NULL, NULL, NULL, NULL)`，测试用账户与证券）。
- `src/main/resources/application.yml`、`application-local.yml`：数据源指向 3307、Redis/Kafka 地址、Jackson `time-zone: Asia/Shanghai`、actuator 暴露 health/info/prometheus。
- `src/main/java/com/fuzuyang/securities/SecuritiesOrderServiceApplication.java` + `common/config/{MyBatisConfig,JacksonTimeZoneConfig}.java` + 各上下文 `package-info.java`。
- `.github/workflows/verify.yml`。
- `README.md` 定稿（启动方式、模块划分、表清单、契约小节、测试命令）。
- 三条 smoke 测试：T1 `ApplicationContextTest`、T2 `SchemaIT`（12 张表与唯一键存在）、T3 `EmbeddedKafkaSmokeIT`（收发一条消息）。

## 验收

1. `docker compose up -d` 后三容器 healthy。
2. `mvn verify` 全绿（三条 smoke 均真实执行）。
3. `GET /actuator/health` 返回 UP。
4. 单独一次 commit。

## 风险

- 首次构建需联网下载依赖与插件（Maven 走阿里云镜像）；受限环境内不通，必须在本机非沙盒终端执行。
- Testcontainers 需要 Docker 运行；Docker 不可用时按上面的降级路径切到 compose 实例。
