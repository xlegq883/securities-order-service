-- ============================================================================
-- 证券订单与成交服务 · 初始化脚本（SQL 单一来源）
-- ----------------------------------------------------------------------------
-- 约定：
--   * compose 与 Testcontainers 引用同一路径，不往 src/test/resources 拷副本。
--   * 脚本必须幂等：CREATE ... IF NOT EXISTS / INSERT ... ON DUPLICATE KEY UPDATE。
--   * 时间列统一 DATETIME(3)（毫秒精度）——查询游标使用 created_at_epoch_ms。
--   * 金额列统一 DECIMAL(18,4)；数量列为整数 BIGINT。
--   * 时区：容器与连接统一 Asia/Shanghai（MySQL 侧 --default-time-zone=+08:00）。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS securities
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE securities;

-- 1. t_order · 订单聚合写表 ---------------------------------------------------
CREATE TABLE IF NOT EXISTS t_order (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '物理主键（供 keyset 分页排序）',
    order_no            VARCHAR(64)     NOT NULL COMMENT '订单号',
    account_id          VARCHAR(64)     NOT NULL COMMENT '账户号',
    security_code       VARCHAR(32)     NOT NULL COMMENT '证券代码',
    side                VARCHAR(8)      NOT NULL COMMENT '买卖方向',
    order_type          VARCHAR(16)     NOT NULL COMMENT '订单类型',
    order_price         DECIMAL(18, 4)  NULL     COMMENT '委托价',
    quantity            BIGINT          NOT NULL COMMENT '委托数量',
    amount              DECIMAL(18, 4)  NULL     COMMENT '委托金额',
    filled_quantity     BIGINT          NOT NULL DEFAULT 0 COMMENT '累计成交量',
    filled_amount       DECIMAL(18, 4)  NOT NULL DEFAULT 0.0000 COMMENT '累计成交金额（与 t_execution.exec_amount 同源累加）',
    last_exec_seq       BIGINT          NULL     COMMENT '最近一笔成交序号（仅 SET 里 GREATEST）',
    event_version       BIGINT          NOT NULL DEFAULT 0 COMMENT '事件版本（每事务每聚合只 bump 一次）',
    status              VARCHAR(24)     NOT NULL COMMENT '订单状态',
    business_date       DATE            NOT NULL COMMENT '业务日期（下单时刻上海日期）',
    exchange_order_id   VARCHAR(64)     NULL     COMMENT '交易所订单号',
    cancel_requested_at DATETIME(3)     NULL     COMMENT '撤单请求时刻',
    client_order_id     VARCHAR(64)     NOT NULL COMMENT '客户端订单号（账户内幂等）',
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时刻',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_account_client (account_id, client_order_id),
    KEY idx_account_created_id (account_id, created_at, id),
    KEY idx_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '订单聚合写表';

-- 2. t_execution · 已应用成交明细（只存已应用成交） ---------------------------
CREATE TABLE IF NOT EXISTS t_execution (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    execution_id  VARCHAR(64)     NOT NULL COMMENT '成交号',
    order_no      VARCHAR(64)     NOT NULL COMMENT '订单号',
    exec_seq      BIGINT          NOT NULL COMMENT '成交序号（用于缺口观测与补拉，不作准入门槛）',
    exec_quantity BIGINT          NOT NULL COMMENT '本笔成交量',
    exec_price    DECIMAL(18, 4)  NULL     COMMENT '本笔成交价',
    exec_amount   DECIMAL(18, 4)  NOT NULL COMMENT '本笔成交金额（唯一真源）',
    exec_time     DATETIME(3)     NOT NULL COMMENT '成交时刻',
    business_date DATE            NOT NULL COMMENT '业务日期',
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时刻',
    PRIMARY KEY (id),
    UNIQUE KEY uk_execution_id (execution_id),
    KEY idx_order_seq (order_no, exec_seq)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '已应用成交明细（被拒成交不落此表）';

-- 3. t_report_event · 回报事件（一次性处理拦截） ------------------------------
CREATE TABLE IF NOT EXISTS t_report_event (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    report_type       VARCHAR(32)     NOT NULL COMMENT '回报类型',
    dedupe_key        VARCHAR(200)    NOT NULL COMMENT '回报去重键（成交类 EXECUTION:{order_no}:{execution_id}；其余 {report_type}:{order_no}:{exchange_seq}）',
    exchange_order_id VARCHAR(64)     NULL     COMMENT '交易所订单号',
    exchange_seq      BIGINT          NULL     COMMENT '交易所流水号',
    execution_id      VARCHAR(64)     NULL     COMMENT '成交号（成交类回报）',
    source            VARCHAR(32)     NOT NULL COMMENT '来源（realtime/repair/...）',
    applied           TINYINT(1)      NOT NULL COMMENT '是否被应用（0=被拒，只进本表与差异表）',
    payload           TEXT            NULL     COMMENT '原始回报',
    created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时刻',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dedupe_key (dedupe_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '回报事件（拦截一次性处理）';

-- 4. t_upstream_command · 上游指令（报单/撤单） -------------------------------
CREATE TABLE IF NOT EXISTS t_upstream_command (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    order_no      VARCHAR(64)     NOT NULL COMMENT '订单号',
    command_type  VARCHAR(32)     NOT NULL COMMENT '指令类型',
    transport     VARCHAR(16)     NOT NULL COMMENT '传输方式',
    payload       TEXT            NULL     COMMENT '指令内容',
    status        VARCHAR(16)     NOT NULL COMMENT '指令状态',
    attempt_count INT             NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    next_retry_at DATETIME(3)     NULL     COMMENT '下次重试时刻',
    last_error    VARCHAR(512)    NULL     COMMENT '最近错误',
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时刻',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_command (order_no, command_type)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '上游指令';

-- 5. t_event_outbox · 事件发件箱（保证同版本同 topic 至多一条） ---------------
CREATE TABLE IF NOT EXISTS t_event_outbox (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    aggregate_type VARCHAR(32)    NOT NULL COMMENT '聚合类型',
    aggregate_id  VARCHAR(64)     NOT NULL COMMENT '聚合标识（order_no）',
    event_version BIGINT          NOT NULL COMMENT '事件版本',
    event_type    VARCHAR(32)     NOT NULL COMMENT 'ORDER_STATE_CHANGED / EXECUTION_APPLIED',
    topic         VARCHAR(64)     NOT NULL COMMENT '目标 topic',
    message_key   VARCHAR(64)     NOT NULL COMMENT '消息 key',
    payload       TEXT            NOT NULL COMMENT '事件 payload（含 change_type、occurred_at）',
    dedupe_key    VARCHAR(200)    NOT NULL COMMENT '事件去重键',
    status        VARCHAR(16)     NOT NULL COMMENT '发送状态',
    retry_count   INT             NOT NULL DEFAULT 0 COMMENT '已重试次数',
    next_retry_at DATETIME(3)     NULL     COMMENT '下次重试时刻',
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时刻',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dedupe_key (dedupe_key),
    UNIQUE KEY uk_aggregate_topic_version (aggregate_type, aggregate_id, event_version, topic),
    KEY idx_status_next (status, next_retry_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '事件发件箱';

-- 6. t_order_query · 订单读模型（只由 order-events 驱动） ---------------------
CREATE TABLE IF NOT EXISTS t_order_query (
    order_no            VARCHAR(64)     NOT NULL COMMENT '订单号',
    account_id          VARCHAR(64)     NOT NULL COMMENT '账户号',
    security_code       VARCHAR(32)     NOT NULL COMMENT '证券代码',
    side                VARCHAR(8)      NOT NULL COMMENT '买卖方向',
    order_type          VARCHAR(16)     NOT NULL COMMENT '订单类型',
    order_price         DECIMAL(18, 4)  NULL     COMMENT '委托价',
    quantity            BIGINT          NOT NULL COMMENT '委托数量',
    amount              DECIMAL(18, 4)  NULL     COMMENT '委托金额',
    filled_quantity     BIGINT          NOT NULL DEFAULT 0 COMMENT '累计成交量',
    filled_amount       DECIMAL(18, 4)  NOT NULL DEFAULT 0.0000 COMMENT '累计成交金额',
    status              VARCHAR(24)     NOT NULL COMMENT '订单状态',
    business_date       DATE            NOT NULL COMMENT '业务日期',
    cancel_requested_at DATETIME(3)     NULL     COMMENT '撤单请求时刻',
    created_at          DATETIME(3)     NOT NULL COMMENT '创建时刻（源自写表，供 keyset 分页）',
    last_event_version  BIGINT          NOT NULL DEFAULT 0 COMMENT '已应用水位线（<= 该值的事件丢弃）',
    PRIMARY KEY (order_no),
    KEY idx_account_created (account_id, created_at, order_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '订单读模型（表与流一对一）';

-- 7. t_execution_query · 成交读模型（只由 execution-events 驱动，无水位线） --
CREATE TABLE IF NOT EXISTS t_execution_query (
    execution_id  VARCHAR(64)     NOT NULL COMMENT '成交号（主键幂等）',
    order_no      VARCHAR(64)     NOT NULL COMMENT '订单号',
    exec_seq      BIGINT          NOT NULL COMMENT '成交序号',
    exec_quantity BIGINT          NOT NULL COMMENT '本笔成交量',
    exec_price    DECIMAL(18, 4)  NULL     COMMENT '本笔成交价',
    exec_amount   DECIMAL(18, 4)  NOT NULL COMMENT '本笔成交金额',
    exec_time     DATETIME(3)     NOT NULL COMMENT '成交时刻',
    applied_at    DATETIME(3)     NOT NULL COMMENT '读模型应用时刻（query_lag 观测用）',
    PRIMARY KEY (execution_id),
    KEY idx_order_seq (order_no, exec_seq)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '成交读模型（无水位线字段）';

-- 8. t_reconcile_diff · 对账差异 ---------------------------------------------
CREATE TABLE IF NOT EXISTS t_reconcile_diff (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '物理主键（resolve 入口使用）',
    business_date DATE            NULL     COMMENT '业务日期',
    order_no      VARCHAR(64)     NULL     COMMENT '订单号',
    diff_type     VARCHAR(32)     NOT NULL COMMENT '差异类型',
    detail        TEXT            NULL     COMMENT '差异明细',
    dedupe_key    VARCHAR(200)    NOT NULL COMMENT '差异去重键',
    status        VARCHAR(16)     NOT NULL COMMENT 'OPEN / RESOLVED / FAILED',
    resolved_at   DATETIME(3)     NULL     COMMENT '处理时刻',
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时刻',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dedupe_key (dedupe_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '对账差异';

-- 9. t_eod_batch · 日终批次（只管编排） --------------------------------------
CREATE TABLE IF NOT EXISTS t_eod_batch (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '批次主键',
    business_date DATE            NOT NULL COMMENT '业务日期',
    batch_no      VARCHAR(64)     NOT NULL COMMENT '批次号',
    status        VARCHAR(16)     NOT NULL COMMENT '批次状态',
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时刻',
    PRIMARY KEY (id),
    UNIQUE KEY uk_business_batch (business_date, batch_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '日终批次编排';

-- 10. t_eod_batch_step · 日终步骤（owner + 租约 + CAS 抢占/接管） ------------
CREATE TABLE IF NOT EXISTS t_eod_batch_step (
    step_id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '步骤主键',
    batch_id        BIGINT UNSIGNED NOT NULL COMMENT '批次主键',
    step_name       VARCHAR(32)     NOT NULL COMMENT '步骤名',
    status          VARCHAR(16)     NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
    attempt_count   INT             NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    owner           VARCHAR(64)     NULL     COMMENT '持有者',
    heartbeat_at    DATETIME(3)     NULL     COMMENT '最近心跳',
    lease_expire_at DATETIME(3)     NULL     COMMENT '租约到期时刻',
    started_at      DATETIME(3)     NULL     COMMENT '开始时刻',
    finished_at     DATETIME(3)     NULL     COMMENT '结束时刻',
    error_msg       VARCHAR(512)    NULL     COMMENT '失败原因',
    PRIMARY KEY (step_id),
    UNIQUE KEY uk_batch_step (batch_id, step_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '日终步骤';

-- 11. t_eod_settlement · 日终结算（绝对值覆盖式 upsert，幂等锚点） -----------
CREATE TABLE IF NOT EXISTS t_eod_settlement (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    business_date   DATE            NOT NULL COMMENT '业务日期',
    account_id      VARCHAR(64)     NOT NULL COMMENT '账户号',
    security_code   VARCHAR(32)     NOT NULL COMMENT '证券代码',
    trade_count     INT             NOT NULL DEFAULT 0 COMMENT '成交笔数',
    filled_quantity BIGINT          NOT NULL DEFAULT 0 COMMENT '累计成交量',
    filled_amount   DECIMAL(18, 4)  NOT NULL DEFAULT 0.0000 COMMENT '累计成交金额',
    settled_at      DATETIME(3)     NOT NULL COMMENT '结算时刻',
    PRIMARY KEY (id),
    UNIQUE KEY uk_business_account_security (business_date, account_id, security_code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '日终结算结果（幂等锚点）';

-- 12. t_distributed_lock · 跨事务长锁（owner + 租约 + CAS） ------------------
CREATE TABLE IF NOT EXISTS t_distributed_lock (
    lock_name       VARCHAR(64) NOT NULL COMMENT '锁名',
    owner           VARCHAR(64) NULL     COMMENT '持有者',
    acquired_at     DATETIME(3) NULL     COMMENT '获取时刻',
    heartbeat_at    DATETIME(3) NULL     COMMENT '最近心跳',
    lease_expire_at DATETIME(3) NULL     COMMENT '租约到期时刻',
    PRIMARY KEY (lock_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '分布式长锁（禁用 GET_LOCK）';

-- 种子：读模型重建锁，唯一一行 -------------------------------------------------
INSERT INTO t_distributed_lock (lock_name, owner, acquired_at, heartbeat_at, lease_expire_at)
VALUES ('read_model_rebuild', NULL, NULL, NULL, NULL)
ON DUPLICATE KEY UPDATE lock_name = lock_name;
