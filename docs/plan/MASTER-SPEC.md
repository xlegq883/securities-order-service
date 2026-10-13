<!-- 总指针：本项目唯一的技术事实来源，实施期间只读 -->

> **状态**：已定稿并锁定（2026-10-13）。实施过程中不得随批次改动技术决策；若确需变更，先改 `DECISIONS.md` 并写明理由，再改代码。
> **配套文件**：`DECISIONS.md`（决策索引与理由）、`batch-0.md` … `batch-5.md`（各批执行单）。对话上下文被压缩后，一律以本目录文件为准，不凭记忆改决策。
> **验收命令**：`mvn verify`（T1 纯逻辑单测 + T2 真库原子性与并发 + T3 真 Kafka 语义，三层全部默认执行）。
> **沙盒约束**：编码与静态自检可在受限环境完成，构建与测试必须在本机非沙盒终端执行。

# 证券订单与成交服务（工作经历复刻）实施计划

## 摘要

新建独立仓库 `securities-order-service`，用 Java 17 + Spring Boot 3 + MyBatis + MySQL + Redis + Kafka 实现当前简历「工作经历」的全部能力（10 条），proto/gRPC 放在最后一批。924 版口径作废。交付物只有**代码与测试**。技术基调：状态机是 **DAG**（矩阵表驱动）；聚合写用**带完整守卫、无同语句跨列依赖**的 CAS，**重试在事务之外**；**分支判定只以累加 CAS 的受影响行数为准**；**`t_execution` 只存已应用成交**；**业务拒绝走正常提交**；**跨事务长锁用锁表 + 租约 + owner 守卫（禁用 `GET_LOCK`）**；成交回报用**增量语义**且**金额单一真源**；下单与撤单幂等行为显式化；**时区与切日统一 Asia/Shanghai**；重试用尽有**显式终态 + 指标 + 人工入口**；日终幂等落在**结算结果表**；事件为**每版本每 topic 至多一条**（order 侧统一 `ORDER_STATE_CHANGED` + `change_type`），读模型**表与流一对一驱动**、重建走**暂停消费 + V0 快照协议**、消费端 `MANUAL_IMMEDIATE` + **`commitRecovered` + DLT**；gRPC **走原生 grpc-java**。测试分三层全部默认执行，验收命令统一 `mvn verify`。分 6 批推进，约 3 周。老仓库冻结，只搬底座。

## 实现要点

- **分层与上下文**：`order`、`execution`、`query`、`eod` 四个上下文，按 `api / application / domain / infrastructure` 分层，依赖单向，防腐层只在最外层。
- **订单状态机（DAG，七态）**：`SUBMITTED`、`ACKNOWLEDGED`、`PARTIALLY_FILLED`、`FILLED`、`CANCELED`、`EXPIRED`、`REJECTED`，后四者为终态。

  | from \ to | SUBMITTED | ACKNOWLEDGED | PARTIALLY_FILLED | FILLED | CANCELED | EXPIRED | REJECTED |
  |---|---|---|---|---|---|---|---|
  | SUBMITTED | — 重复受理幂等忽略 | ✓ 已报回报 | ✗ | ✗ | ✗ 未报不可撤 | ✓ 日终仍未受理则失效 | ✓ 拒单回报 |
  | ACKNOWLEDGED | ✗ | — 重复已报幂等忽略 | ✓ 成交回报 | ✓ 一次全额成交 | ✓ 未成交即撤 | ✓ 日终失效 | ✗ 拒单发生在受理阶段 |
  | PARTIALLY_FILLED | ✗ | ✗ | ✓ 继续部分成交：只累加量额、状态不变（自环） | ✓ 累计到量 | ✓ 部分成交后撤余量 | ✓ 日终过期 | ✗ |
  | 四个终态 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ 终态不可逆 |

  迁移表用 `Map<Status, Set<Status>>` 驱动 + `isTerminal()`。
- **写语句五条硬规则**：① 禁止同语句跨列依赖；② 每个聚合写事务只 bump 一次 `event_version`；③ 重试在事务之外且跨 Bean；④ 批量状态变更必须**逐单 CAS**；⑤ 金额同源。
- **锁策略**：跨事务长锁用 `t_distributed_lock`（owner + 租约 + CAS + owner 守卫释放），**禁用 `GET_LOCK`**；事务内短锁用 `SELECT ... FOR UPDATE SKIP LOCKED`。
- **分支判定口径**：分类只以 **CAS①**（累加 UPDATE）的受影响行数为准（1 → 应用；0 → `ILLEGAL_TRANSITION`/`EXEC_AFTER_TERMINAL`/`EXEC_OVERFILL`/并发重试）；**CAS②（状态推导）的 0 行是正常自环**，不计数、不落差异、不重试，仍返回 `applied:true`。
- **事件模型（本次写死）**：
  - **每事务每 topic 至多一条事件**。`order-events` 上每个 `(order_no, event_version)` **恰好一条** `ORDER_STATE_CHANGED`；payload 为聚合快照（`status`、`filled_quantity`、`filled_amount`、`cancel_requested_at`、`event_version`、`occurred_at`）+ **`change_type`** ∈ `{ACKED, REJECTED, CANCEL_REQUESTED, CANCELED, EXPIRED, FILL_UPDATED, FILLED}`（自环部分成交用 `FILL_UPDATED`，累计到量用 `FILLED`；`change_type` 只服务日志/指标/排查，**不参与读侧排序与去重**）。
  - `execution-events` 上每个版本至多一条 `EXECUTION_APPLIED`（仅已应用成交）。
  - **同版本、跨 topic**：一次聚合写事务只 bump 一次 `event_version`，最多产出两条事件行（order + execution），分属两个 topic——这不违反"一事务一版本"，README 需点明。
  - `dedupe_key`：order 侧 `{order_no}:{event_version}:ORDER_STATE_CHANGED`；execution 侧 `{order_no}:{event_version}:EXECUTION_APPLIED:{execution_id}`。
  - **事件发出条件**：聚合任何可观察变化（量额、`status`、`cancel_requested_at`）都发一条 order 事件；被拒成交不改变聚合，不发事件、不 bump 版本。
- **事务边界与副作用持久化**：业务拒绝**不回滚**（报告与差异同事务提交）；非业务异常才回滚（两者一起回滚，靠幂等重投）。**凡业务事务会回滚的路径，副作用记录必须 `REQUIRES_NEW`**（EOD 步骤/批次失败标记、relay 指令失败标记、缺口修复耗尽标记）。
- **成交应用事务顺序**：① 插 `t_report_event`（`dedupe_key` 唯一，重复即抛 → 200 + `applied:false, diffType:"DUPLICATE_REPORT"`）→ ② CAS① 累加（`WHERE order_no=? AND status IN ('ACKNOWLEDGED','PARTIALLY_FILLED') AND filled_quantity + :qty <= quantity`，SET 累加量额、`last_exec_seq = GREATEST(...)`、`event_version = event_version + 1`）→ ③ CAS① 0 行：分类 → 同事务写差异与指标 → 正常提交返回 200 + `{applied:false, diffType}`（不插明细）→ ④ CAS① 1 行：插 `t_execution`（同一对象上的 `exec_amount`）→ CAS② 状态推导 → 写两条事件 → 200 + `{applied:true}`。隔离 `READ_COMMITTED`。
- **明细与去重口径**：`t_execution` 只存已应用成交；被拒成交只进 `t_report_event`（`applied=0`）与差异表；`t_report_event.dedupe_key` 拦截所有回报的一次性处理，`uk_execution_id` 作兜底，差异表 `dedupe_key` 防重复。回报去重键：成交类 `EXECUTION:{order_no}:{execution_id}`；订单回报与撤单确认类 `{report_type}:{order_no}:{exchange_seq}`。
- **金额口径**：`t_execution.exec_amount` 唯一真源，CAS① 累加同一字段值；仅带价格与数量时按 `exec_amount = exec_price × exec_quantity` 精确算一次后落库；对账禁止重算。金额列统一 **DECIMAL(18,4)**；整数数量无需舍入，小数数量 **HALF_UP 取 4 位**。Java 侧 BigDecimal。
- **不变量与对账口径**：`filled_amount = SUM(t_execution.exec_amount)`、`filled_quantity = SUM(t_execution.exec_quantity)`、明细行数 = 已采纳回报数、`status='FILLED' ⟺ filled_quantity = quantity`、终态不可逆；对账任务不加过滤条件。
- **下单幂等**：`uk(account_id, client_order_id)`；参数一致 → 200 返回原订单；不一致 → 409 `IDEMPOTENT_REPLAY_CONFLICT`。
- **撤单**：仅 `ACKNOWLEDGED`/`PARTIALLY_FILLED` 可撤；`SUBMITTED` 与终态返回 `ORDER_NOT_CANCELABLE` 且不写 `cancel_requested_at`；写入 `... SET cancel_requested_at = NOW(), event_version = event_version + 1 WHERE order_no=? AND status IN (...) AND cancel_requested_at IS NULL`（这一事务产出 `change_type=CANCEL_REQUESTED` 的唯一一条 order 事件）；0 行三分支：不可撤 → 错误码；已请求过 → 幂等成功；其余 → 外层重试。不引入"撤单中"状态；该字段进快照与 `t_order_query`；指令超限只落 `UPSTREAM_SUBMIT_FAILED`；日终过期不看撤单请求。
- **时区与切日**：全链路 **Asia/Shanghai**；`business_date` = 下单时刻的上海日期（预留 `TradingCalendarPort`）；EOD 只处理指定 `business_date`；晚到回报归属订单 `business_date`，靠"差异 + 该日新批次号重跑结算"修正。
- **读模型：表与流一对一**：`t_order_query` 只由 `order-events` 驱动（同版本唯一 ⇒ 严格递增应用无歧义、`<= last_event_version` 丢弃）；`t_execution_query` 只由 `execution-events` 驱动（`execution_id` 主键幂等、**无水位线字段**）；跨流无顺序保证只影响两表相对可见性，窗口内短暂不一致由 `query_lag_seconds{stream=...}` 观测、由对账与重建纠正。
- **重试与事务边界**：白名单仅 `DeadlockLoserDataAccessException`、`CannotAcquireLockException`；尝试 3 次、间隔 50ms/200ms；`RetryTemplate` 包住跨 Bean 的事务调用；事务方法内不得 catch 后重试。
- **写路径守卫集合**：已报/拒单回报 `status='SUBMITTED'`；撤单确认 `status IN ('ACKNOWLEDGED','PARTIALLY_FILLED') AND filled_quantity < quantity`；日终过期（逐单）`status IN ('SUBMITTED','ACKNOWLEDGED','PARTIALLY_FILLED') AND filled_quantity < quantity AND business_date = :d`；`last_exec_seq` 只在 SET 里 `GREATEST`。
- **缺口检测与修复**：宽限窗口（3 笔或 30 秒）后落 `EXEC_GAP`；端口 `ExecutionReportSource.pull(orderNo, afterSeq, limit)`（测试 `Stub`、运行时 `Noop` 默认、可选 `Http`）；修复任务最多 3 次、退避 1s/5s/30s、复用实时回报同一入口；默认 `app.upstream.execution-source.enabled=false`。
- **失败兜底与人工入口**：EOD 步骤 `attempt_count` 或接管次数达 3 → `REQUIRES_NEW` 置步骤与批次 `FAILED`、指标 `eod_step_failed_total`/`eod_step_stale_takeover_total`、人工续跑 `POST /api/eod/batch/{batchNo}/resume`；缺口修复用尽 → 差异转 `FAILED`、人工确认 `POST /api/reconcile/diffs/{id}/resolve`；上游指令用尽 → `FAILED` + `UPSTREAM_SUBMIT_FAILED`、人工重投 `POST /api/upstream/commands/{id}/retry`；Kafka 超限进 DLT。
- **日终批处理**：编排层 `t_eod_batch`/`t_eod_batch_step`（`uk(business_date, batch_no)` 只管编排；断点续跑 = 跳过 `SUCCESS`；整批重跑 = 新批次号 + 重置步骤）；步骤用 owner + 租约 + CAS 抢占/接管（`lease_expire_at=NOW()+60s`，每 15 秒续租）。步骤：① `EXPIRE_ORDERS` 逐单 CAS（每单产出唯一一条 `change_type=EXPIRED` 的 order 事件）；② `SETTLE_SUMMARY` 按 `(business_date, account_id, security_code)` 重算并**绝对值覆盖式 upsert**；③ `CHECK_CONSISTENCY` 差异按 `dedupe_key` upsert。
- **消费端**：`enable.auto.commit=false` + `MANUAL_IMMEDIATE` + 单条模式；`DefaultErrorHandler(DeadLetterPublishingRecoverer, ExponentialBackOffWithMaxRetries(3))` + **`setCommitRecovered(true)`**（3.x 再 `setAckAfterHandle(true)`），DLT 用 `NewTopic` 显式创建。
- **读模型重建协议**：① 锁表 CAS 抢占（0 行 → `REBUILD_IN_PROGRESS` 并计 `read_model_rebuild_conflict_total`；每 15 秒 owner 守卫续租 60 秒；结束 owner 守卫释放；崩溃则租约过期由他人接管并计 `rebuild_lock_takeover_total`）；② 优雅停容器并等待在途单条完成；③ 同一 REPEATABLE READ 事务内"按写表重算两张读模型表 + 读 `MAX(event_version)` 得 V0"；④ 覆盖式 upsert 并把 `t_order_query.last_event_version` 写为 V0（明细表不加水位线）；⑤ 恢复消费并释放锁。
- **查询与游标分页**：详情、列表、成交明细；opaque cursor + keyset SQL，`limit` 上限，非法游标报参数错误。
- **gRPC 契约与横切（不走 starter）**：proto3 三文件 + `io.github.ascopes:protobuf-maven-plugin`；grpc-java 原生 + 自建 `GrpcServerLifecycle`（9090，`ServerInterceptor`）；`ProtoMapper` 防腐层；错误码映射 `Status`；拦截器注入 traceId、耗时与错误指标。

## 接口与数据结构

- **表（12 张）**：`t_order`、`t_execution`、`t_report_event`、`t_upstream_command`、`t_event_outbox`、`t_order_query`、`t_execution_query`、`t_reconcile_diff`、`t_eod_batch`、`t_eod_batch_step`、`t_eod_settlement`、`t_distributed_lock`。
- **`t_order`**：`order_no`、`account_id`、`security_code`、`side`、`order_type`、`order_price` DECIMAL(18,4)、`quantity`、`amount` DECIMAL(18,4)、`filled_quantity`、`filled_amount` DECIMAL(18,4)、`last_exec_seq`、`event_version`、`status`、`business_date`、`exchange_order_id`、`cancel_requested_at`、`client_order_id`；`uk_order_no`、`uk(account_id, client_order_id)`、`idx_account_created_id`、`idx_status`。
- **`t_execution`（仅已应用成交）**：`order_no`、`execution_id`、`exec_seq`、`exec_quantity`、`exec_price` DECIMAL(18,4)、`exec_amount` DECIMAL(18,4)、`exec_time`、`business_date`；`uk_execution_id`、`idx_order_seq(order_no, exec_seq)`。
- **`t_event_outbox`**：`aggregate_type`、`aggregate_id`、`event_version`、`event_type`（只有 `ORDER_STATE_CHANGED` 与 `EXECUTION_APPLIED` 两个取值）、`topic`、`message_key`、`payload`（含 `change_type`、`occurred_at`）、`dedupe_key`（唯一）、`status`、`retry_count`、`next_retry_at`；**索引 `uk` 保证同版本同 topic 至多一条**。
- **`t_report_event`**：`report_type`、`dedupe_key`（唯一）、`exchange_order_id`、`exchange_seq`、`execution_id`、`source`、`applied`、`payload`、`created_at`。
- **`t_upstream_command`**：`order_no`、`command_type`、`transport`、`payload`、`status`、`attempt_count`、`next_retry_at`、`last_error`；`uk(order_no, command_type)`。
- **`t_order_query` / `t_execution_query`**：前者含 `status`、`filled_quantity`、`filled_amount`、`cancel_requested_at`、`business_date`、`last_event_version`；后者以 `execution_id` 为主键并含 `exec_amount`、`applied_at`、**无水位线字段**。
- **`t_distributed_lock`**：`lock_name`（PK）、`owner`、`acquired_at`、`heartbeat_at`、`lease_expire_at`；seed 一行 `('read_model_rebuild', NULL, NULL, NULL, NULL)`。
- **`t_eod_settlement`**：`business_date`、`account_id`、`security_code`、`trade_count`、`filled_quantity`、`filled_amount` DECIMAL(18,4)、`settled_at`；`uk(business_date, account_id, security_code)`。
- **`t_eod_batch_step`**：`step_id`、`batch_id`、`step_name`、`status`、`attempt_count`、`owner`、`heartbeat_at`、`lease_expire_at`、`started_at`、`finished_at`、`error_msg`。
- **`t_reconcile_diff`**：`business_date`、`order_no`、`diff_type`、`detail`、`dedupe_key`（唯一）、`status`（`OPEN`/`RESOLVED`/`FAILED`）、`resolved_at`、`created_at`；差异类型 `ILLEGAL_TRANSITION`、`EXEC_AFTER_TERMINAL`、`EXEC_GAP`、`EXEC_OVERFILL`、`CANCEL_AFTER_FILL`、`EXEC_SUM_MISMATCH`、`READ_MODEL_DRIFT`、`UPSTREAM_SUBMIT_FAILED`、`DUPLICATE_REPORT`。
- **回报接口响应**：`POST /api/reports` 统一 200，响应体 `{orderNo, applied, diffType?, filledQuantity, status}`。
- **事件枚举**：`event_type ∈ {ORDER_STATE_CHANGED, EXECUTION_APPLIED}`；`change_type ∈ {ACKED, REJECTED, CANCEL_REQUESTED, CANCELED, EXPIRED, FILL_UPDATED, FILLED}`。
- **REST**：`POST /api/orders`、`POST /api/reports`、`POST /api/orders/{orderNo}/cancel`、`GET /api/orders/{orderNo}`、`GET /api/orders?accountId=&cursor=&limit=`、`GET /api/orders/{orderNo}/executions`、`POST /api/eod/batch?businessDate=`、`POST /api/eod/batch/{batchNo}/resume`、`POST /api/query/rebuild`、`POST /api/reconcile/diffs/{id}/resolve`、`POST /api/upstream/commands/{id}/retry`。
- **gRPC（批次 5）**：`OrderService{CreateOrder, GetOrder, ListOrders, CancelOrder}`、`ExecutionService{ReportExecution, ListExecutions}`；proto3 新增字段兼容，删除字段 `reserved`。
- **错误码**：`ORDER_NOT_FOUND`、`ORDER_NOT_CANCELABLE`、`IDEMPOTENT_REPLAY_CONFLICT`、`DUPLICATE_REPORT`、`INVALID_CURSOR`、`INVALID_PARAM`、`REBUILD_IN_PROGRESS`。
- **指标**：`order_created_total`、`order_idempotent_replay_total`、`report_applied_total`、`report_rejected_total`、`exec_dup_total`、`exec_overfill_total`、`exec_after_terminal_total`、`exec_gap_total`、`exec_gap_open`、`exec_gap_repair_exhausted_total`、`illegal_transition_total`、`state_transition_total`、`state_unchanged_total`、`cancel_request_total`、`cancel_not_cancelable_total`、`retry_attempt_total`、`retry_exhausted_total`、`upstream_command_pending`、`upstream_command_failed_total`、`event_outbox_pending`、`event_outbox_sent_total`、`eod_step_failed_total`、`eod_step_stale_takeover_total`、`consume_fail_total`、`dlt_published_total`、`stale_event_dropped_total`、`read_model_rebuild_total`、`read_model_rebuild_conflict_total`、`rebuild_lock_takeover_total`、`read_model_rebuild_duration_seconds`、`query_lag_seconds{stream=order|execution}`、`consumer_lag_records_max`。

## 批次与测试

| 批次 | 内容 | 天数 |
|---|---|---|
| 0 | 脚手架：仓库/模块/compose（MySQL 3307、Redis、Kafka，`TZ=Asia/Shanghai`）/CI/最小 README + 测试基建（failsafe、Testcontainers、spring-kafka-test、CI 跑 `mvn verify`） | 1 |
| 1 | 状态机、成交累加与状态推导（金额同源、仅采纳落明细、被拒正常提交）、事件写入（每版本每 topic 至多一条）、缺口端口、撤单、下单幂等、上游指令与 relay（能力 1–5） | 4 |
| 2 | 查询与游标分页（能力 6） | 3 |
| 3 | 日终：编排与租约接管、逐单过期、结算 upsert、一致性校验、失败终态（`REQUIRES_NEW`）与人工续跑（能力 7） | 3 |
| 4 | 读模型：表与流一对一、两套去重口径、位点与 DLT、锁表互斥重建五步协议、失败兜底与人工入口（能力 8、9） | 4 |
| 5 | 门禁 G1–G3 → proto/gRPC 契约与横切（能力 10） | 0.5 + 3 |
| 缓冲 | 异常路径补测、README 定稿 | 2 |

**批次 5 门禁（Go/No-Go）**：G1 `mvn -U dependency:get` 拉一个新 grpc 依赖成功；G2 `mvn generate-sources` 成功；G3 最小 in-process server 单测通过。任一失败按降级走（G1→proto 仅作契约 + POJO 防腐层；G2→保留 `.proto`、生成改手工触发；G3→gRPC 服务端推迟），不影响批次 1–4。

### 测试分层（三层全部默认执行）

- **T1 纯逻辑单测**：状态迁移矩阵 7×7、缺口判定与宽限窗口、水位线丢旧判定、CAS① 0 行四分支、CAS② 0 行不入错误分支、撤单 0 行三分支、下单幂等分支、**事件构造规则（每版本每 topic 至多一条；`change_type` 取值正确；order 侧恒为 `ORDER_STATE_CHANGED`）**、两套 `dedupe_key` 规则、重试白名单、金额同源与舍入、切日口径、游标编解码、错误码与 Proto 映射、日终步骤编排。
- **T2 真库原子性与并发（Testcontainers `mysql:8.0`）**：并发同 `execution_id` 只落一笔；同订单 N 笔并发成交后量额与明细行数正确；不变量（`filled_amount == SUM(exec_amount)`、`filled_quantity == SUM(exec_quantity)`、明细行数 = 已采纳回报数）；连续两笔部分成交（CAS② 1 行 / 自环 0 行）均 `applied:true` 且 `t_order_query` 两次都更新；被拒成交 200 + `applied:false` 且明细表无行、报告与差异已提交；非业务异常路径两者一起回滚；并发超量被拒；`CANCEL_AFTER_FILL`；**每版本每 topic 至多一条事件行（撤单、报回报、过期、成交各自断言）**；死锁外层重试成功；反向用例（事务内 catch 重试必抛）；日终换批次号重跑不翻倍、过期逐单生效且每单一条 order 事件、步骤失败标记 `REQUIRES_NEW` 持久化、`resume` 可续跑；差异 `FAILED` 与 `resolve`；`SKIP LOCKED` 不重复；重建互斥（并发只有一个抢到、非持有者不能续租/释放、租约过期可接管）；重建后两张读模型表与写表一致、`last_event_version = V0`。**H2 与 mock mapper 禁止用于本层任何断言。**
- **T3 真 Kafka 语义（EmbeddedKafka）**：重投只应用一次；失败不 ack → 位点不推进；成功才 ack；**order 快照严格递增应用且不丢状态（含 ack/cancel/expire 场景）**；跨流乱序专项（两表最终一致、无永久滞后）；被拒成交不产生事件；DLT 不阻塞分区专项；重建竞态专项；`cancel_requested_at` 正确投影；`query_lag_seconds{stream=...}` 与 `consumer_lag_records_max` 分别上报。
- **执行约定**：`mvn verify` 是唯一验收命令，CI 同样跑它；无"默认跳过"机制；Docker 与本地镜像已具备；Docker 不可用时唯一降级是指向 compose 的 MySQL(3307)，绝不降级为 mock/H2。每批收尾：`mvn verify` 全绿 + 单独 commit。

## 假设与默认

- 仓库名 `securities-order-service`；日终排在读模型之前；REST 与 gRPC 双入口都保留，REST 先行。
- **事件唯一性**：`order-events` 每 `(order_no, event_version)` 恰好一条 `ORDER_STATE_CHANGED`（变化性质放 `change_type`）；`execution-events` 每版本至多一条 `EXECUTION_APPLIED`；同一事务可产出"同版本、跨 topic"两条事件，不违反"一事务一版本"（README 点明）。
- 锁语义：跨事务长锁用 `t_distributed_lock`（owner + 租约 + CAS + owner 守卫释放），禁用 `GET_LOCK`；事务内短锁用 `SKIP LOCKED`。
- 读模型表与流一对一；分支判定只以 CAS① 为准；业务拒绝不回滚；凡业务事务会回滚的路径副作用记录必须 `REQUIRES_NEW`。
- `t_execution` 只存已应用成交；下单幂等按 `uk(account_id, client_order_id)`；金额列统一 DECIMAL(18,4)、`exec_amount` 唯一真源；时区 Asia/Shanghai。
- 撤单语义、两套去重键、消费端配置（`MANUAL_IMMEDIATE` + `setCommitRecovered(true)` + DLT）、重建"暂停消费 + V0 快照"按上文锁定。
- 重试白名单 `DeadlockLoserDataAccessException` / `CannotAcquireLockException`，尝试 3 次、间隔 50ms/200ms；锁租约 60 秒、心跳 15 秒。
- 环境事实：Docker 29.8.0 可用，本地已有 `mysql:8.0`/`redis:7`/`apache/kafka:3.7.0`；本机 3306 被原生 MySQL267 占用，compose 的 MySQL 固定映射 3307；Maven 走阿里云镜像，受限沙盒内网络不通，**构建须在非沙盒终端执行**。
- 批次 5 依赖联网拉取 `io/grpc`、protoc 与生成插件；**不使用 `grpc-spring-boot-starter`**。
- 端口：HTTP 8080、gRPC 9090、MySQL 3307、Redis 6379、Kafka 9092。
- README 保留最小内容（启动方式、模块划分、表清单、状态迁移矩阵、守卫集合、下单与撤单幂等行为表、回报接口响应语义、CAS 判定口径、**事件唯一性与"同版本跨 topic"说明**、`change_type` 取值表、读模型表与流对应关系、锁策略与重建协议、金额同源与舍入、时区与切日、去重键组成、事务边界与副作用持久化、失败兜底与人工入口、指标口径、测试命令）；不产出演示脚本、口径稿、追问卡。
- 老仓库只搬底座（compose 与端口约定、建表与索引风格、Redis 幂等占位、Lua 扣减思路、CI 工作流、`TransactionRollbackOnlyExperimentTest` 反例），业务代码一律重写。
- 不引入 Spring Cloud、分库分表、ES；资金与持仓清算在边界外，仅做可用量校验。

