# ADR-0004: Model Call Job Submission and Durable Backlog Boundary

- Status: ACCEPTED
- Date: 2026-09-02
- Scope: M0-S9 Model Call Job submission boundary and future durable backlog evolution

## Context

V1 已使用 PostgreSQL 保存 `ModelCallJob` state，并使用独立的 Spring `TaskExecutor` 运行 Job lifecycle。
Worker 在自己的线程中同步等待 Model Gateway 返回，而 Gateway 再使用独立的 model-call `ExecutorService`
执行最终 execution deadline。两个 executor 解决的是 execution boundary 隔离，不会创造 durable backlog，
也不会消除实际 Provider capacity 上限。

当用户数、Model Operation 种类或跨实例部署增加时，当前 bounded in-process queue 可能出现 admission、
recovery 或 horizontal scaling 限制。直接让 Application Workflow 依赖 Spring `TaskExecutor`、`Runnable`
或未来某个 broker client，会把 submission mechanism 扩散到业务调用方，使后续演进需要修改多个 Workflow。

同时，当前 BYOK Credential 只允许存在于本次进程内任务。即使引入 durable message broker，Backend 重启后
也不能在没有 Credential 的情况下自动恢复 Provider 调用。未来若使用平台持有的 Provider Credential，仍需
单独决定 Secret Manager、访问权限、rotation、audit 与 deployment boundary。

## Decision

采用 operation-specific typed submission boundary：

```text
Application Workflow
        ↓
Create ModelCallJob in PostgreSQL
        ↓
Typed Job Submission Boundary
        ↓
V1 in-process TaskExecutor adapter
        ↓
Operation-specific Job Worker
        ↓
Typed Model Operation Port
```

- Application Workflow 不直接依赖 Spring `TaskExecutor`、`Executor`、`Runnable` 或 broker API；
- submission contract 接收 typed work item，并显式返回是否被当前 execution boundary 接纳；
- `ACCEPTED` 只表示任务已被当前 execution boundary 接纳，不表示 Provider 调用成功或 Job 已完成；
- capacity rejection 必须显式暴露，不能被误报为 accepted；已创建 Job 的 rejection compensation 由独立
  implementation slice 定义；
- Worker 保持 transport-agnostic，不感知任务来自 in-process executor、database queue 或 message broker；
- PostgreSQL 继续作为 Job status、typed result 与 consumption state 的 authority；
- V1 当前 adapter 仍使用独立、bounded Spring `TaskExecutor`，并继续与 Gateway model-call
  `ExecutorService` 隔离；
- 当前决定只保留替换 submission mechanism 的边界，不批准 Kafka、RabbitMQ、database-backed dispatcher、
  automatic retry、Credential persistence 或 generic workflow engine。

Transient BYOK 的 restart invariant 保持不变：服务重启后，缺少 Credential 的 `RUNNING` Job 不得自动
retry。平台持有 Credential 或 durable secret distribution 若进入正式产品范围，必须先通过独立的 Security
Architecture Decision。

## Alternatives

### Application Workflow directly uses TaskExecutor

实现层次最少，但 submission mechanism、capacity rejection 与 thread-pool dependency 会扩散到每个 Workflow，
后续替换 durable queue 时需要修改业务调用链。

### Introduce Kafka or RabbitMQ now

可以提供 durable backlog、ack 或多消费者能力，但当前没有生产规模证据，并会增加部署、运维、recovery、
idempotency、DLQ 与 observability 成本。更重要的是，broker 不能解决 transient BYOK Credential 在服务重启后
不可恢复的问题。

### Store work only in PostgreSQL and add a polling dispatcher now

能够避免新增 broker，并以 PostgreSQL 建立 durable admission；但仍需要设计 claim lease、restart recovery、
polling contention、Credential availability 与 duplicate execution。本轮只批准演进边界，不提前引入这些语义。

## Consequences

- Application Workflow 与当前 Spring execution mechanism 解耦，未来替换队列时优先局限在 submission / dispatch
  infrastructure；
- 当前实现仍受 Job TaskExecutor running capacity 与 queue capacity 限制；typed boundary 不增加吞吐；
- capacity rejection 成为必须被调用方处理的显式结果；
- Worker、Gateway、PostgreSQL authority、execution / consumption state 与 consume-once 语义不变；
- durable backlog、cross-instance recovery 与 hosted platform Credential 仍是独立 Architecture / Security scope，
  不得由本 ADR 推断为已批准。

## Revisit triggers

根据真实运行证据出现以下任一情况时，重新评估 `IDEA-002`：

- bounded in-process queue 持续产生可观测的 capacity rejection；
- 需要跨实例 admission、consumer scaling、restart recovery、DLQ 或 replay；
- 多个 Model Operation 产生不同 routing、ordering 或 priority requirement；
- Hosted Mode 正式批准平台 Credential lifecycle，并具备 Secret Manager、rotation 与 audit boundary。
