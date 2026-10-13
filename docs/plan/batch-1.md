# 批次 1 执行单 · 订单与成交核心（4 天）

## 输入

- 总指针与决策索引；批次 0 已完成（骨架、12 张表、测试基建可用）。

## 范围（对应工作经历能力 1–5）

1. 状态机（七态 DAG、迁移矩阵、终态守卫）
2. 成交累加与状态推导（金额同源、仅采纳落明细、被拒正常提交）
3. 事件写入（每版本每 topic 至多一条）
4. 回报统一入口与去重键
5. 撤单、下单幂等、上游指令与 relay

## 产出文件

- **domain**（`order/domain`、`execution/domain`）：`OrderStatus`、`ChangeType`、`OrderStatusTransitions`（矩阵 + `isTerminal()`）、`ReportType`、`DiffType`；领域异常 `IllegalTransitionException`。
- **infrastructure**：实体 `OrderDO`、`ExecutionDO`、`ReportEventDO`、`UpstreamCommandDO`、`EventOutboxDO` + 对应 Mapper 与 XML。`OrderMapper` 至少含：`applyExecution`（CAS①，含状态守卫与容量守卫、`GREATEST`、`event_version+1`）、`deriveStatus`（CAS②，`CASE` 推导 + `status <> 目标态`）、`markAcked`、`markRejected`、`requestCancel`、`expireOne`、`insertOrder`、`selectByOrderNo`、`selectByClientOrderId`；`EventOutboxMapper` 含 `selectPending`（`SKIP LOCKED`）、`markSent`、`markFailed`；`ExecutionMapper` 含 `insert`（`uk_execution_id`）。
- **application**：`OrderCreationService`（下单幂等三分支）、`OrderReportService`（已报/拒单）、`ExecutionApplicationService`（CAS① → 0 行分类 / 1 行插明细 → CAS② → 事件）、`CancelApplicationService`（撤单四分支）、`EventWriter`（order 快照事件与 execution 事件，含两套 `dedupe_key`）、`ports/ExecutionReportSource` + `NoopExecutionReportSource` + 测试用 `StubExecutionReportSource`。
- **application/task**：`EventOutboxRelay`、`UpstreamCommandRelay`（`SKIP LOCKED` 取批、退避、超限 `FAILED` + `UPSTREAM_SUBMIT_FAILED`）。
- **common**：`ErrorCode`（7 个码）、`BusinessException`、`GlobalExceptionHandler`、`RetryTemplate` 配置 + 外层重试壳 `OrderCommandRetry`（跨 Bean 调用事务方法）。
- **api**：`OrderController`（`POST /api/orders`）、`ReportController`（`POST /api/reports`，统一 200 + `{orderNo, applied, diffType?, filledQuantity, status}`）、`OrderCancelController`（`POST /api/orders/{orderNo}/cancel`）。

## 必须覆盖的测试

- **T1**：迁移矩阵 7×7 全组合；`change_type` 取值；两套 `dedupe_key` 生成；CAS 三分支纯逻辑（含 CAS② 0 行不判错）；撤单 0 行三分支；下单幂等三分支；金额同源与舍入；`business_date` 取上海日期；重试异常白名单。
- **T2**：并发同 `execution_id` 只落一笔；同订单 N 笔并发成交后量额与明细行数正确；不变量 `filled_amount == SUM(exec_amount)`、`filled_quantity == SUM(exec_quantity)`；连续两笔部分成交（CAS② 1 行 + 自环 0 行）；被拒成交 200 + `applied:false` 且明细表无行、报告与差异已提交；非业务异常路径两者一起回滚；并发超量被拒；撤单四分支与并发唯一写入；下单幂等并发唯一；**每版本每 topic 至多一条事件行**；死锁/锁超时下外层重试成功且无 `UnexpectedRollbackException`；反向用例（事务内 catch 重试必抛）。
- **T3 smoke**：成交事件投递到 `execution-events` 与 `order-events` 且 `dedupe_key` 唯一（本批尚无消费方，只验证生产端）。

## 验收

1. `mvn verify` 全绿（T1 + T2 + T3 全部真实执行）。
2. 手工可复现：一单 → 已报 → 部分成交 → 到量 `FILLED`；超量回报返回 `applied:false` + `EXEC_OVERFILL`。
3. 单独一次 commit。

## 风险

- `applyExecution` / `deriveStatus` 两条语句顺序与守卫不可图省事合并（违反硬规则①）。
- 被拒回报的"正常提交"路径要确保差异确实提交（不得抛业务异常）。
