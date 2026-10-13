# 批次 5 执行单 · proto/gRPC 契约与横切（0.5 + 3 天）

## 输入

- 总指针与决策索引；批次 0–4 已完成（REST 入口已可用、测试三层已跑通）。

## 门禁（先跑再写代码）

- **G1**：`mvn -U dependency:get` 拉取一个新 grpc 依赖成功（验证阿里云镜像可达并落缓存）。
- **G2**：`mvn generate-sources` 成功（验证 protoc 二进制下载与代码生成）。
- **G3**：最小 in-process server 单测通过（`grpc-inprocess`，不占端口、不依赖外网）。
- **降级**：G1 失败 → proto 仅作契约文档 + POJO 防腐层、对外仍 REST；G2 失败 → 保留 `.proto`、生成改手工触发；G3 失败 → gRPC 服务端推迟，**不影响批次 1–4**。

## 产出文件

- `src/main/proto/order.proto`、`execution.proto`、`common.proto`（proto3，包名 `securities.order.v1` / `securities.execution.v1` / `securities.common.v1`；删除字段一律 `reserved`）。
- `pom.xml` 新增：`io.github.ascopes:protobuf-maven-plugin`、`io.grpc:grpc-netty-shaded`、`grpc-protobuf`、`grpc-stub`，版本用 `grpc-bom` 与 `protobuf-bom` 对齐。
- `infrastructure/grpc/GrpcServerLifecycle`：自建 server（9090）+ 注册服务与拦截器；**不使用 `grpc-spring-boot-starter`**。
- `infrastructure/grpc/interceptor/*`：traceId 注入（MDC）、耗时与错误计数、metadata 幂等键读取。
- `infrastructure/grpc/mapper/ProtoMapper`：proto ↔ 应用层 DTO 的防腐层转换。
- `common/error/GrpcStatusMapper`：`ErrorCode` → `Status` 映射表。
- 指标补齐（Micrometer + `/actuator/prometheus`），指标名以决策索引与总指针为准。

## 必须覆盖的测试

- **T1**：Proto ↔ DTO 映射（含可选字段缺省）、`ErrorCode → Status` 逐条断言、拦截器逻辑（traceId 生成/透传、耗时记录）。
- **T3**：**in-process server** 调用（`OrderService` 与 `ExecutionService` 各一条），断言幂等键从 metadata 读取生效、错误码以 `Status` 返回；真实 netty 端口仅在 `@Tag("it")` 用例中启用。
- 现有 REST 用例保持全绿（双入口并存）。

## 验收

1. 门禁 G1–G3 通过（或按降级路线执行并记录）。
2. `mvn verify` 全绿。
3. 单独一次 commit。

## 风险

- protoc 二进制与 grpc 依赖首次下载需联网；受限环境内不可用，须在本机终端执行。
