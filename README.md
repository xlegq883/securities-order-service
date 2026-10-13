# securities-order-service

证券订单与成交服务：订单受理、成交回报、查询、日终，以及配套的契约与横切能力。

## 实施规格（先读这里）

本项目的实现规格以 **总指针** 为准，不再凭记忆或口头约定改决策：

- 技术规格（唯一事实来源）：[`docs/plan/MASTER-SPEC.md`](docs/plan/MASTER-SPEC.md)
- 决策索引与理由：[`docs/plan/DECISIONS.md`](docs/plan/DECISIONS.md)
- 各批执行单：`docs/plan/batch-0.md` … `docs/plan/batch-5.md`

验收命令统一为 `mvn verify`；三层测试（T1 纯逻辑单测、T2 真库原子性与并发、T3 真 Kafka 语义）全部默认执行，无默认跳过机制。

## 当前状态

- 仓库当前只包含实施规格与执行单；批次 0（脚手架）待开工。
- 依赖编排（MySQL 3307 / Redis 6379 / Kafka 9092）、工程骨架、CI 与测试基建将在批次 0 落地。

## 本地依赖

```bash
docker compose up -d      # MySQL(3307) / Redis(6379) / Kafka(9092)
mvn verify                # 唯一验收命令
```

> 约定：本机 3306 已被占用，MySQL 固定映射到 **3307**；容器与服务统一 `TZ=Asia/Shanghai`。
