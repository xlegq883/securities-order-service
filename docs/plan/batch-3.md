# 批次 3 执行单 · 日终批处理（3 天）

## 输入

- 总指针与决策索引；批次 0、1 已完成（批次 2 可并行或后置）。

## 范围（对应能力 7）

批次编排与断点续跑、三个幂等步骤、失败终态与人工续跑。

## 产出文件

- `eod/application/EodBatchService`：创建批次（`uk(business_date, batch_no)`）、`resume(batchNo)`（跳过 `SUCCESS` 步骤）、整批重跑 = 新批次号。
- `eod/application/EodStepRunner`：步骤抢占/接管 CAS（`status IN ('PENDING','FAILED') OR (status='RUNNING' AND lease_expire_at < NOW())`）、`owner` + `heartbeat_at` + `lease_expire_at`（60s 租约 / 15s 心跳）、`attempt_count` 达 3 → **`REQUIRES_NEW`** 置步骤与批次 `FAILED`。
- `eod/application/steps/ExpireOrdersStep`：**逐单 CAS**（`status IN ('SUBMITTED','ACKNOWLEDGED','PARTIALLY_FILLED') AND filled_quantity < quantity AND business_date = :d`），每单产出唯一一条 `change_type=EXPIRED` 的 order 事件。
- `eod/application/steps/SettleSummaryStep`：按 `(business_date, account_id, security_code)` 重算并**绝对值覆盖式 upsert** 到 `t_eod_settlement`（禁止 `+=`）。
- `eod/application/steps/CheckConsistencyStep`：`filled_amount != SUM(exec_amount)` 或 `filled_quantity != SUM(exec_quantity)` → 差异按 `dedupe_key` upsert。
- `eod/api/EodController`：`POST /api/eod/batch?businessDate=`、`POST /api/eod/batch/{batchNo}/resume`。
- `t_eod_batch` / `t_eod_batch_step` 的 Mapper 与 XML。

## 必须覆盖的测试

- **T1**：批次与步骤编排（续跑跳过 `SUCCESS`）、幂等键生成、`EXPIRE_ORDERS` 守卫条件、结算绝对值语义。
- **T2**：**换批次号整批重跑后结算行数与数值不变**；`upsert` 连跑两次不翻倍；过期逐单生效且每单一条 order 事件；`attempt_count` 达 3 置 `FAILED` 且 **`REQUIRES_NEW` 使失败标记在业务回滚后仍持久化**；租约未过期不接管 / 过期被 CAS 接管；`resume` 可续跑；差异表重跑不产生重复行。

## 验收

1. `mvn verify` 全绿。
2. 手工可复现：指定 `businessDate` 跑一次 → 再换批次号跑一次 → `t_eod_settlement` 数值不变。
3. 单独一次 commit。

## 风险

- 日终过期若写成集合 `UPDATE` 会绕过版本与事件（违反硬规则④），必须逐单。
- 结算维度若不足（后续需要更细粒度）不得靠加冗余列解决，须先改决策索引。
