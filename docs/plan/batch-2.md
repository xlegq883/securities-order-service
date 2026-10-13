# 批次 2 执行单 · 查询与游标分页（3 天）

## 输入

- 总指针与决策索引；批次 0、1 已完成。

## 范围（对应能力 6）

订单详情、订单列表（opaque cursor + keyset 分页）、成交明细查询。

## 本批要定稿的执行层决策

- **数据源切换开关**：`app.query.source=write|read`。批次 2 先查写表（`t_order`、`t_execution`）；批次 4 读模型建成后默认切到 `read`（`t_order_query`、`t_execution_query`），**接口契约不变、只换 mapper**。
- **游标格式**：`base64("{created_at_epoch_ms}|{id}")`，客户端不解析；非法即返回 `INVALID_CURSOR`。
- **分页边界**：`limit` 默认 20、上限 100；多取一条判断 `hasNext`，返回 `nextCursor`。

## 产出文件

- `query/api/OrderQueryController`：`GET /api/orders/{orderNo}`、`GET /api/orders?accountId=&cursor=&limit=`、`GET /api/orders/{orderNo}/executions`。
- `query/application/OrderQueryService` + `query/application/CursorCodec`（编解码与校验）。
- `query/infrastructure/OrderQueryMapper(+XML)`、`ExecutionQueryMapper(+XML)`：keyset SQL `WHERE account_id=? AND (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC LIMIT ?+1`。
- `query/api/dto/{OrderDetailResponse, OrderListResponse, ExecutionListResponse}`。
- `docs/evidence/batch-2-explain.md`：`offset` 深分页与 keyset 的 `EXPLAIN` 与耗时对比（真实数据、可复现命令）。

## 必须覆盖的测试

- **T1**：游标编解码（往返一致、非法输入）、`limit` 上限与默认值、参数校验分支。
- **T2**：keyset 边界（首页 / 末页 / 同一毫秒多条 / 非法游标 / 超限 limit）；`EXPLAIN` 断言命中 `idx_account_created_id`、无全表扫描、无 filesort（对深分页 offset 写法做对照）。

## 验收

1. `mvn verify` 全绿。
2. `docs/evidence/batch-2-explain.md` 中的对比数据可在本机复现。
3. 单独一次 commit。

## 风险

- 同毫秒多条若只按 `created_at` 排序会漏/重，游标必须带 `id` 复合比较。
