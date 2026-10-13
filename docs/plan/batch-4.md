# 批次 4 执行单 · 读模型与重建（4 天）

## 输入

- 总指针与决策索引；批次 0、1 已完成；批次 2（查询）已存在（本批切换其数据源）。

## 范围（对应能力 8、9）

两条事件流的消费、两套去重口径、手动位点与 DLT、重建五步协议、对账与失败兜底人工入口。

## 产出文件

- `query/infrastructure/consumer/OrderEventConsumer`：`order-events`（key = `order_no`），水位线丢旧（`version <= last_event_version` 丢弃并计 `stale_event_dropped_total`），成功才 `acknowledge()`。
- `query/infrastructure/consumer/ExecutionEventConsumer`：`execution-events`，按 `execution_id` 幂等插入 `t_execution_query`（无水位线）。
- `query/infrastructure/config/KafkaConsumerConfig`：`enable.auto.commit=false`、`AckMode.MANUAL_IMMEDIATE`、单条模式、`DefaultErrorHandler(DeadLetterPublishingRecoverer, ExponentialBackOffWithMaxRetries(3))` + `setCommitRecovered(true)` + `setAckAfterHandle(true)`；`NewTopic` 显式创建 `<topic>.DLT`。
- `query/application/ReadModelRebuildService`：**五步协议** —— ① `t_distributed_lock` CAS 抢占（0 行 → `REBUILD_IN_PROGRESS` + `read_model_rebuild_conflict_total`；15s 心跳续租 60s；owner 守卫释放）；② 优雅停容器并等待在途单条完成；③ 同一 REPEATABLE READ 事务内重算两张读模型表并读 `MAX(event_version)` 得 V0；④ 覆盖式 upsert 并把 `t_order_query.last_event_version` 写为 V0；⑤ 恢复消费并释放锁。
- `query/application/ReadModelReconcileTask`：写读比对 → `READ_MODEL_DRIFT`；成交汇总比对 → `EXEC_SUM_MISMATCH`。
- `query/api/QueryAdminController`：`POST /api/query/rebuild`、`POST /api/reconcile/diffs/{id}/resolve`、`POST /api/upstream/commands/{id}/retry`。
- 查询数据源切换到读模型表（`app.query.source=read` 默认）。
- 指标：`query_lag_seconds{stream=order|execution}`（事件 `occurred_at` → `applied_at` 的时间差）与 `consumer_lag_records_max`（位点落后，独立口径）。

## 必须覆盖的测试

- **T2**：锁表互斥（并发第二个返回 `REBUILD_IN_PROGRESS`）、非持有者不能续租/释放、租约过期可接管并计 `rebuild_lock_takeover_total`；重建后两张读模型表与写表一致、`last_event_version = V0`、明细表无水位线字段。
- **T3**：事件重投只应用一次；处理失败不 ack → 位点不推进 → 重启后重投；`order-events` 严格递增且不丢状态（含 ack/cancel/expire 场景）；两条流交叉乱序后两表最终与写表一致、无永久滞后；**DLT 不阻塞分区专项**（毒消息重试 3 次 → DLT，断言 DLT 收到、位点推进到毒消息之后、同分区后续消息仍被应用）；**重建竞态专项**（一边灌成交一边重建，重建期间读模型不变、恢复后 `>V0` 应用、`<=V0` 丢弃，最终一致）；`cancel_requested_at` 正确投影。

## 验收

1. `mvn verify` 全绿。
2. 手工可复现：改坏若干读模型行 → 调 `POST /api/query/rebuild` → 数据恢复且返回 V0。
3. 单独一次 commit。

## 风险

- `commitRecovered` 未生效则分区仍被卡死，"不阻塞分区"以 T3 用例为准；不通过即切显式 DLT + `acknowledge()` 路径。
- 重建期间必须真正停止消费（不是暂停位点提交），否则快照会被覆盖。
