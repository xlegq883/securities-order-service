# 批次 1 执行单 · 订单与成交核心（4 天）

> **定稿版**（2026-10-13 逐项讨论后整体重写，覆盖初稿）。技术决策以 [`MASTER-SPEC.md`](MASTER-SPEC.md) + [`DECISIONS.md`](DECISIONS.md)（含变更 1–6）为准。
> 本批**不写日终（批次 3）**、**不写读模型消费方（批次 4）**。

## 一、输入

- 总指针与决策索引；批次 0 已完成（骨架、12 张表、测试基建可用）。
- 注意：Testcontainers 已在 `pom.xml` 覆盖为 **1.21.4**（见 `DECISIONS.md` 变更 3 覆盖 1），T2 可直接依赖。

## 二、范围（对应工作经历能力 1–5）

1. 状态机（七态 DAG、迁移矩阵、终态守卫）
2. 成交累加与状态推导（金额同源、仅采纳落明细、被拒正常提交）
3. 事件写入与可靠投递（每版本每 topic 至多一条；三阶段 relay；至少一次）
4. 回报统一入口、去重键与 0 行分类
5. 撤单（请求 + 确认）、下单幂等（LIMIT 与 MARKET）、上游指令 relay
6. 缺口检测与修复（EXEC_GAP）
7. 本批可观测指标（含 Gauge 周期采样）

## 三、本批定稿的执行层决策

### 3.1 回报分发（唯一入口）

`ReportController` 只做 HTTP 适配（报文校验 400 早退、错误映射）；`ReportDispatcher` 是四类回报唯一入口，负责横切三事：① 插 `t_report_event`（`applied=1`，`uk_dedupe_key` 冲突就地 catch → `DUPLICATE_REPORT`）；② 同事务分发（handler 的 `@Transactional(REQUIRED)` 加入外层）；③ 被拒时统一"改 `applied=0` + 落差异"。`RetryTemplate` 外壳包住 `ReportDispatcher.dispatch` 的跨 Bean 调用。

| reportType | 落点 | 职责 |
|---|---|---|
| `ORDER_ACKED` | `OrderReportService.handleAcked(report)` | 挂 `exchange_order_id`、推 `ACKNOWLEDGED` + 发事件 |
| `ORDER_REJECTED` | `OrderReportService.handleRejected(report)` | 推 `REJECTED` + 发事件 |
| `CANCEL_ACKED` | **`CancelApplicationService.confirm(report)`** | 推 `CANCELED`（守卫 `filled_quantity < quantity`）+ 发事件 |
| `EXECUTION` | `ExecutionApplicationService.apply(report)` | 事前检查 → CAS①/CAS② → 插明细 → 两条事件 |

未知 `reportType` 在控制器校验阶段返回 400 `INVALID_PARAM`。

### 3.2 唯一键冲突：三类处置

| 唯一键 | 性质 | 处置 |
|---|---|---|
| `t_report_event.uk_dedupe_key` | 回报重复投递 | 事务方法体内就地 `catch (DuplicateKeyException)` → 200 + `applied:false` + `DUPLICATE_REPORT`；**catch 不可穿出代理**（否则 rollback-only → `UnexpectedRollbackException`） |
| `t_execution.uk_execution_id` | 上游脏数据（同 executionId 跨订单） | **事前检查**（插报告表后、CAS① 前查 `execution_id` 是否被别的订单占用 → 直接 `DATA_CONFLICT`，不执行 CAS①）+ **兜底**（竞态穿透则主事务回滚，外层用 `REQUIRES_NEW` 落差异）；两者均 200 + `applied:false` + `DATA_CONFLICT` |
| `t_event_outbox` 两唯一键 | 程序缺陷 | **传播 → 500 + 告警**，不进差异体系 |

- 该约束以 MySQL/InnoDB 为前提（单语句冲突不中止事务）；迁移 PostgreSQL 需把 INSERT 包进 `SAVEPOINT`（`PROPAGATION_NESTED`）。
- 可测性开关：`app.execution-conflict.precheck-enabled`（默认 true），关闭后强制走兜底路径。
- 可重入性：脏回报因主事务回滚不留报告行，重复投递会重走同一判定；差异靠 `dedupe_key` 不重复、量额不重复计入。

### 3.3 `t_report_event.applied` 写入时机

插入时按 `applied=1`（结果未定，同事务中间值对外不可见）；被拒分支同事务内**先** `UPDATE … SET applied=0` **再**落差异；重复投递命中 `dedupe_key` 时**不改写原行**；事务回滚则整行消失。四条回报路径统一。指标与字段对齐：`report.applied` 仅在 CAS 确认应用后计数，`report.rejected` 在分类后计数。

### 3.4 0 行分类矩阵（行=回读到的到达态，列=回报类型）

| 到达态 | ORDER_ACKED | ORDER_REJECTED | CANCEL_ACKED | EXECUTION |
|---|---|---|---|---|
| 空 | `ORDER_NOT_FOUND` | `ORDER_NOT_FOUND` | `ORDER_NOT_FOUND` | `ORDER_NOT_FOUND`（executionId 被他单占用 → `DATA_CONFLICT`） |
| SUBMITTED | 应用 → ACKNOWLEDGED | 应用 → REJECTED | `ILLEGAL_TRANSITION` | `ILLEGAL_TRANSITION` |
| ACKNOWLEDGED | `ILLEGAL_TRANSITION` | `ILLEGAL_TRANSITION` | 应用 → CANCELED ※ | 应用（容量不足 → `EXEC_OVERFILL`） |
| PARTIALLY_FILLED | `ILLEGAL_TRANSITION` | `ILLEGAL_TRANSITION` | 应用 → CANCELED ※ | 应用（容量不足 → `EXEC_OVERFILL`） |
| FILLED | `AFTER_TERMINAL` | `AFTER_TERMINAL` | `CANCEL_AFTER_FILL` | `EXEC_AFTER_TERMINAL` |
| CANCELED | `AFTER_TERMINAL` | `AFTER_TERMINAL` | `AFTER_TERMINAL` | `EXEC_AFTER_TERMINAL` |
| EXPIRED | `AFTER_TERMINAL` | `AFTER_TERMINAL` | `AFTER_TERMINAL` | `EXEC_AFTER_TERMINAL` |
| REJECTED | `AFTER_TERMINAL` | `AFTER_TERMINAL` | `AFTER_TERMINAL` | `EXEC_AFTER_TERMINAL` |

- ※ 应用条件 `filled_quantity < quantity`；非终态却 `filled == quantity`（理论不可见）→ `ILLEGAL_TRANSITION`。
- **边界规则（一句话）**：只有"成交回报晚于终态"用 `EXEC_AFTER_TERMINAL`、"撤单确认晚于全额成交"用 `CANCEL_AFTER_FILL`，其余一切"晚于终态"统一 `AFTER_TERMINAL`（`CANCEL_AFTER_FILL` 单列是因为它代表交易所侧正常竞态而非上游重复推送）。
- **差异键片段**：成交类取 `executionId`；订单类与撤单确认类取 `exchange_seq`；`ORDER_NOT_FOUND` 至少含 `orderNo`。
- **兜底**：矩阵未覆盖（行存在、非终态、非上述）→ `RETRYABLE`，外层重试 ≤3 次（50ms/200ms），耗尽 → 503 `RETRY_EXHAUSTED` + `retry.exhausted`。

### 3.5 写路径 SQL 与守卫（要点）

- **下单创建**（单事务）：先查 `uk_account_client` → 命中且参数一致 200 原订单（含 `upstreamCommandStatus`）；一致/不一致判定要在**并发冲突回查后重新比对参数**；未命中 → `INSERT t_order(status='SUBMITTED', event_version=1)`（方法体内 catch `DuplicateKeyException`）→ 事件（v1、`change_type=SUBMITTED`）→ `INSERT t_upstream_command(SUBMIT, transport=配置值, PENDING)`。
  - 金额规则：`LIMIT` → `order_price` 必填且 > 0，`amount = order_price × quantity`（整数数量、4 位小数无舍入，经 `Money`）；`MARKET` → `order_price` 与 `amount` **均为 NULL**。成交金额与委托金额解耦（`filled_amount`/`exec_amount` 一律以回报为准）。
- **已报 / 拒单**：`UPDATE t_order SET status=?, exchange_order_id=?, event_version=event_version+1 WHERE order_no=? AND status='SUBMITTED'`。
- **成交**：插 `t_report_event` → **事前检查** → **CAS①**（守卫 `status IN ('ACKNOWLEDGED','PARTIALLY_FILLED')` 且 `filled_quantity + :q <= quantity`；SET 累加量额、`last_exec_seq = GREATEST(COALESCE(last_exec_seq,0), :seq)`、`event_version = event_version + 1`）→ 1 行插 `t_execution`（`exec_amount` 报文原样，缺失时 `Money` 算一次，与 `:a` 同源）→ **CAS②**（`SET status = CASE WHEN filled_quantity = quantity THEN 'FILLED' ELSE 'PARTIALLY_FILLED' END`，条件含 `status <> 目标态`，**不 bump 版本**，0 行=自环正常）→ **读回快照（必须在 CAS② 之后）** → 两条事件（`change_type = status == FILLED ? FILLED : FILL_UPDATED` + `EXECUTION_APPLIED`，同 `event_version`）。
- **撤单请求**：`… SET cancel_requested_at=NOW(3), event_version=event_version+1 WHERE order_no=? AND status IN ('ACKNOWLEDGED','PARTIALLY_FILLED') AND cancel_requested_at IS NULL` → 读回 → 事件 `CANCEL_REQUESTED` + `INSERT t_upstream_command(CANCEL, transport=配置值, PENDING)`；0 行三分支：空 → 404 `ORDER_NOT_FOUND`；不可撤 → 400 `ORDER_NOT_CANCELABLE`；已请求过 → **200 幂等成功**返回既有 `cancelRequestedAt` + `upstreamCommandStatus`。
  - **有意设计**：重复撤单**不重新入队**（`uk(order_no, command_type)` 只允许一条 CANCEL）；指令 `FAILED` 后的唯一恢复路径是批次 4 的 `POST /api/upstream/commands/{id}/retry`（同一行置回 `PENDING`、清零计数）。**不用 4xx/5xx 表达指令失败**，失败事实由 `upstreamCommandStatus` + 差异 `UPSTREAM_SUBMIT_FAILED` 表达。
- **撤单确认**：`… SET status='CANCELED', event_version=event_version+1 WHERE status IN ('ACKNOWLEDGED','PARTIALLY_FILLED') AND filled_quantity < quantity` → 读回 → 事件 `CANCELED`。
- **读回骨架**：每次聚合写成功后，同事务内 `OrderMapper.selectSnapshot(orderNo)` 取 `event_version` + 全字段快照用于拼事件与 `dedupe_key`；读回必须同事务（CAS 已持行锁至提交）。**禁止** `SET event_version = LAST_INSERT_ID(event_version + 1)` + `SELECT LAST_INSERT_ID()`（与 `useGeneratedKeys` 共用通道，会被随后插入的 `t_execution`/`t_event_outbox` 覆盖）。

### 3.6 缺口检测与修复（EXEC_GAP）

- **契约**：`exec_seq` 为**订单内从 1 起、期望连续递增**的成交序号（写入接口契约与 README）；上游违约即数据完整性问题。
- **"已收到序号" = 已应用 ∪ 被拒**：`SELECT exec_seq FROM t_execution WHERE order_no=? UNION SELECT exec_seq FROM t_report_event WHERE order_no=? AND report_type='EXECUTION' AND exec_seq IS NOT NULL`（后段走新增索引 `idx_report_exec`）。**只用 `t_execution` 会把被拒成交当缺失，产生永远修不掉的幻影缺口。**
- **驱动集（不按状态过滤）**：`SELECT DISTINCT order_no FROM t_execution WHERE created_at >= NOW(3) - INTERVAL :windowSecond SECOND LIMIT :batch`（走新增索引 `idx_exec_created`）；窗口 `app.gap.scan-window-seconds=300`、批上限 `app.gap.scan-batch-size=500`、节奏 `app.gap.scan-interval-ms=10000`。终态订单同样可被扫到（缺的那笔影响结算）；退出窗口后由已落库的差异继续驱动修复。
- **定义与触发**：`minMissingSeq` = `[1, max_seq]` 内**最小未出现的序号**（下界 1；首笔为 5 时 `minMissingSeq = 1`）；触发 = `(minMissingSeq > min_seen AND max_seq - minMissingSeq >= app.gap.grace-count(3))` **或** `(NOW(3) - MAX(t_report_event.created_at) >= app.gap.grace-seconds(30))`。前者限定"缺口出现在已见区间内部"才快速触发，**起始缺失只走时间条件**（乱序是预期内的）。
- **多洞只报最小**：同时缺 4 与 8 → 只落 `EXEC_GAP:{orderNo}:4`；补拉 `pull(orderNo, fromSeq=4, limit=100)` 返回 `seq >= 4`（含边界）；若补完 8 仍缺则新出现 `…:8`（旧行已 `RESOLVED`）。
- **纯函数**：`GapDetector.minMissingSeq(Collection<Long> receivedSeqs)`、`GapDetector.shouldReport(Collection<Long> receivedSeqs, Instant lastReportAt, Instant now, GapProps p)`。
- **修复（同样至少一次）**：认领 `diff_type='EXEC_GAP' AND status='OPEN' AND next_attempt_at <= NOW(3)`（走新增索引 `idx_diff_type_status`）→ **事务外** `ExecutionReportSource.pull(...)` → 回报复用实时回报同一入口（`source=PULL`，重复由 `t_report_event.dedupe_key` 吸收）→ 空洞消失 `RESOLVED` / 仍在则退避（1s/5s/30s）+ `attempt_count+1` / 达 `repair-max-attempts(3)` → `FAILED` + `exec.gap.repair.exhausted`。端口默认关闭（`app.upstream.execution-source.enabled=false`）时**跳过、不累加**，缺口保持 `OPEN`，由 Gauge 暴露。

### 3.7 投递 relay：三阶段 + 至少一次 + 端口化

- **三阶段**：① **短事务认领**（`(status='PENDING' AND next_retry_at <= NOW(3)) OR (status='IN_PROGRESS' AND claimed_at < NOW(3) - INTERVAL :lease SECOND)` → `SET status='IN_PROGRESS', owner=:owner, claimed_at=NOW(3)` → 提交，行锁立即释放；走 `idx_status_next`）；② **事务外发送**（经端口，有界超时 `app.outbox.send-timeout-ms`，批内单行失败不影响其余）；③ **短事务落结果**（owner 守卫 `WHERE id=? AND owner=:owner AND status='IN_PROGRESS'`）：成功 → `SENT`；失败 → `retry_count+1` + `next_retry_at=NOW(3)+退避` + 退回 `PENDING`；达上限 → `FAILED`（指令侧同时落 `UPSTREAM_SUBMIT_FAILED`）。
- **bean 常驻、调度条件化**：两个 relay 在任何环境都可构造并暴露 `runOnce()`；`@ConditionalOnProperty` **只加在 `*RelayScheduler`**（`app.outbox.relay-enabled`、`app.upstream.command-relay-enabled`），测试中关闭定时、手动 `runOnce()`。`runOnce()` 不标 `@Transactional`。
- **语义 = at-least-once**：租约过期重认领、或"已发出但未标 `SENT` 时崩溃"都会重复发送，这是**接受的行为**。事件重复由读侧幂等消化（`(aggregate_id, event_version)` 严格递增、`execution_id` 主键，批次 4）；指令重复要求上游按报文内幂等标识消化（SUBMIT 带 `clientOrderId`、CANCEL 带 `orderNo` + `exchangeOrderId`）。**不做"发送前二次确认"**这类会引入新竞态的补偿。
- **参数硬约束**：`app.outbox.claim-lease-seconds` **必须 > `app.outbox.scan-batch-size` × `app.outbox.send-timeout-ms`**；默认 **batch=100、timeout=3s、lease=600s**（含安全系数）；**改动 batch 或 timeout 必须重算 lease**。指标 `relay.lease_reclaim_total{channel=event|command}` 度量重复敞口（正常应为 0）。
- **端口化（可离线测试的落地条件）**：`EventPublisher`（`app.outbox.publisher=KAFKA|LOG`，默认 `KAFKA`）与 `UpstreamCommandSender`（`app.upstream.command-transport=LOG|HTTP|GRPC`，默认 `LOG`）由配置单一开关同时决定"装配"与"落库取值"；发送时按**行内** transport 选 sender，若当前装配无对应实现则该行 `FAILED` + `UPSTREAM_SUBMIT_FAILED`，**不静默换通道**；`SENT` = 已交给出站通道，**不代表上游受理**（受理由 `ACKED` 回报驱动）；**两个 relay 及其 handler 只依赖端口，构造参数与字段不得出现 `KafkaTemplate`/`KafkaProducer`**（中间件客户端只允许出现在 `KafkaEventPublisher`）。

### 3.8 幂等命中的响应契约

`POST /api/orders` 与 `POST /api/orders/{orderNo}/cancel` 的幂等命中响应必须带 `upstreamCommandStatus ∈ {PENDING, IN_PROGRESS, SENT, FAILED}`（无指令为 `null`）——幂等命中往往正是"上次没送出去"的场景。

### 3.9 响应边界（三个入口共用）

| 情况 | HTTP | 响应体 / 副作用 |
|---|---|---|
| 报文与字段校验失败（含未知 `reportType`、缺 `exchangeSeq`/`executionId`/`execSeq`/`execQuantity`/`execTime`、`execAmount` 与 `execPrice` 同时缺、枚举非法、数量非正、`LIMIT` 缺价 / `MARKET` 带价） | **400 `INVALID_PARAM`** | `{code, message, fields?}`；**零留痕**（不插报告表、不落差异、不计 `applied/rejected`） |
| 下单幂等命中（参数一致 / 不一致） | **200** / **409 `IDEMPOTENT_REPLAY_CONFLICT`** | 原订单 + `upstreamCommandStatus` / 错误体 |
| 撤单请求：不存在 / 不可撤 / 已请求过 | **404 `ORDER_NOT_FOUND`** / **400 `ORDER_NOT_CANCELABLE`** / **200** | 已请求过时返回 `cancelRequestedAt` + `upstreamCommandStatus`，不再入队 |
| 重复回报 | **200** | `{orderNo, applied:false, diffType:"DUPLICATE_REPORT"}` |
| 业务 / 数据拒绝（矩阵中所有类型） | **200** | `{orderNo, applied:false, diffType}` + 差异 |
| 成功应用 | **200** | `{orderNo, applied:true, filledQuantity, status, …}` |
| 真并发冲突、重试 3 次仍失败 | **503 `RETRY_EXHAUSTED`** | 错误体 + `retry.exhausted` |
| 事件发件箱唯一键冲突（程序缺陷） | **500** | 告警；不进差异体系 |

### 3.10 可观测指标（本批）

- **Counter**（集中 `common/metrics/AppMetrics`；Micrometer 点号名经 Prometheus 惯例渲染为规格名；加导出名断言测试）：`order.created`、`order.idempotent.replay`、`report.applied`、`report.rejected`（tag `diff_type`）、`report.invalid`（tag `reason`）、`exec.dup`、`exec.overfill`、`exec.after.terminal`、`illegal.transition`、`state.transition`、`state.unchanged`、`cancel.request`、`cancel.not.cancelable`、`exec.gap`、`exec.gap.repair.exhausted`、`retry.attempt`、`retry.exhausted`、`relay.lease_reclaim_total`（tag `channel`）、`upstream.command.sent`、`upstream.command.failed`、`event.outbox.sent`。
- **Gauge = 周期采样，绝不按抓取查库**：`upstream.command.pending`、`event.outbox.pending`、`exec.gap.open` —— `MetricsSampler` 每 `app.metrics.sample-interval-ms`（默认 5000）采样三条 `COUNT(*)` 到 `AtomicLong`，Gauge 只读内存；采样 SQL 走 `idx_status_next`（两表）与 `idx_diff_type_status`；采样异常保留旧值 + WARN，不终止调度。
- **调度隔离**：`spring.task.scheduling.pool.size=4`（relay 长批次不得饿死指标采样与缺口扫描）。
- **标签**：仅 `op`、`reason`、`report_type`、`diff_type`、`channel`；禁止高基数标签。

## 四、产出文件

- **回报入口**：`api/ReportController`；`execution/application/ReportDispatcher`。
- **order 上下文**：`domain/{OrderStatus, OrderStatusTransitions, ChangeType, Money, BusinessDateResolver}`；`infrastructure/{entity/OrderDO, mapper/OrderMapper(+XML)}`；`application/{OrderCreationService, OrderReportService(handleAcked/handleRejected), CancelApplicationService(request/confirm)}`；`api/OrderController`。
- **execution 上下文**：`domain/ExecutionReport`；`infrastructure/{entity/{ExecutionDO, ReportEventDO}, mapper/{ExecutionMapper, ReportEventMapper}(+XML)}`；`application/{ExecutionApplicationService(apply), GapScanTask, GapRepairTask}`、`application/gap/{GapDetector, GapProps}`；`application/port/{ExecutionReportSource, UpstreamCommandSender}` + `infrastructure/{NoopExecutionReportSource, LoggingUpstreamCommandSender}`。
- **投递**：`application/port/EventPublisher`；`infrastructure/kafka/KafkaEventPublisher`、`infrastructure/log/LoggingEventPublisher`；`application/{EventWriter, EventOutboxRelay, UpstreamCommandRelay}`（常驻、`runOnce()`）+ `application/schedule/{EventOutboxRelayScheduler, UpstreamCommandRelayScheduler}`；测试支撑 `testsupport/{RecordingPublisher, RecordingSender, RecordingPublisherConfig}`。
- **公共**：`common/{error/ErrorCode, exception/{BusinessException, GlobalExceptionHandler}, api/ErrorResponse, config/{RetryConfig, AppProperties}, metrics/{AppMetrics, MetricsSampler}, util/OrderNoGenerator}`。
- **配置**：`spring.task.scheduling.pool.size=4`；`app.metrics.sample-interval-ms=5000`；`app.outbox.{publisher=KAFKA, send-timeout-ms=3000, claim-lease-seconds=600, scan-batch-size=100, relay-enabled, scan-interval-ms}`；`app.upstream.{command-relay-enabled, command-transport=LOG, execution-source.enabled=false}`；`app.gap.{scan-enabled=true, scan-interval-ms=10000, scan-window-seconds=300, scan-batch-size=500, grace-count=3, grace-seconds=30, repair-max-attempts=3}`；`app.execution-conflict.precheck-enabled=true`。
- **SQL（`sql/V1__init.sql` 增列与索引，配合 `docker compose down -v`）**：投递表 `owner VARCHAR(64)` + `claimed_at DATETIME(3)`（状态集扩为 `{PENDING, IN_PROGRESS, SENT, FAILED}`）；差异表 `attempt_count INT NOT NULL DEFAULT 0` + `next_attempt_at DATETIME(3) NULL`；`t_report_event.exec_seq BIGINT NULL` + `idx_report_exec(order_no, report_type, exec_seq)`；`t_execution.idx_exec_created(created_at, order_no)`；`t_upstream_command.idx_status_next(status, next_retry_at)`；`t_reconcile_diff.idx_diff_type_status(diff_type, status)`。

## 五、必须覆盖的测试

### 5.1 Profile 与通道划分（互不 `include`）

| 档 | profile | 载体 | 关键配置 |
|---|---|---|---|
| T1 纯逻辑 | `local` | 无中间件 | 排除 Kafka 自动配置；`initialization-fail-timeout=-1` |
| T2 真库 | `test` | Testcontainers MySQL | `app.outbox.publisher=LOG`、`command-transport=LOG`、两个 scheduler 关、gap 扫描关、**排除 `KafkaAutoConfiguration`** |
| T3 真库 + 真 broker | `test-kafka` | Testcontainers MySQL + `@EmbeddedKafka` | `publisher=KAFKA`、`bootstrap-servers=${spring.embedded.kafka.brokers}`、两个 scheduler 关、gap 扫描关 |

- 所有 relay/gap 任务在测试里**手动 `runOnce()`**，不依赖 `@Scheduled` 节拍。
- 成交类用例统一走 `OrderFixture.createAndAck(...)`；**禁止对 `SUBMITTED` 灌成交**（另设反向用例断言其落 `ILLEGAL_TRANSITION`）。

### 5.2 T1（纯 JUnit）

迁移矩阵 7×7；`isTerminal`；`change_type` 映射与由快照推导 `FILLED|FILL_UPDATED`；两套 `dedupe_key`（由读回版本拼出）；**0 行分类矩阵逐格断言**（含 `DATA_CONFLICT` 与 `EXEC_OVERFILL` 叠加条件）；撤单 0 行三分支；下单幂等三分支；报文校验（含 LIMIT/MARKET 价规则）；`ReportDispatchTest`（四类型路由，`CANCEL_ACKED → confirm`，未知类型 400）；**`PortAbstractionTest`（反射断言两个 relay 的构造参数与字段不含 `KafkaTemplate`/`KafkaProducer`）**；`Money`/HALF_UP；`business_date`；通道路由表；`GapDetectorTest` 八用例（无缺失 / 内部缺口未满 3 笔 / 累积 3 笔 / 起始缺失仅时间触发 / 乱序补齐 / 多洞取最小 / 仅 1 笔 / 被拒序号计入"已收到"后无缺口）；`MetricsSamplerTest`（多次读 Gauge 不触发采样）；指标名与标签映射表。

### 5.3 T2（`test`，不连 Kafka）

- **`RelayClaimIT` / `RelayAtLeastOnceIT`**（`publisher=LOG` 或 `RecordingPublisher`）：认领后 `IN_PROGRESS` + `owner` 非空；**发送回调里用另一条连接查库能读到 `IN_PROGRESS`**（证明发送不在持锁事务内）；成功 → `SENT`；失败 → 退避回 `PENDING` 且 `retry_count+1`；达上限 → `FAILED` + `UPSTREAM_SUBMIT_FAILED`；**模拟"已发送未标 `SENT` 即崩溃"（行置 `IN_PROGRESS` 且 `claimed_at` 过期）→ `runOnce()` 重发一次、`RecordingPublisher` 记录两次、`relay.lease_reclaim_total` 递增**；租约未过期不重认领；owner 守卫挡旧持有者；**配置校验断言 `lease > batch × send-timeout`**。
- **`CancelAckIT`**：撤单确认走 `confirm()` —— 正常推进 `CANCELED`；`FILLED` 后 → `CANCEL_AFTER_FILL`；`CANCELED`/`EXPIRED`/`REJECTED` 后 → `AFTER_TERMINAL`；`SUBMITTED` → `ILLEGAL_TRANSITION`；空 → `ORDER_NOT_FOUND`。
- **`AfterTerminalMatrixIT`**：矩阵终态行逐格跑真库用例，断言差异类型、`applied=0`、无事件、状态不变。
- **`DataConflictIT`**：订单 A 先占 executionId=E → 订单 B 发同 E：事前检查路径返回 200 + `DATA_CONFLICT`、B 量额未变、明细无新行、差异存在、报告行 `applied=0`；同订单同 executionId → `DUPLICATE_REPORT`；**关掉 `precheck-enabled` 走兜底**：主事务回滚（订单未变）→ `REQUIRES_NEW` 落差异 → 同样 200 无 500、无 `UnexpectedRollbackException`；重复投递同一脏回报 → 差异不重复。
- **`GapScanScopeIT`**：驱动集只含窗口内订单；批上限生效；`idx_exec_created` / `idx_report_exec` / `idx_diff_type_status` 被 `EXPLAIN` 命中（防退化为全表扫）。
- **`GapDetectionRepairIT`**：内部缺口累积 3 笔 → 落 `EXEC_GAP:{orderNo}:4` + `exec.gap`；起始缺失仅时间条件触发；**被拒成交不产生幻影缺口**；Stub 补发（`fromSeq` 含边界）→ `RESOLVED` 且不重复计入；Stub 一直空 → `attempt_count` 至 3 → `FAILED` + `exec.gap.repair.exhausted`；端口关闭 → 保持 `OPEN`、Gauge 反映；多洞补拉后仍缺 → 新行出现、旧行 `RESOLVED`。
- **Gauge 成本专项**：采样间隔设长（60s）→ 期间多次读 Gauge 只采样一次；三条采样 SQL 的 `EXPLAIN` 命中索引；采样抛异常保留旧值且调度继续。
- 其余：撤单请求三分支与并发唯一写入；`applied` 时机（成功=1 无差异 / 被拒同事务已置 0 且有差异 / 重复投递不改写原行 / 异常回滚两者一并消失）；幂等与事务边界（并发同 `(accountId, clientOrderId)` → 200/200 或 200/409，**不出现 500 与 `UnexpectedRollbackException`**；`TransactionRollbackOnlyExperimentTest` 反例）；响应边界（四类非法报文 400 且零留痕）；MARKET（price/amount 双 NULL、成交后 `amount` 仍 NULL 而 `filled_amount` 正确、两条不变量、带价被 400 拒）；业务正确性（已报/拒单各一条事件、**`t_event_outbox.event_version == t_order.event_version`**、事件版本严格递增无空洞、成交并发守恒含 `last_exec_seq` 非空、同 `executionId` 并发只落一笔、撤单确认先到后成交保持 `CANCELED`、每版本每 topic 至多一条事件行、死锁重试成功）。
- **回归用例**：排除 Kafka 自动配置 + `publisher=LOG` 时上下文可加载，且两个 relay bean **存在**、`runOnce()` 可调用。

### 5.4 T3（`test-kafka`，只放 `EventPublishIT`）

**下单 → ACK → 全额成交 → `runOnce()`** → `order-events` **3 条**（v1 `SUBMITTED`、v2 `ACKED`、v3 `FILLED`）、`execution-events` **1 条**（v3），断言 `event_outbox_sent_total` 增长、`event.outbox.pending` 归零；可选用例 B：部分成交 → 到量（4 条 + 2 条）。

## 六、验收

1. `mvn verify` 全绿（T1 + T2 + T3 全部真实执行）。
2. 手工复现八条路径：正常全链路 / MARKET / 超量 `EXEC_OVERFILL` / 错单号 `ORDER_NOT_FOUND` / 非法报文 400 / 并发同幂等键 / 撤单指令 FAILED 后重复撤单 / 跨订单同 `executionId` 冲突。
3. `docker compose down -v && docker compose up -d` 让本批 SQL 变更生效。
4. 单独一次 commit。

## 七、风险与假设

- **`applyExecution` / `deriveStatus` 顺序与守卫不可合并**（违反硬规则①）；CAS② 的 0 行是自环，不得判错。
- **被拒回报必须走正常提交**，差异不得随回滚丢失；凡业务事务会回滚的路径副作用必须 `REQUIRES_NEW`。
- **`GREATEST(COALESCE(last_exec_seq, 0), :seq)`**：`last_exec_seq` 可空，缺 `COALESCE` 会静默失效（T2 有专项断言）。
- **至少一次**是设计接受的行为；不要把重复发送当 bug 追，先看 `relay.lease_reclaim_total` 与租约参数。
- 本批与后续批次的边界：`EXPIRED` 迁移与日终属批次 3；读模型消费、重建、人工 `resolve`/`retry` 入口属批次 4；proto/gRPC 属批次 5。
