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

### 变更 3 · 2026-10-13 · 批次 0 实现期两处落地修正

**覆盖 1 · Testcontainers 版本 1.19.7 → 1.21.4**

- **原决定**：T2 依赖 `org.testcontainers:mysql` + `junit-jupiter`，版本由 `spring-boot-starter-parent:3.2.5` 统一管理（1.19.7），不额外 import BOM。
- **新决定**：在 `pom.xml` 覆盖 `<testcontainers.version>1.21.4</testcontainers.version>`；**仍复用 Boot 的 `testcontainers-bom`**，只改版本属性，不新增 BOM 来源。
- **理由**：1.19.7 内置的 docker-java 与本机 Docker Engine **29.8.0** 无法通信，T2 报 `Could not find a valid Docker environment` / `Status 400`；覆盖为 1.21.4 后 `SchemaIT` 6 个用例全绿，且无需设置 `DOCKER_HOST`。
- **影响面**：仅 `pom.xml` 属性与 T2 测试；T1/T3 与批次 1–5 业务实现不受影响。
- **未采纳的替代**：为本机开启 Docker TCP `2375` 或手工设 `DOCKER_HOST` —— 把环境依赖外置到个人设置，换机即坏，且不解决 1.19.7 对新引擎的兼容性。

**覆盖 2 · 12 表 DDL 增量列**

- **原决定**：建表字段与唯一键以 MASTER-SPEC 的列清单为准（部分表未列代理主键与 `created_at`）。
- **新决定**：
  1. 各写表（`t_order`、`t_execution`、`t_report_event`、`t_upstream_command`、`t_event_outbox`、`t_reconcile_diff`、`t_eod_batch`、`t_eod_settlement`）增加代理主键 `id BIGINT UNSIGNED AUTO_INCREMENT`；
  2. 写表统一增加 `created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)`；
  3. `t_order_query` 增加 `created_at` 与 `idx_account_created(account_id, created_at, order_no)`；
  4. `t_order` 的 `id` 兼作 keyset 分页排序键（对应 `idx_account_created_id`）。
- **理由**：`t_order` 的 `idx_account_created_id` 与批次 2 游标 `(created_at, id)` 本就隐含这两列必须存在；`DATETIME(3)` 毫秒精度是游标 `created_at_epoch_ms` "不丢同毫秒行"的前提；其余写表加代理主键与 `created_at` 是低风险的通用惯例，便于排查与后续 keyset。
- **保留不变**：`t_order_query` PK `order_no`、`t_execution_query` PK `execution_id`、`t_distributed_lock` PK `lock_name`、`t_eod_batch_step` PK `step_id`；全部唯一键命名严格照规格。
- **影响面**：`sql/V1__init.sql`；批次 2 的游标实现；批次 4 读模型 keyset。

### 变更 4 · 2026-10-13 · `change_type` 扩展 `SUBMITTED`

- **原决定**（见 MASTER-SPEC 事件枚举）：`change_type ∈ {ACKED, REJECTED, CANCEL_REQUESTED, CANCELED, EXPIRED, FILL_UPDATED, FILLED}`。
- **新决定**：新增 `SUBMITTED`；下单事务把 `event_version` 置 1，并发出该版本唯一的 `ORDER_STATE_CHANGED`（快照 `status=SUBMITTED`）。
- **理由**：原枚举缺"创建"一档，读模型没有初始行 —— "已受理但尚未收到回报"的单在查询侧不可见，直到第一次回报事件才出现，属结构性缺口。
- **影响面**：批次 1 的下单创建与事件写入；批次 4 读模型的初始投影。
- **未采纳的替代**：下单不发事件、靠重建补齐（会让新单在窗口内查不到）；查询侧 read-through 回填（查询路径多一段兼容逻辑且与"表与流一对一"冲突）。

### 变更 5 · 2026-10-13 · 差异类型与错误码扩展，以及响应边界口径

- **原决定**：差异类型 9 个取值（`ILLEGAL_TRANSITION`/`EXEC_AFTER_TERMINAL`/`EXEC_GAP`/`EXEC_OVERFILL`/`CANCEL_AFTER_FILL`/`EXEC_SUM_MISMATCH`/`READ_MODEL_DRIFT`/`UPSTREAM_SUBMIT_FAILED`/`DUPLICATE_REPORT`）；错误码 7 个；`POST /api/reports` 统一 200（未区分报文非法）。
- **新决定**：
  1. 差异类型新增 `ORDER_NOT_FOUND`（回读为空）、`AFTER_TERMINAL`（非成交回报晚于终态）、`DATA_CONFLICT`（同 `executionId` 落到不同订单）；
  2. 错误码新增 `RETRY_EXHAUSTED`（503，真并发冲突重试 3 次仍失败）；
  3. **响应边界写死**：报文/字段校验失败 → **400 `INVALID_PARAM` 且零留痕**（不插报告表、不落差异、不计 `applied/rejected`）；业务与数据拒绝 → **200 + `applied:false` + `diffType`**；事件发件箱唯一键冲突（程序缺陷）→ **500 且不进差异体系**；撤单请求的"订单不存在"→ **404**，而回报管道里的"订单不存在"→ 差异 `ORDER_NOT_FOUND`（同一语义两种出口，因一个是客户端动作、一个是上游事实）。
- **理由**：原分类里"订单不存在"没有归属，会掉进无意义的重试（退避三轮、污染日志指标）；"晚于终态"只有部分情形被命名；用 4xx 表达业务拒绝会诱导上游重投，制造重复投递。
- **影响面**：批次 1 的 0 行分类矩阵、差异表使用、错误码表；批次 4 的消费失败兜底沿用同一错误码体系。

### 变更 6 · 2026-10-13 · 投递/差异表的认领字段、缺口扫描索引与投递语义

- **原决定**：投递表 `status ∈ {PENDING, SENT, FAILED}`；差异表无重试字段；缺口扫描未规定范围与索引；投递语义未声明。
- **新决定**：
  1. 投递表状态扩为 `{PENDING, IN_PROGRESS, SENT, FAILED}`，并补 `owner VARCHAR(64)`、`claimed_at DATETIME(3)` —— 支撑"短事务认领 → 事务外发送 → 短事务落结果（owner 守卫）"与租约回收；
  2. 差异表补 `attempt_count INT NOT NULL DEFAULT 0`、`next_attempt_at DATETIME(3) NULL`；
  3. 补索引与列：`t_report_event.exec_seq BIGINT NULL` + `idx_report_exec(order_no, report_type, exec_seq)`；`t_execution.idx_exec_created(created_at, order_no)`；`t_upstream_command.idx_status_next(status, next_retry_at)`；`t_reconcile_diff.idx_diff_type_status(diff_type, status)`；
  4. 投递语义 = **at-least-once**（租约过期重认领、或"已发出但未标 `SENT` 时崩溃"都会重复发送）；事件重复由读侧幂等消化，指令重复由上游按报文内幂等标识（`clientOrderId` / `orderNo` + `exchangeOrderId`）消化；
  5. 发送与外部调用**一律在事务之外**；对外通道经端口（`EventPublisher` / `UpstreamCommandSender`），**中间件客户端只允许出现在其实现类中**（两个 relay 不得直接依赖 `KafkaTemplate`）。
- **理由**：①发送若在持锁事务内，等于"长事务包着网络调用"；②缺口判定若只用 `t_execution`，被拒成交会被当成缺失序号，产生永远修不掉的幻影缺口；③`t_upstream_command` 每秒按 `status` 认领却没有索引 = 每秒全表扫；④"至少一次"必须显式声明，否则重复会被当成 bug 追。
- **影响面**：`sql/V1__init.sql`（配合 `docker compose down -v`）；批次 1 的 relay、缺口检测/修复、差异落库；批次 3 的差异兜底；批次 4 的消费幂等前提（版本号与 `execution_id` 去重）。
- **未采纳的替代**：把被拒成交的序号写进 `t_execution` 以避免幻影缺口（违反 D7 与对账等式）；用 `GET_LOCK` 做认领互斥（连接级锁在连接池下失效，见 D17）。

### 变更 7 · 2026-10-13 · 批次 1 实现期六处落地修正

**覆盖 1 · 投递组件落在 `order` 上下文**
- **原决定**（batch-1 §四）：`EventWriter`/`EventOutboxRelay`/`UpstreamCommandRelay` 与 `application/port/{EventPublisher, UpstreamCommandSender}`、`LoggingUpstreamCommandSender` 归到 execution 侧。
- **新决定**：投递（两个 relay、`EventWriter`、两个端口、`Kafka/Logging` 出站、`t_event_outbox`/`t_upstream_command` 的 DO 与 Mapper）全部落在 **order** 上下文；execution 只保留 `ExecutionReportSource` 端口与 Noop。
- **理由**：execution 已经依赖 order（回报分发调用 order 的处理器、CAS、EventWriter）；若投递端口按原清单放在 execution，order 的下单/撤单路径写指令行就得反向依赖 execution，形成上下文环，违反 D1"依赖单向"。投递方向固定为 execution → order。

**覆盖 2 · 写路径隔离级别固定 `READ_COMMITTED`**
- **原决定**：仅规定成交应用事务隔离 `READ_COMMITTED`（MASTER-SPEC），未规定下单等路径。
- **新决定**：数据源默认隔离级别整体设为 `READ_COMMITTED`（`spring.datasource.hikari.transaction-isolation`），写路径全部适用。
- **理由**：MySQL 默认 `REPEATABLE READ` 下，下单冲突后的"回查区分两个唯一键"读到的是事务开始时的旧快照 → 看不到并发已提交的同幂等键订单 → 误判为订单号碰撞，重试 3 次后返回 503（T2 并发下单用例实测复现）。`READ_COMMITTED` 让每条语句取最新已提交快照，回查才成立。

**覆盖 3 · 退避时间由数据库时钟计算**
- **原决定**：退避写 `next_retry_at`/`next_attempt_at`，由应用计算时间戳。
- **新决定**：投递与缺口修复的退避一律用 `DATE_ADD(NOW(3), INTERVAL ? ...)`（数据库时钟）；插入 PENDING 行时 `next_retry_at` 置 NULL（认领 SQL 已容忍 NULL 表示"立即可认领"）。
- **理由**：应用墙钟与数据库时钟/时区不一致时，`next_retry_at` 会落在将来或过去（Testcontainers 默认 UTC 库 + 上海应用实测导致认领永远命中不到）。改用数据库时钟后与库时区解耦。

**覆盖 4 · 重试白名单新增 `RetryableDispatchException`，且非白名单耗尽不映射 503**
- **原决定**：白名单仅 `DeadlockLoserDataAccessException`/`CannotAcquireLockException`。
- **新决定**：白名单增加应用级 `RetryableDispatchException`（0 行分类矩阵兜底分支，batch-1 §3.4）；`RetryTemplate` 的 recovery 回调只在"最后异常属白名单"时映射 `RETRY_EXHAUSTED`，否则原样抛出。
- **理由**：§3.4 要求矩阵未覆盖时"外层重试 ≤3 次、耗尽 → 503"，必须有可重试的异常类型；而 `RetryTemplate` 对"不可重试异常"同样会调用 recovery 回调，若不区分会把业务异常误映射成 503。

**覆盖 5 · 测试库时区与 compose 对齐**
- **新决定**：`AbstractMySqlIT` 给 Testcontainers MySQL 传 `--default-time-zone=+08:00`（含字符集参数）。
- **理由**：Testcontainers 默认库时区为 UTC，`NOW(3)` 与上海应用时钟相差 8 小时，使时间相关的认领/缺口判定失真（覆盖 2/3 的根因之一）。测试环境必须与 compose 同为 Asia/Shanghai。

**覆盖 6 · 指令投递参数显式化**
- **新决定**：`app.upstream` 增 `scan-interval-ms=1000`、`batch-size=100`、`send-timeout-ms=3000`、`claim-lease-seconds=600`（与 outbox 同构，含 `lease > batch × timeout` 启动校验）；另增 `app.metrics.sample-interval-ms=5000`、`app.execution-conflict.precheck-enabled=true`。
- **理由**：指令投递与事件投递同为三阶段/至少一次，需同等的批大小、超时与租约参数与校验，复用 outbox 配置会掩盖两者的独立调参需求。

**覆盖 7 · T2/T3 共用单例 MySQL 容器 + profile 下放**
- **新决定**：`AbstractMySqlIT` 不再用 `@Testcontainers`/`@Container`，改为静态初始化里 `MYSQL.start()` 一次、JVM 退出时停止的**进程内单例**容器（仍保留 `@ServiceConnection`）；`@ActiveProfiles` 从基类下放到各测试类（T2 = `test`，T3 = `test-kafka`）。
- **理由**：`@Container` 静态字段在基类时，所有子类共用同一容器对象，而 `@Testcontainers` 在**每个测试类结束时停止**它；Spring 上下文缓存又可能在下一个类复用旧 DataSource → 后续用例连到已停容器（实测 `CannotCreateTransactionException`）。另外 `@ActiveProfiles` 是**合并**语义，基类声明 `test` 会与子类 `test-kafka` 叠加，使 T3 也被排除 Kafka 自动配置。共享单例容器还要求用例对跨类数据自隔离（relay/事件用例在 `@BeforeEach` 清空投递表；其余断言按订单维度）。

## 执行层待定项（不属技术决策，由各批执行单定稿）

- 包结构目录形态（批次 0 执行单定稿：上下文在前、层在后）。
- compose 细节、CI 写法、failsafe 命名约定（批次 0 执行单定稿）。
- Mapper 约定（批次 0 定稿）：接口放 `<context>.infrastructure.mapper`，XML 放 `src/main/resources/mapper/<context>/`，`mapper-locations=classpath*:mapper/**/*.xml`；事件 topic 名走代码常量 `EventTopics`，不做配置化。
- 投递组件归属（批次 1 定稿）：事件 outbox、上游指令与两个 relay、`EventPublisher`/`UpstreamCommandSender` 端口及其实现落在 `order` 上下文（见变更 7 覆盖 1，避免 execution↔order 成环）。
- 测试档案约定（批次 1 定稿）：T1 = `local`（叠加 `test`）、T2 = `test`、T3 = `test-kafka`；`@ActiveProfiles` 为**合并**语义，profile 由各测试类自带、基类不上提；真库用例共用**进程内单例 MySQL 容器**（`--default-time-zone=+08:00`）。
- 分层映射（批次 1 定稿）：api 层持有请求/响应 DTO，应用层以 `*Command`/`*View`/`*Result`/`*Outcome` 模型通信；应用服务直接使用 `*DO` 与本上下文 Mapper，api 负责 DTO ↔ 模型映射。
- 语言特性约束（批次 1 定稿）：Java 17，**不使用 `switch` 模式匹配**（预览特性）；分支用 `instanceof` 模式匹配。
- 游标编码格式、列表过滤参数集合、返回 DTO 形态（批次 2 执行单定稿）。
