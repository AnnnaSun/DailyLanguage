# Evaluation API Orchestration Flow

- Document Status: `IMPLEMENTED`
- Feature / Slice: `M1-S8E-API`
- Last Verified: `2026-09-09`
- Entry:
  - `PUT /api/language-profiles/{languageProfileId}/practice-sessions/{sessionId}/evaluation`
  - `PUT /api/language-profiles/{languageProfileId}/practice-sessions/{sessionId}/evaluation/reconciliation`

## 1. Behavior Boundary

本 Flow 描述已经实现的 owner-scoped Evaluation HTTP orchestration。两个入口都要求 authenticated
`UserContext` 与 CSRF。Trigger 接收 `providerId` 和单次 `X-Model-Provider-Credential`，先读取 owned completed
Practice snapshot，再验证固定 `EVALUATION` route 与 Provider 一致，随后复用 S8D dispatch 和 S8E-R
reconciliation kernel。Reconciliation 不接收 Credential、Provider、Job id 或 raw Model output，只根据路径中的
Profile / Session 与 authenticated identity 推进或重放同一个 durable Evaluation outcome。

`PENDING` 返回 `202 Accepted`，`Location` 指向 reconciliation endpoint；durable `SUCCEEDED / FAILED` 都返回
`200 OK`。`FAILED` 是完成的 Evaluation workflow outcome，不等同于 HTTP transport failure。

本 Flow 不提供 background scheduler、automatic retry、遗留 `CREATED / RUNNING` recovery、SSE / WebSocket、
GET polling、Frontend UI、Trace persistence、Evidence qualification 或 Memory / Weakness / Level / Mastery mutation。
进程在 Run / Job durable commit 后、memory-only submission 前终止的 crash window 仍是已知限制。

## 2. Main Call Chain

```mermaid
sequenceDiagram
    participant Client
    participant Security as Spring Security / CSRF
    participant API as EvaluationController
    participant App as PracticeSessionEvaluationService
    participant Reader as GroundedEvaluationInputReader
    participant Routes as FixedTextGenerationRoutes
    participant Dispatch as EvaluationDispatchService
    participant JobDispatch as TextGenerationJobDispatch
    participant Worker as TextGenerationJobWorker
    participant Consume as EvaluationResultConsumptionService
    participant DB as PostgreSQL
    participant Provider as TextGenerationPort

    Client->>Security: PUT /evaluation + providerId + transient Credential
    Security->>API: authenticated UserContext
    API->>App: start(profileId, sessionId, userContext, providerId, secret)
    App->>Reader: readOwned(...)
    Reader->>DB: owner/profile-scoped completed snapshot read
    DB-->>Reader: Ready / safe failure
    App->>Routes: findRoute(EVALUATION)
    App->>Dispatch: dispatchForReadyInput(Ready, UserContext, Credential)
    Dispatch->>DB: REQUIRES_NEW create/read unique Run + Job
    opt Run / Job newly Created
        Dispatch->>JobDispatch: dispatchCreated(Job, request, Credential)
        JobDispatch-->>Worker: bounded async submission
        Worker->>Provider: memory-only request/Credential
        Provider-->>Worker: ModelResult
        Worker->>DB: durable execution/result transition
    end
    App->>Consume: consumeForReadyInput(Ready, UserContext)
    Consume->>DB: lock Run; reconcile/replay Job result and outcome
    alt Job still CREATED / RUNNING
        App-->>API: Pending(run)
        API-->>Client: 202 + reconciliation Location
    else durable SUCCEEDED / FAILED
        App-->>API: Terminal(outcome)
        API-->>Client: 200 + safe durable projection
    end

    Client->>Security: PUT /evaluation/reconciliation
    Security->>API: authenticated UserContext
    API->>App: reconcile(profileId, sessionId, userContext)
    App->>Reader: readOwned(...)
    App->>Consume: consumeForReadyInput(Ready, UserContext)
    Note over App,Consume: no Credential, route lookup or new dispatch
    Consume-->>API: Pending / Terminal / NotFound
    API-->>Client: 202 / 200 / stable error
```

## 3. State and Authority

| Concern | Authority / Behavior |
|---|---|
| Authentication | Spring Security 提供 `UserContext`；caller 不能通过 body 指定 userId |
| Ownership / language isolation | `GroundedEvaluationInputReader.readOwned` 在读取 learner text 前按 `userId + languageProfileId + sessionId` 裁决 |
| Route / Provider | Java 从 fixed `EVALUATION / TEXT_GENERATION` route 读取配置，并要求请求 `providerId` 精确匹配 |
| Run / Job uniqueness | S8B 在 Session row lock 与 `REQUIRES_NEW` transaction 中建立或读取唯一 Run / Job |
| Provider execution | S8D 只对 `Created` dispatch；`Existing` 不重新调用 Provider |
| Reconciliation | S8E-R 锁定 owner-scoped Run，在单一 transaction 内消费 Job result 并终结或重放 outcome |
| Semantic authority | Model 只产生不可信 JSON；Java grounding、qualification、状态转换和 persistence 保持 authority |
| Long-term learning state | 本 Flow 不创建 Qualified Evidence，也不修改 Memory、Weakness、Level 或 Mastery |

`PracticeSessionEvaluationService.start / reconcile` 使用 `@Transactional(NEVER)`，避免把 Reader 的短
read-only transaction、Run / Job 创建的 `REQUIRES_NEW`、异步 dispatch 和 result consumption 的 read-write
transaction 包进一个外层事务。Reconciliation 的 `Pending` 携带锁内读取的 durable `EvaluationRun` snapshot，
Controller 因此不需要第二次无锁查询。

## 4. Response and Failure Paths

| Condition | HTTP Result |
|---|---|
| Job 仍为 `CREATED / RUNNING` | `202 Accepted` + reconciliation `Location` |
| Run 为 durable `SUCCEEDED / FAILED` | `200 OK` + safe outcome projection |
| providerId 非法 / Credential 缺失或空白 | `400 INVALID_PROVIDER_ID / INVALID_PROVIDER_CREDENTIAL` |
| owner/profile 范围内 Session 不存在 | `404 PRACTICE_SESSION_NOT_FOUND` |
| owned Session 没有 EvaluationRun | `404 EVALUATION_NOT_FOUND` |
| Session 尚未 completed | `409 PRACTICE_SESSION_NOT_COMPLETED` |
| providerId 与配置 route 不匹配 | `422 EVALUATION_PROVIDER_MISMATCH` |
| exact input/material snapshot 不可用 | `503 EVALUATION_INPUT_UNAVAILABLE` |
| route、prompt 或 rubric 配置不可用 | `503 EVALUATION_CONFIGURATION_UNAVAILABLE` |
| durable identity / CAS 不变量损坏 | 安全异常 fail closed；不伪装成业务成功，不创建替代 Run / Job |

Response 只投影 Run id、Profile / Session id、status、timestamps、closed failure category、safe grounding
rejection reason 与 validated semantic candidate / claims。它不返回 userId、Job id、workflow / row version、
Credential、Prompt 或 raw Model output。Credential 只进入当前 trigger 内存调用链；reconciliation 完全不接触它。

## 5. Verification Evidence

- Critical Diff Review（2026-09-09）：Scope MATCH；Code Review / Architecture PASS；no blocking findings。
- Full-loop Ownership（2026-09-09）：`UNDERSTOOD`；用户正确区分 ModelCallJob execution/consumption 与
  EvaluationRun business outcome，并说明 grounding rejection 不会修改长期 learner state；Human Touch
  `NOT_REQUIRED`。
- Local targeted：45 discovered，43 executed，2 个 database-conditional tests skipped；0 failures / 0 errors。
- Local full server：727 tests / 0 failures / 0 errors / 162 environment-conditional skips（实际执行 565）。
- External：独立 disposable PostgreSQL 18.6 empty schema，Flyway V1–V13 13/13 validated and applied；pgvector
  0.8.6。
- S8 evaluator integration regression 31/31 PASS：Reader 5、Grounding 3、Run creation 8、Dispatch / HTTP API 2、
  Result consumption 13；0 failures / 0 errors / 0 skipped。
- HTTP integration 覆盖 trigger → async Job → reconciliation → `SUCCEEDED`、terminal replay 不二次调用 Provider、
  foreign owner `404`，以及 Credential 不进入 `model_call_job` durable JSON。
- Integration 使用受控 `TextGenerationPort` mock，未调用 live Provider。临时容器已删除，primary database 未使用；
  未执行 Flyway repair 或修改 schema history。`git diff --check` PASS。

## 6. Source References

- `server/src/main/java/com/dailylanguage/evaluator/api/EvaluationController.java`
- `server/src/main/java/com/dailylanguage/evaluator/application/PracticeSessionEvaluationService.java`
- `server/src/main/java/com/dailylanguage/evaluator/application/GroundedEvaluationInputReader.java`
- `server/src/main/java/com/dailylanguage/evaluator/application/EvaluationDispatchService.java`
- `server/src/main/java/com/dailylanguage/evaluator/application/EvaluationResultConsumptionService.java`
- `server/src/main/java/com/dailylanguage/evaluator/application/EvaluationRunCreationService.java`
- `server/src/test/java/com/dailylanguage/evaluator/api/EvaluationControllerTests.java`
- `server/src/test/java/com/dailylanguage/evaluator/application/PracticeSessionEvaluationServiceTests.java`
- `server/src/test/java/com/dailylanguage/evaluator/application/EvaluationDispatchIntegrationTests.java`
- `server/src/test/java/com/dailylanguage/evaluator/application/EvaluationResultConsumptionIntegrationTests.java`
