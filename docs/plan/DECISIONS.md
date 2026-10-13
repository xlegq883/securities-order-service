# 决策索引（DECISIONS）

> 本文件回答"定了什么、为什么"，规格细节见 [`MASTER-SPEC.md`](MASTER-SPEC.md)。
> 每条均为**已锁定**决策，实施期间不得随批次改动。确需变更时：先在本文件末尾追加"变更记录"（写明理由与影响面），再改代码。

| # | 决策 | 内容 | 为什么 |
|---|---|---|---|
| D1 | 分层与上下文 | `order`/`execution`/`query`/`eod` 四上下文 × `api`/`application`/`domain`/`infrastructure` 四层；依赖单向；防腐层只在最外层 | 限界上下文是这份工作经历的第一条能力；依赖方向错会让领域模型被外部契约污染 |
| D2 | 状态机形态 | 单线七态 **DAG**，迁移用矩阵表驱动 + `isTerminal()`；禁止链式描述 | `ACKNOWLEDGED→FILLED`、`ACKNOWLEDGED→CANCELED`、`PARTIALLY_FILLED→CANCELED/EXPIRED` 都是一步迁移，链式写法会把合法迁移判成非法 |
| D3 | 状态线数量 | 只有 `status` 一条线，不设 `report_status` | 新仓库无历史包袱，状态只由交易所回报驱动；本地提交失败用重试计数与指标表达，不进状态机 |
| D4 | 写语句硬规则 | ① 禁止同语句跨列依赖；② 每事务每聚合只 bump 一次 `event_version`；③ 重试在事务之外且跨 Bean；④ 批量状态变更逐单 CAS；⑤ 金额同源 | ①同语句多列赋值依赖 MySQL 隐式求值顺序，换库即崩；②保证事件版本唯一；③事务内重试已被 rollback-only 标记，必然无效；④集合 UPDATE 会绕过版本与事件；⑤双口径必然对账误报 |
| D5 | 分支判定 | 只以 **CAS①**（累加 UPDATE）受影响行数为准；**CAS② 的 0 行是正常自环** | `PARTIALLY_FILLED→PARTIALLY_FILLED` 时 `status <> 目标态` 恒不成立，把 CAS② 的 0 行当错误会误判正常部分成交 |
| D6 | 成交语义 | **增量**（本笔成交量），累加；`exec_seq` 不作准入门槛 | 累加可交换 ⇒ 乱序、重投、并发都安全；`exec_seq` 只用于缺口观测与补拉 |
| D7 | 明细表口径 | `t_execution` **只存已应用成交**；被拒成交只进 `t_report_event` 与差异表 | 否则 `filled_amount == SUM(exec_amount)` 永不成立、`EXEC_SUM_MISMATCH` 永久误报 |
| D8 | 业务拒绝的持久化 | 被拒回报返回 **200 + `applied:false` + `diffType`**，主事务**不回滚**；凡业务事务会回滚的路径，副作用记录必须 `REQUIRES_NEW` | 差异不能随回滚丢掉；用 4xx 表达"拒绝"会诱导上游重试，制造重复投递 |
| D9 | 事件唯一性 | `order-events` 每 `(order_no, event_version)` **恰好一条** `ORDER_STATE_CHANGED`（性质放 `change_type`）；`execution-events` 每版本至多一条 `EXECUTION_APPLIED`；**同版本跨 topic 允许** | 读侧"严格递增"只有在"每版本唯一"时才无歧义，同版本两条会丢状态；`change_type` 只服务日志/指标，不参与排序去重 |
| D10 | 事件发出条件 | 聚合任何可观察变化（量额 / `status` / `cancel_requested_at`）都发一条 order 事件 | 否则部分成交自环时量额投影不到读模型，`t_order_query.filled_quantity` 会悄悄变旧 |
| D11 | 回报去重键 | 成交类 `EXECUTION:{order_no}:{execution_id}`；订单回报与撤单确认类 `{report_type}:{order_no}:{exchange_seq}` | 不假设"交易所流水号全局唯一"；明细侧另有 `uk_execution_id` 作更强兜底 |
| D12 | 金额口径 | `exec_amount` 为唯一真源、累加同字段值、**对账不重算**；金额列统一 DECIMAL(18,4)；小数数量 HALF_UP 取 4 位 | 整数数量下 `price × qty` 恰好 4 位无需舍入；银行家舍入在 .5 场景会与上游口径不一致，直接制造差异 |
| D13 | 下单幂等 | `uk(account_id, client_order_id)`；参数一致 → **200 返回原订单**；参数不一致 → 409 `IDEMPOTENT_REPLAY_CONFLICT` | 幂等命中不是冲突；唯一性按账户维度才符合业务语义 |
| D14 | 撤单语义 | 仅 `ACKNOWLEDGED`/`PARTIALLY_FILLED` 可撤；`SUBMITTED` 与终态返回 `ORDER_NOT_CANCELABLE` 且**不写** `cancel_requested_at`；重复撤单幂等成功；不引入"撤单中"状态 | 未报不可撤；"撤单中"由 `cancel_requested_at` 推导，少一个状态少一处不一致 |
| D15 | 时区与切日 | 全链路 Asia/Shanghai；`business_date` = 下单时刻上海日期；EOD 只处理指定日；**晚到回报归属原 `business_date`** | 切日错会让结算算错日；晚到回报不能改归属日，靠该日重跑结算修正 |
| D16 | 读模型驱动 | 表与流一对一：`t_order_query` 只由 `order-events` + 水位线驱动；`t_execution_query` 只由 `execution-events` + `execution_id` 驱动且**无水位线** | 两套去重口径混在一张表上必然漂移；跨流乱序只应造成窗口内短暂不一致 |
| D17 | 重建协议 | **暂停消费 + V0 快照**五步；互斥用 `t_distributed_lock`（owner + 租约 + CAS + owner 守卫释放），**禁用 `GET_LOCK`** | 重算数据与水位线必须同源，否则重建期间新事件被覆盖或丢失；`GET_LOCK` 是连接级、连接池下会提前失效或错乱 |
| D18 | 消费错误处理 | `enable.auto.commit=false` + `MANUAL_IMMEDIATE` + 单条模式；`setCommitRecovered(true)`（3.x 再加 `setAckAfterHandle(true)`）+ DLT | 不设 `commitRecovered` 则超限后位点不推进、分区仍被卡死；"不阻塞分区"以 T3 用例为准 |
| D19 | 重试边界 | 白名单仅 `DeadlockLoserDataAccessException`/`CannotAcquireLockException`；尝试 3 次、间隔 50ms/200ms；`RetryTemplate` 跨 Bean 包住事务调用 | 事务内 catch 再重试必然抛 `UnexpectedRollbackException`（老仓库 `TransactionRollbackOnlyExperimentTest` 即反例） |
| D20 | 日终幂等 | 锚点在 `t_eod_settlement.uk(business_date, account_id, security_code)` 与差异表 `dedupe_key`；批次表只管编排；结算**绝对值覆盖、禁止累加** | 换批次号重跑不能翻倍——幂等必须落在被计算的数据上，而不是批次表 |
| D21 | 失败终态 | 重试用尽一律显式终态 + 指标 + 人工入口（`resume`/`resolve`/`retry`）+ DLT | 不允许静默无限重试；没有人工路径的"失败终态"等于卡死 |
| D22 | 测试策略 | 三层全部默认执行，唯一验收命令 `mvn verify`；**H2 与 mock mapper 禁止**用于并发/Kafka 语义断言 | 原子性与消费语义是纯单测测不出来的，默认跳过等于没测 |
| D23 | 依赖与契约 | 原生 grpc-java（**不用** `grpc-spring-boot-starter`）+ `io.github.ascopes:protobuf-maven-plugin`；proto3 | starter 与 Boot 3 的 javax/jakarta 版本矩阵是已知雷区；ascopes 插件自带 protoc 下载、免 os-maven-plugin |
| D24 | 锁语义总则 | 跨事务长锁用锁表（owner + 租约 + CAS）；事务内短锁用 `SELECT ... FOR UPDATE SKIP LOCKED` | 长锁不能用连接级锁；短锁随事务释放、与连接归还无关 |

## 变更记录

> 规则：**MASTER-SPEC.md 冻结不改**，所有覆盖性变更记录在此；实施与验收以"MASTER-SPEC + 本记录"为准。

### 变更 1 · 2026-10-13 · Redis 移出技术栈

- **原决定**（见 MASTER-SPEC 摘要、端口与"老仓库只搬底座"）：技术栈写作 `MySQL + Redis + Kafka`，端口含 `Redis 6379`，底座搬运清单含"Redis 幂等占位、Lua 扣减思路"。
- **新决定**：**不引入 Redis** —— pom 不加 `spring-boot-starter-data-redis`，compose 不启 `redis:7`，端口 6379 释放；幂等继续由数据库唯一键承担，分布式互斥由 `t_distributed_lock`（owner + 租约 + CAS）承担。
- **理由**：整套设计（订单受理、回报处理、读写模型、日终、重建）没有任何 Redis 使用点；为凑技术栈塞进一个无规格约束的缓存/锁层，只会增加不一致面与验证成本。
- **影响面**：批次 0（pom、compose、端口约定、README 与 `batch-0.md`）；批次 1–5 的实现与测试不受影响。
- **未采纳的替代**：用 Redis 做"回报幂等快速拦截层"（与老仓库口径一致）—— 需新增一条决策并让 T2 依赖真 Redis，收益仅是可讲的性能叙事，当前非必要。

### 变更 2 · 2026-10-13 · 构建与测试的执行方式

- **原决定**（见 MASTER-SPEC 假设与默认）："构建须在非沙盒终端执行"，即由使用者在本机终端跑 `mvn verify` 并回贴失败日志。
- **新决定**：**由实施者在会话内以提升权限直接执行** `docker compose up -d` 与 `mvn -B -Dfile.encoding=UTF-8 verify`，自行读取日志并修复；使用者只在权限批准时需要点一次。
- **理由**：实测该机器在提权后网络与 Docker 均可用（`maven.aliyun.com`/`repo.maven.apache.org` 均 HTTP 200；Docker 29.8.0 可连，本地已有 `mysql:8.0`/`redis:7`/`apache/kafka:3.7.0`）。沙盒内的失败来自网络禁用与 `user.home` 被改写（Maven 解析成 `C:\.m2\repository`），并非机器不可用。
- **影响面**：批次 0–5 的验证环路（反馈闭环显著加快）；**不改变任何技术决策**。
- **保留的例外**：改动 `sql/V1__init.sql` 后需 `docker compose down -v` 重建卷才生效（entrypoint 脚本只在数据目录为空时执行）。

## 执行层待定项（不属技术决策，由各批执行单定稿）

- 包结构目录形态（批次 0 执行单定稿：上下文在前、层在后）。
- compose 细节、CI 写法、failsafe 命名约定（批次 0 执行单定稿）。
- 游标编码格式、列表过滤参数集合、返回 DTO 形态（批次 2 执行单定稿）。
