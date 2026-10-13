# securities-order-service

证券订单与成交服务：订单受理、成交回报、查询、日终，以及配套的契约与横切能力。

## 实施规格（先读这里）

本项目的实现规格以 **总指针** 为准，不再凭记忆或口头约定改决策：

- 技术规格（唯一事实来源）：[`docs/plan/MASTER-SPEC.md`](docs/plan/MASTER-SPEC.md)
- 决策索引与理由：[`docs/plan/DECISIONS.md`](docs/plan/DECISIONS.md)
- 各批执行单：`docs/plan/batch-0.md` … `docs/plan/batch-5.md`

验收命令统一为 `mvn verify`；三层测试（T1 纯逻辑单测、T2 真库原子性与并发、T3 真 Kafka 语义）全部默认执行，无默认跳过机制。

## 技术栈

| 项 | 选型 |
|---|---|
| 语言 / 运行时 | Java 17 |
| 框架 | Spring Boot 3.2.5 |
| 持久化 | MyBatis 3.0.3 + MySQL 8.0 |
| 消息 | Kafka（`spring-kafka`，本地 `apache/kafka:3.7.0`） |
| 指标 | Micrometer + Prometheus（`/actuator/prometheus`） |
| 契约 | REST 先行；proto/gRPC 在批次 5 以原生 grpc-java 引入 |

> **不引入 Redis**（见 `DECISIONS.md` 变更 1）：幂等由数据库唯一键承担，分布式互斥由 `t_distributed_lock` 承担。
> **不引入 H2**（见 D22）：并发与 Kafka 语义断言只允许真库 / 真 broker。

## 模块划分

单模块 Maven。包结构“上下文在前、层在后”：

```
com.fuzuyang.securities
├─ order       订单受理、状态机、成交回报、撤单、上游指令与 relay
├─ execution   成交累加与状态推导、缺口检测与修复
├─ query       订单/成交查询、读模型消费与重建
├─ eod         日终批处理编排、逐单过期、结算、一致性校验
└─ common      api / error / exception / config / metrics / util
```

每个上下文按 `api → application → domain` 分层，`infrastructure` 依赖 `domain`/`application`；依赖单向，防腐层只在最外层。每个包以 `package-info.java` 声明依赖方向。

## 表清单（12 张，一次建齐）

SQL 只有一份单一来源：[`sql/V1__init.sql`](sql/V1__init.sql)，compose 与 Testcontainers 引用同一路径。

| 表 | 职责 |
|---|---|
| `t_order` | 订单聚合写表 |
| `t_execution` | 已应用成交明细（**只存已应用成交**） |
| `t_report_event` | 回报事件与一次性处理去重键 |
| `t_upstream_command` | 上游报单/撤单指令 |
| `t_event_outbox` | 事件发件箱（`uk` 保证同版本同 topic 至多一条） |
| `t_order_query` | 订单读模型（只由 `order-events` 驱动，带水位线） |
| `t_execution_query` | 成交读模型（只由 `execution-events` 驱动，**无水位线**） |
| `t_reconcile_diff` | 对账差异 |
| `t_eod_batch` | 日终批次编排 |
| `t_eod_batch_step` | 日终步骤（owner + 租约 + CAS） |
| `t_eod_settlement` | 日终结算结果（幂等锚点，绝对值覆盖） |
| `t_distributed_lock` | 跨事务长锁（禁用 `GET_LOCK`） |

时间列统一 `DATETIME(3)`（毫秒精度，服务查询游标）。金额列统一 `DECIMAL(18,4)`。

## 端口

| 端口 | 用途 |
|---|---|
| 8080 | HTTP / REST |
| 9090 | gRPC（批次 5） |
| 3307 | MySQL（本机 3306 已被占用，固定映射 3307） |
| 9092 | Kafka |

## 本地依赖与验收

```bash
docker compose up -d      # MySQL(3307) / Kafka(9092)，不启 Redis
mvn verify                # 唯一验收命令：T1 + T2 + T3 全部真实执行
```

- 服务启动：`mvn spring-boot:run -Dspring-boot.run.profiles=local`
- 健康检查：`GET /actuator/health` 应为 `UP`

> 约定：容器与服务统一 `TZ=Asia/Shanghai`；MySQL 固定映射到 **3307**。
> compose 修改 `sql/V1__init.sql` 后需 `docker compose down -v` 重建卷才生效（entrypoint 脚本只在数据目录为空时执行）。

## 测试分层（三层全部默认执行）

| 层 | 绑定 | 中间件 | 内容 |
|---|---|---|---|
| **T1** 纯逻辑单测 `*Test` | surefire | 无 | 状态迁移矩阵、CAS 分支、事件构造规则、去重键、金额口径、游标编解码、错误码映射 |
| **T2** 真库原子性与并发 `*IT` | failsafe | Testcontainers `mysql:8.0` | 并发去重、不变量、被拒回报正常提交、死锁外层重试、日终幂等、锁表互斥与接管、重建一致性 |
| **T3** 真 Kafka 语义 `*IT` | failsafe | `@EmbeddedKafka` | 重投只应用一次、失败不 ack、严格递增投影、跨流乱序最终一致、DLT 不阻塞分区、重建竞态 |

**H2 与 mock mapper 禁止**用于并发 / Kafka 语义断言。

> 注：`pom.xml` 将 Testcontainers 版本覆盖为 **1.21.4**（Boot 3.2.5 默认 1.19.7 与 Docker Engine 29 不兼容），详见 `DECISIONS.md` **变更 3 · 覆盖 1**。

## 契约指针

- **订单状态机（DAG，七态）**：`SUBMITTED`、`ACKNOWLEDGED`、`PARTIALLY_FILLED`、`FILLED`、`CANCELED`、`EXPIRED`、`REJECTED`（后四者为终态）。迁移用 `Map<Status, Set<Status>>` 矩阵表驱动 + `isTerminal()`。
- **写语句五条硬规则**：① 禁止同语句跨列依赖；② 每个聚合写事务只 bump 一次 `event_version`；③ 重试在事务之外且跨 Bean；④ 批量状态变更逐单 CAS；⑤ 金额同源。
- **CAS 判定口径**：分支只以 **CAS①**（累加 UPDATE）受影响行数为准；**CAS②（状态推导）的 0 行是正常自环**，仍返回 `applied:true`。
- **回报接口响应**：`POST /api/reports` 统一 200，响应体 `{orderNo, applied, diffType?, filledQuantity, status}`。业务拒绝走正常提交（不回滚），报告与差异同事务提交。
- **事件唯一性**：`order-events` 上每个 `(order_no, event_version)` **恰好一条** `ORDER_STATE_CHANGED`（性质放 `change_type`）；`execution-events` 每版本至多一条 `EXECUTION_APPLIED`。**同一事务可产出“同版本、跨 topic”两条事件**（order + execution），不违反“一事务一版本”。
- **`change_type`**：`SUBMITTED`（下单创建，决策变更 4）、`ACKED`、`REJECTED`、`CANCEL_REQUESTED`、`CANCELED`、`EXPIRED`、`FILL_UPDATED`（部分成交自环）、`FILLED`（累计到量）；只服务日志/指标/排查，不参与读侧排序与去重。
- **去重键**：成交类 `EXECUTION:{order_no}:{execution_id}`；订单回报与撤单确认类 `{report_type}:{order_no}:{exchange_seq}`；事件去重键 `{order_no}:{event_version}:ORDER_STATE_CHANGED` 与 `{order_no}:{event_version}:EXECUTION_APPLIED:{execution_id}`。
- **下单幂等**：`uk(account_id, client_order_id)`；参数一致 → 200 返回原订单；不一致 → 409 `IDEMPOTENT_REPLAY_CONFLICT`。幂等命中响应（下单与撤单）带 `upstreamCommandStatus`。
  - `order_no` 由服务端生成（`O + yyyyMMddHHmmssSSS + 6 位随机`）；**唯一性由 `uk_order_no` 保证**，生成器只降低碰撞率。`INSERT` 冲突后**回查区分两个唯一键**：命中账户幂等键 → 幂等分支；否则视为订单号碰撞 → 重新生成（最多 3 次，耗尽 → 503 `RETRY_EXHAUSTED`），并计 `order_order_no_collision_total`。
  - `LIMIT` 必带正数 `orderPrice`，`amount = orderPrice × quantity`；`MARKET` 的 `order_price` 与 `amount` 均为 NULL（成交金额与委托金额解耦）。
- **成交序号契约**：`exec_seq` 为订单内**从 1 起、期望连续递增**的成交序号；只用于缺口观测与补拉，**不作准入门槛**（累加可交换）。`ExecutionReportSource.pull(orderNo, fromSeq, limit)` 返回 `seq >= fromSeq`（含边界）的成交回报。
- **投递 relay（三阶段 + 至少一次）**：`短事务认领（IN_PROGRESS + owner + 租约）→ 事务外发送 → owner 守卫落结果（SENT / 退避回 PENDING / 达上限 FAILED）`。语义 = **at-least-once**：租约过期重认领、或"已发出但未标 `SENT` 时进程崩溃"都会**重复发送**，这是设计接受的行为；`SENT` 只表示"已交给出站通道"，不代表上游受理。重复敞口由 `relay_lease_reclaim_total{channel}` 度量（正常应为 0）。出站经端口（`EventPublisher`/`UpstreamCommandSender`），relay **不直接依赖 Kafka 客户端**；硬约束 `claim-lease-seconds > batch-size × send-timeout-ms`（启动期校验）。事件重复由读侧幂等消化，指令重复由上游按报文内幂等标识消化（SUBMIT 带 `clientOrderId`，CANCEL 带 `orderNo` + `exchangeOrderId`）。
- **缺口检测与修复**："已收到序号" = **已应用 ∪ 被拒**（只用 `t_execution` 会把被拒成交当缺失，产生永远修不掉的幻影缺口）；内部缺口（`minMissing > minSeen` 且 `max - minMissing >= grace-count`）或时间条件（无新回报 ≥ `grace-seconds`）触发；多洞只报最小。补拉**复用实时回报同一入口**（`source=PULL`），退避 1s/5s/30s；端口关闭时缺口保持 `OPEN`（由 Gauge 暴露），达 `repair-max-attempts` → `FAILED` + `exec_gap_repair_exhausted_total`。
- **响应边界**：报文/字段校验失败 → 400 `INVALID_PARAM` 且**零留痕**（不插报告表/不落差异/不计 applied-rejected）；业务/数据拒绝 → 200 + `applied:false` + `diffType`；撤单请求"订单不存在"→ 404，而回报管道里"订单不存在"→ 差异 `ORDER_NOT_FOUND`；真并发重试耗尽 → 503 `RETRY_EXHAUSTED`；发件箱唯一键冲突（程序缺陷）→ 500 且不进差异体系。
- **本批指标**：Counter 计入 `AppMetrics`（点号名不带 `_total`，导出名由断言钉住）：`order_created_total`、`order_idempotent_replay_total`、`order_order_no_collision_total`、`report_applied_total`、`report_rejected_total{diff_type}`、`report_invalid_total{reason}`、`exec_dup_total`、`exec_overfill_total`、`exec_after_terminal_total`、`illegal_transition_total`、`state_transition_total`、`state_unchanged_total`、`cancel_request_total`、`cancel_not_cancelable_total`、`exec_gap_total`、`exec_gap_repair_exhausted_total`、`retry_attempt_total`、`retry_exhausted_total`、`relay_lease_reclaim_total{channel}`、`upstream_command_sent_total`、`upstream_command_failed_total`、`event_outbox_sent_total`。Gauge 为**周期采样到内存**（默认 5s：`upstream_command_pending`、`event_outbox_pending`、`exec_gap_open`）——**Prometheus 抓取不产生任何数据库查询**，采样 SQL 均有索引支撑。
- **撤单**：仅 `ACKNOWLEDGED`/`PARTIALLY_FILLED` 可撤；`SUBMITTED` 与终态返回 `ORDER_NOT_CANCELABLE` 且不写 `cancel_requested_at`；重复撤单幂等成功；不引入“撤单中”状态。
- **读模型**：表与流一对一。`t_order_query` 由 `order-events` + 水位线驱动；`t_execution_query` 由 `execution-events` + `execution_id` 驱动且无水位线。跨流无顺序保证，只影响两表相对可见性。
- **锁策略**：跨事务长锁用 `t_distributed_lock`（owner + 租约 + CAS + owner 守卫释放），**禁用 `GET_LOCK`**；事务内短锁用 `SELECT ... FOR UPDATE SKIP LOCKED`。
- **重建协议（五步）**：① 锁表 CAS 抢占；② 优雅停容器并等待在途单条完成；③ 同一 REPEATABLE READ 事务内重算两张读模型表 + 读 `MAX(event_version)` 得 V0；④ 覆盖式 upsert 并把 `t_order_query.last_event_version` 写为 V0；⑤ 恢复消费并释放锁。
- **消费端**：`enable.auto.commit=false` + `MANUAL_IMMEDIATE` + 单条模式；`DefaultErrorHandler(DeadLetterPublishingRecoverer, ExponentialBackOffWithMaxRetries(3))` + `setCommitRecovered(true)`；DLT 用 `NewTopic` 显式创建。
- **金额口径**：`t_execution.exec_amount` 唯一真源；仅带价格与数量时按 `exec_amount = exec_price × exec_quantity` 算一次落库；对账禁止重算。小数数量 HALF_UP 取 4 位。
- **不变量**：`filled_amount = SUM(exec_amount)`、`filled_quantity = SUM(exec_quantity)`、明细行数 = 已采纳回报数、`status='FILLED' ⟺ filled_quantity = quantity`、终态不可逆。
- **时区与切日**：全链路 Asia/Shanghai；`business_date` = 下单时刻上海日期；EOD 只处理指定日；晚到回报归属原 `business_date`。
- **事务边界与副作用持久化**：业务拒绝不回滚；非业务异常才回滚。凡业务事务会回滚的路径，副作用记录必须 `REQUIRES_NEW`（EOD 步骤/批次失败标记、relay 指令失败标记、缺口修复耗尽标记）。
- **失败兜底与人工入口**：EOD 步骤失败 → `POST /api/eod/batch/{batchNo}/resume`；缺口修复用尽 → `POST /api/reconcile/diffs/{id}/resolve`；上游指令用尽 → `POST /api/upstream/commands/{id}/retry`；Kafka 超限进 DLT。
- **重试边界**：白名单 = `DeadlockLoserDataAccessException`、`CannotAcquireLockException`、`RetryableDispatchException`（0 行分类矩阵兜底）；尝试 3 次、间隔 50ms/200ms；事务内不得 catch 后重试。**非白名单异常耗尽时原样抛出**，不映射 503。

## 接口与配置（批次 1 落地）

### REST 端点

| 方法 | 路径 | 请求要点 | 响应 |
|---|---|---|---|
| POST | `/api/orders` | `{accountId, clientOrderId, securityCode, side ∈ BUY/SELL, orderType ∈ LIMIT/MARKET, orderPrice?, quantity}`；`LIMIT` 必带正数价、`MARKET` 禁带价 | 200 `{orderNo, …, upstreamCommandStatus}`；`clientOrderId` 参数不一致 → 409 |
| POST | `/api/reports` | `{reportType ∈ ORDER_ACKED / ORDER_REJECTED / CANCEL_ACKED / EXECUTION, orderNo, exchangeOrderId?, exchangeSeq?, executionId?, execSeq?, execQuantity?, execPrice?, execAmount?, execTime?, source?}` | 统一 200 `{orderNo, applied, diffType?, filledQuantity?, status?}`；报文/字段非法 → 400（零留痕） |
| POST | `/api/orders/{orderNo}/cancel` | 无体 | 200 `{orderNo, status, cancelRequestedAt, upstreamCommandStatus}`（已请求过亦 200，**不重新入队**）；不可撤 400 / 不存在 404 |

### 测试档案与中间件

- profile：**T1 = `local`（叠加 `test`）**、**T2 = `test`**、**T3 = `test-kafka`**。`@ActiveProfiles` 是**合并**语义，故 profile 由各测试类自带，基类不上提。
- `AbstractMySqlIT` 用**进程内单例 MySQL 容器**（`@ServiceConnection`，`--default-time-zone=+08:00`，启动一次、JVM 退出停止）；跨类共用同一库，relay/事件用例在 `@BeforeEach` 清空投递表自隔离，其余断言按订单维度。
- relay / 缺口任务在测试中手动 `runOnce()`，不依赖 `@Scheduled` 节拍。

### 批次 1 新增配置键

| 键 | 默认 | 说明 |
|---|---|---|
| `app.outbox.publisher` | `KAFKA` | `KAFKA` / `LOG`（测试与离线） |
| `app.outbox.send-timeout-ms` | `3000` | 单条发送超时 |
| `app.outbox.claim-lease-seconds` | `600` | 认领租约；**必须 > `batch-size × send-timeout-ms / 1000`**（启动期 `@AssertTrue` 校验） |
| `app.upstream.command-relay-enabled` | `true` | 指令投递定时开关 |
| `app.upstream.command-transport` | `LOG` | `LOG` / `HTTP` / `GRPC`（**同时**决定装配哪个 sender 与写入指令行的 `transport`） |
| `app.upstream.scan-interval-ms` / `batch-size` / `send-timeout-ms` / `claim-lease-seconds` | `1000` / `100` / `3000` / `600` | 指令投递参数（同 outbox 的租约硬约束） |
| `app.upstream.execution-source.enabled` | `false` | 缺口补拉端口（关闭时缺口保持 `OPEN`，由 Gauge 暴露） |
| `app.gap.scan-enabled` / `scan-interval-ms` / `scan-window-seconds` / `scan-batch-size` / `grace-count` / `grace-seconds` / `repair-max-attempts` / `repair-batch-size` | `true` / `10000` / `300` / `500` / `3` / `30` / `3` / `100` | 缺口扫描与修复 |
| `app.execution-conflict.precheck-enabled` | `true` | `uk_execution_id` 跨订单**事前检查**（关闭则强制走兜底路径，供测试） |
| `app.metrics.sample-interval-ms` | `5000` | Gauge 周期采样间隔 |
| `spring.task.scheduling.pool.size` | `4` | 调度隔离（relay 长批次不饿死采样/缺口扫描） |

## 批次进度

| 批次 | 内容 | 状态 |
|---|---|---|
| 0 | 脚手架：仓库/模块/compose（MySQL 3307、Kafka，`TZ=Asia/Shanghai`）/CI/README + 测试基建 | ✅ 已完成 |
| 1 | 状态机、成交累加与状态推导、事件写入、缺口端口、撤单、下单幂等、上游指令与 relay | ✅ 已完成 |
| 2 | 查询与游标分页 | ⏳ 待开工 |
| 3 | 日终：编排与租约接管、逐单过期、结算 upsert、一致性校验、失败终态与人工续跑 | ⏳ 待开工 |
| 4 | 读模型：消费、去重口径、位点与 DLT、重建五步、对账与失败兜底 | ⏳ 待开工 |
| 5 | 门禁 G1–G3 → proto/gRPC 契约与横切 | ⏳ 待开工 |
