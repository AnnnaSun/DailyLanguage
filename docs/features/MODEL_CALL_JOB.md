# Model Call Job Design Contract

> Status: APPROVED DESIGN
> Approved: 2026-08-30
> Architecture amendment: 2026-09-02 — typed submission boundary and durable backlog evolution seam
> Implementation scope: SLICE-GATED
> Foundation phase: M0-S9

本文定义 V1 如何保存响应较慢但最终成功的 Model result，并让 Application Workflow 在结果仍有效时继续。
它不是通用消息平台，也不改变 Model Gateway 的单 Operation execution contract。

## 1. Goal and boundary

V1 production Application Workflow 在发起外部 Model call 前创建持久化 `ModelCallJob`。快速完成时，
调用方仍可在 interactive wait budget 内取得正常结果；等待预算耗尽时，HTTP / UI 返回可查询的 `jobId`，
后台调用继续执行，最终结果由 Job 保存而不是丢弃。

```text
Application Workflow
        ↓
Create ModelCallJob in PostgreSQL
        ↓
Typed Model Operation Job Submission
        ↓
Spring TaskExecutor adapter + transient in-memory Credential
        ↓
Typed Model Operation Port
        ↓
Provider Adapter
        ↓
Persist safe typed result / failure
        ↓
Workflow consumes, waits for user, or marks stale
```

Model Gateway 仍只负责一次 Model Operation 的 route、execution timeout 与 safe failure translation。
Job lifecycle、interactive waiting、result persistence、staleness 与后续动作属于 Background Job /
Application Workflow，不进入 Gateway Request / Response。

Job TaskExecutor 与 Gateway model-call ExecutorService 是两个不同的 execution boundary：前者运行 Job lifecycle
并等待 Operation result，后者包围同步 Provider Adapter call 并执行最终 deadline。二者不得共用同一个
bounded fixed pool，否则 Job worker 可能占满线程并等待无法获得线程的 Provider task，形成 starvation / deadlock。

## 2. Two timeout meanings

```text
interactive wait timeout
→ 调用方停止同步等待
→ Job 仍为 RUNNING，后台调用不取消

model execution timeout
→ 单次 Model call 达到最终 deadline
→ Gateway 返回 TIMEOUT；Job 进入 terminal execution status
```

`TIMEOUT` 不证明 Provider 没有收到或执行请求，也不授权自动 retry。Provider 可能已经产生部分或全部
token cost。V1 通过足够长的 execution deadline 与 late-result capture 减少丢弃慢结果的概率，但不承诺
恢复 Provider 已执行而 Backend 从未收到的 response。

## 3. Identity and versioning

`jobId` 使用稳定、不可猜测的 UUIDv7，只表示一个逻辑 Job identity，不把版本号编码进 ID。

每个 Job 至少关联：

- owner `userId`；
- 适用时的 `languageProfileId`；
- `ModelPurpose` 与 `ModelOperation`；
- route selection 后同时存在的 selected `ProviderId` 与 `ModelId`；route selection 前可以同时缺失；
- owning `workflowId`、`workflowStepId` 与 `workflowVersion`；
- execution / consumption status；
- optimistic-lock `rowVersion`；
- `createdAt`、optional `completedAt` 与 `expiresAt`。

三个值承担不同职责：

```text
jobId          → 稳定 identity
workflowVersion → 判断迟到结果是否仍适用于当前 Workflow
rowVersion      → 防止 accept / reject / expire 并发覆盖
```

接受、拒绝、自动过期和内部消费必须使用 conditional update，同时核对 expected `rowVersion`、当前状态与
`expiresAt`。一个结果最多被消费一次。

## 4. Lifecycle

Execution lifecycle 与 result consumption lifecycle 分离，避免一个 enum 混合“调用是否完成”和“结果是否
仍可使用”两种状态。

```text
Execution Status:
CREATED → RUNNING → SUCCEEDED
                  → FAILED
                  → TIMED_OUT
                  → OUTCOME_UNKNOWN

Consumption Status:
NOT_READY → PENDING_CONFIRMATION → CONSUMED
                               → DISCARDED
                               → EXPIRED
          → CONSUMED
          → STALE
```

- `STALE`：结果成功返回，但 owning Workflow 已 replan、fallback、取消或进入更新 version；
- `EXPIRED`：结果超过允许保留或处理的时间；
- `DISCARDED`：用户明确拒绝；
- `CONSUMED`：Application Workflow 已使用该结果；
- `OUTCOME_UNKNOWN`：Backend 无法确认 Provider execution outcome，例如进程在外部调用期间终止。

## 5. Completion policy by owning workflow

Job 只保存 execution outcome，不决定业务动作。Owning Application Workflow 检查 ownership、expiry、
workflow step/version 与结构化验证结果后决定：

- Conversation 或用户请求的生成内容：进入 `PENDING_CONFIRMATION`，由用户 Continue / Discard；
- Planner：step/version 仍有效时继续；已有 fallback / replan 时标记 `STALE`；
- Evaluator：Session 与 evaluation version 仍有效时进入 Structured Output validation 和 Evidence
  qualification；已有更新 evaluation 时标记 `STALE`；
- Content / Audio / Image：根据 REQUIRED / OPTIONAL 与 dependency policy 自动继续、等待用户、跳过或
  标记 stale。

迟到 Model result 不得直接修改 Weakness、Level、Mastery 或其他长期 Learner State。Evaluator result 仍然
只是 candidate，必须经过 Java validation / qualification。

## 6. Persistence and security

PostgreSQL 是 Job status 与可查询 result 的 authority。Result 必须使用 operation-specific typed artifact
或受控 result reference，不使用任意 JSON / metadata Map 保存 Provider raw response。

禁止将以下内容写入 Job、PostgreSQL、Redis、Trace、Log 或消息：

- API Key / Credential；
- raw Provider response；
- Provider SDK exception / stack trace；
- 未受控 request dump；
- 不属于该 Job 的 private Conversation / Context。

BYOK Credential 只能在创建后台任务时作为短生命周期内存数据传给当前 Worker，不得进入 durable job
payload。服务重启后，缺少 Credential 的 `RUNNING` Job 不得自动重试，应进入 `OUTCOME_UNKNOWN` 或按批准
recovery policy 终止。

## 7. Runtime and Kafka boundary

V1 baseline：

```text
Typed Job Submission Boundary
+ Spring TaskExecutor adapter
+ PostgreSQL Job State
+ bounded in-app polling
```

Application Workflow 不直接依赖 Spring `TaskExecutor`、`Executor`、`Runnable` 或 broker client，而是依赖
operation-specific typed submission boundary。当前 V1 adapter 使用 `modelCallJobTaskExecutor` 执行 Worker；
`ACCEPTED` 只表示当前 execution boundary 已接纳任务，不表示 Provider 调用成功或 Job 已完成。Capacity
rejection 必须显式返回，不能被吞掉或误报为 accepted；已创建 Job 的 rejection compensation 由独立 slice
定义。

这里的 TaskExecutor 只负责 Application / Job orchestration。Gateway final deadline 使用独立注入的 model-call
ExecutorService；具体 bean、pool sizing 与 lifecycle configuration 在对应 implementation scope 中决定。
Worker 保持 transport-agnostic，PostgreSQL 继续作为 Job status、typed result 与 consumption state authority。

Kafka 不进入 V1 Model Call Job path。Kafka 不会自动截获 Provider response；仍需要存活的 Worker 收到响应
并发布事件。Kafka message key 只提供 partition / ordering / correlation，不是 UI 可按 jobId 查询的业务
存储；Kafka delivery / exactly-once 也不覆盖 transaction 外的 Provider side effect。

只有真实出现跨实例吞吐、多消费者、replay、work queue / DLQ、per-profile ordering 或 DB-backed Job
瓶颈时，才依据 `IDEA-002` 重新评估 Kafka / RabbitMQ。

当前已批准保留 submission mechanism 的替换边界，但不代表 durable backlog 已获批准。未来采用
database-backed dispatcher、Kafka 或 RabbitMQ 仍需独立 Architecture Decision。若 durable execution 依赖平台
持有 Credential，还必须先批准 Secret Manager、rotation、access control 与 audit 等 Security Architecture。
Transient BYOK 下的 restart invariant 不变：缺少 Credential 的 `RUNNING` Job 不得自动 retry。

本节的正式决策轨迹见
[`ADR-0004`](../adr/0004-model-call-job-submission-and-durable-backlog-boundary.md)。

## 8. V1 scope and phase

- M0-S9：在 S7 transient Credential 与 S8 Structured Output / Trace foundation 之后，实现 backend
  `ModelCallJob` persistence、TaskExecutor execution、status query、versioned consume 与 late-result capture；
- M1：Planner、Conversation 与 Evaluator 的 Text Generation workflow 使用 Job，并提供站内 polling / result
  confirmation；
- M3 / M5：Content、Audio、Image 等正式进入对应 Phase 时复用该 lifecycle；
- M6：验证 concurrency、expiry、restart / outcome-unknown、secret leakage、capacity 与 failure recovery。

V1 站内提示不等于 Module 34 Push Notification / Learning Recall。Push、scheduler、邮件或外部通知继续
留在 V1 之后。

## 9. Explicit non-goals

- Kafka、RabbitMQ 或 distributed job platform；
- Credential persistence 或 durable secret distribution；
- automatic retry、cross-provider fallback 或 multi-attempt execution model；
- Push Notification / Learning Recall scheduler；
- generic Job payload Map、通用 workflow engine 或任意 result schema；
- Job 直接修改长期学习状态；
- 保证 Provider execution exactly once；
- 保证 Backend 未收到的 Provider response 可以恢复。

未来正式允许手动 / 自动 retry 时，再将一个 logical `jobId` 与多个 physical `attemptId` 分离，并先决定
idempotency、cost、deadline、maximum attempts 与 Trace policy。
