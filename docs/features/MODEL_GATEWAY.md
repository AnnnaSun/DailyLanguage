# Model Gateway Design Contract

> Status: APPROVED DESIGN  
> Approved: 2026-08-29  
> Implementation scope: S6A–S6E COMPLETE；S6F PARTIAL / accepted non-blocking L2 gap；S7A COMPLETE (`d8d47ac`)；S7B READY_TO_COMMIT
> Phase: M0-S6 / M0-S7A / M0-S7B

本文固化 M0-S6 的 Model Gateway 责任边界。它定义后续 Text、Vision、Speech、Image 与
Embedding model capability 如何进入系统，但不提前实现尚未进入当前 slice 的 Operation。

## 1. Core responsibility

Model Gateway 是所有外部 Model 调用的统一 Architecture boundary，不是学习业务 Agent，也不是
一个负责完整多阶段生成流程的万能接口。

```text
Application Use Case
        ↓
Typed Model Operation Port
        ↓
Purpose + Operation Route
        ↓
Provider Adapter
        ↓
Configured Provider / Model
```

Gateway 负责一次明确 Model Operation 的执行、capability check、timeout 与安全 failure translation。
Application Workflow 负责多个 Operation 的顺序、依赖、required / optional policy、partial success、
degradation 与 terminal behavior。

## 2. Routing dimensions

路由必须区分两个维度：

- `ModelPurpose`：为什么调用，例如 Planning、Conversation、Evaluation、Content Design；
- `ModelOperation`：执行什么技术操作，例如 Text Generation、Vision Understanding、Speech
  Transcription、Speech Synthesis、Image Generation 或 Embedding。

逻辑 route key：

```text
ModelRouteKey = ModelPurpose + ModelOperation
```

每条 route 独立解析到：

```text
ProviderId + ModelId + typed operational configuration
```

同一 Purpose 可以调用多个 Operation；同一 Provider 也可以为不同 Operation 使用不同 Model 或
不同 Adapter。Provider 和 Model 是外部可配置 identifier，不使用封闭 enum；Purpose 与 Operation
是内部受控语义，可以使用 enum 并通过编译期检查暴露遗漏。

V1 使用 fixed mapping，不实现 cost-aware、latency-aware、quality-aware 或 automatic router。

## 3. Typed operation ports

Model Gateway 是逻辑 module boundary，不要求把所有能力塞进一个 Java interface 或万能 DTO。

M0-S6 的第一个 Port 只覆盖：

```text
Text Generation
```

未来只有在正式 Phase 需要时才增加独立 Typed Port，例如：

```text
Vision Understanding
Speech Transcription
Speech Synthesis
Image Generation
Embedding
```

Text、Speech、Image 与 Embedding 的 Request / Response 结构不同。禁止通过大量 nullable fields、
`Object`、generic option bag 或 `Map<String, Object>` 构造万能 `ModelRequest`。

可以在确有共同语义时共享：

- `ProviderId`；
- `ModelId`；
- `ModelPurpose`；
- `ModelOperation`；
- `ModelRouteKey`；
- `ModelResult<T>`；
- `ModelFailure` / `ModelFailureKind`；
- portable `ModelUsage`。

## 4. Text generation contract

Text Generation Request 的稳定业务语义包括：

- `ModelPurpose`；
- ordered messages；
- typed output specification。

Message role 使用项目内部语义，不暴露 OpenAI、DeepSeek、Gemini 或其他 SDK 类型。Text output
specification 在 S6 只支持普通 text；S8 可以增加 typed structured-output specification，而不通过
Provider-specific option Map 扩展。

Request 禁止包含：

- Provider 或 Model selection；
- raw API Key 或其他 Credential；
- Provider base URL；
- arbitrary Provider options；
- `userId` 或 `languageProfileId`；
- Trace persistence detail。

Provider / Model 来自 route；Credential 属于 S7 的独立 transient execution context；timeout 属于
Gateway operational policy；Trace 属于 S8 observability boundary。Gateway 不拥有 Domain identity 或
Learning State authority。

Text Generation Response 只暴露 portable typed information：

- selected `ProviderId`；
- selected `ModelId`；
- text output；
- normalized finish reason；
- Provider 可可靠提供时的 portable usage。

禁止把 Provider raw response、exception、SDK type 或 arbitrary metadata Map 暴露给业务模块。

## 5. Result and failure contract

预期的 Model operational failure 使用显式 typed result，使 Planner、Evaluator、Conversation 等调用方
必须处理 unavailable / degraded behavior。Programming bug、invalid wiring 与不可能成立的 internal
state 仍然 fail fast，不伪装成普通 Provider failure。

M0-S6 的最小 failure taxonomy：

```text
CAPABILITY_UNAVAILABLE
REQUEST_REJECTED
AUTHENTICATION_FAILED
CREDENTIAL_UNAVAILABLE
RATE_LIMITED
TIMEOUT
TEMPORARY_UNAVAILABLE
PROVIDER_FAILURE
```

Failure 可以包含安全的 Provider / Model identity 和 Provider 明确给出的 retry-after，但不得包含：

- raw response body；
- exception message 或 stack trace；
- Prompt / private Conversation；
- Credential；
- 未脱敏 request dump。

Gateway 只报告和归一化失败，不决定业务降级结果。

`ModelFailureKind.TIMEOUT` 表示单次 Model execution 达到最终 deadline，不等于 Application 的
interactive wait budget 耗尽。后者由 `ModelCallJob` 返回 pending handle，并允许后台调用在最终 deadline
内继续；不得把仍在运行的 Job 伪装成 terminal Gateway failure。

## 6. Retry and fallback boundary

M0-S6 默认不自动 retry。Timeout 不证明 Provider 未执行请求；静默 retry 可能产生重复费用、不同输出、
更严重的 Rate Limit 或不可解释的 attempt history。后续只有在明确的 idempotency、cost、deadline、
maximum attempts、backoff 与 Trace policy 下，才能为特定 Operation 增加 bounded retry。

M0-S6 默认禁止静默 cross-provider fallback。切换 Provider 可能改变 Credential、费用、隐私边界、
capability 与 output compatibility。只有同时满足以下条件时，后续 Routing / Workflow Policy 才可允许：

- 用户显式配置并授权 fallback；
- fallback Provider 的 transient Credential 可用；
- Operation 与 required feature compatibility 已验证；
- maximum attempts、cost 与 terminal behavior 明确；
- Trace 可以区分 route 和每次 attempt。

Provider Adapter 不得自行选择另一个 Provider。

## 7. Multi-operation workflow boundary

类似以下流程不属于单次 Gateway 调用：

```text
Text Generation
      ↓
Speech Synthesis
      ↓
Optional Image Generation
```

具体 Application Workflow 必须显式定义每一步：

- `REQUIRED` / `OPTIONAL`；
- dependency；
- success artifact；
- failure status；
- retry / replace / skip action；
- degraded result；
- terminal condition。

已成功并验证的独立 artifact 可以在后续可选步骤失败时保留。例如普通阅读内容的 audio / image
失败不应删除已验证 text；Speech Transcription 失败时不得猜测 transcript；Listening Practice 的
required audio 失败时不得把 Session 伪装成已正常开始。

Model infrastructure failure 可以进入 Workflow / Trace evidence，但不得成为 learner error、Weakness、
Skill decline 或其他长期 Learning Evidence。

## 7.1 Model Call Job boundary

V1 production Application Workflow 在调用 Typed Model Operation Port 前创建 `ModelCallJob`。Job 负责：

- interactive wait 与 pending status；
- TaskExecutor lifecycle；
- durable execution / consumption status；
- late-result persistence、expiry、staleness 与 consume-once；
- 用户 confirmation 或内部 Workflow resume。

Gateway 不创建或持久化 Job，不接收 `jobId`、`userId`、`languageProfileId`、`workflowVersion` 或
`rowVersion` 作为 Model Request 字段，也不决定迟到结果应被接受、拒绝或标记 stale。

```text
Application Workflow
        ↓
ModelCallJob
        ↓
Typed Model Operation Port
        ↓
Provider Adapter
```

详细 lifecycle 见 [`MODEL_CALL_JOB.md`](MODEL_CALL_JOB.md)。

## 8. Provider adapter boundary

Adapter 按 Operation 实现小而明确的 SPI，避免一个 Provider class 拥有大量不支持的方法并抛出
`UnsupportedOperationException`。

同一 Provider 的不同 Adapter 可以通过 Composition 复用 HTTP transport、authentication、error
translation 与 protocol mapping；不为复用字段创建通用 Base Class，也不把 Provider SDK 类型泄漏给
Application / Domain。

S7B 已实现第一个 concrete protocol Adapter：使用 JDK HttpClient 与现有 Jackson 3，按
OpenAI-compatible Chat Completions 的 portable subset 映射。第一个配置目标是 DeepSeek；兼容 Provider 的
ProviderId / endpoint 差异通过 typed config 表达，协议行为差异才新增 Adapter。不引入 Spring AI、Provider
SDK、Registry、Factory 或 Base Class。

## 9. S6 design decisions

```text
D1  Model Gateway 是 provider-agnostic logical module boundary。
D2  每次 Gateway 调用只执行一个明确 Model Operation。
D3  Route key 使用 ModelPurpose + ModelOperation。
D4  Provider / Model 按 route 独立配置，V1 只使用 fixed mapping。
D5  不同 Operation 使用独立 Typed Port 和 Request / Response。
D6  只共享具有真实共同语义的 identifier、route、result、failure 与 usage type。
D7  禁止万能 Request、nullable field collection 与 arbitrary options / metadata Map。
D8  Provider SDK、raw response 与 Provider-specific options 不得进入业务 contract。
D9  Credential 不进入 Model Request / Response；S7 设计独立 transient execution context。
D10 S6 第一个实现 Port 只覆盖 Text Generation。
D11 Gateway 归一化 timeout / error，但不决定业务 fallback 或 Domain mutation。
D12 多 Operation 顺序与依赖由具体 Application Workflow 控制。
D13 Workflow 显式定义 REQUIRED / OPTIONAL 与 partial-success policy。
D14 已验证的独立 artifact 可以在后续可选步骤失败时保留并单独重试。
D15 S6 默认不自动 retry。
D16 S6 默认禁止静默 cross-provider fallback。
D17 Cross-provider fallback 需要用户配置、Credential、capability 与 policy 共同授权。
D18 Model infrastructure failure 不产生 Learning Evidence 或长期学习状态变化。
D19 S6 不新增 Spring AI 或 concrete Provider SDK dependency。
D20 Model Call Job 位于 Application / Background Job boundary，不进入 Gateway Request / Response。
D21 interactive wait timeout 返回 pending；只有最终 execution deadline 才产生 Gateway TIMEOUT。
```

## 10. M0-S6E approved execution decisions

```text
D22 TextGenerationRoute 必须显式持有 positive executionTimeout，不提供 silent default，也不把 timeout 放入
    provider-neutral Request / Response。
D23 Gateway 和 TextGenerationProviderAdapter 使用同一个 executionTimeout：Adapter 配置 Provider HTTP / client
    timeout，Gateway 通过外层 Future deadline 建立最终本地等待边界。
D24 S6E 使用外部注入的 dedicated JDK ExecutorService，不在 Gateway 内创建或关闭；它与 M0-S9 Job
    TaskExecutor 不得共用同一个 bounded fixed pool，避免 Worker 等待自己提交的 Provider task 导致
    starvation / deadlock。
D25 final deadline 到期时 best-effort cancel(true)，并返回 route-aware TIMEOUT；不自动 retry / fallback，
    且不保证 Provider 已停止、未执行或未产生 token cost。
D26 checked ModelProviderCallException 只携带 ModelFailureKind 与 optional retryAfter；禁止 raw message、raw
    response、SDK exception / cause、Prompt、Credential 与 arbitrary metadata 穿过 Adapter boundary。
D27 只有 typed operational exception 被归一化；caller interrupt、executor rejection、unclassified
    RuntimeException、null result 与 route identity mismatch 均安全 fail fast，Error 不转换为 ModelFailure。
D28 S7 必须显式把 transient Credential 传播到 actual Provider call；不得假设普通 ThreadLocal 会跨
    ExecutorService boundary 自动传播。
```

## 11. M0-S7A approved credential decisions

```text
D29 TransientProviderCredential 是单次 execution 的 Provider-scoped opaque secret，与 provider-neutral
    TextGenerationRequest 分开传入 Typed Port；不得进入 route configuration、result 或 failure payload。
D30 selected route 与 credential.providerId 不匹配时，在提交 Adapter task 前返回 route-aware
    CREDENTIAL_UNAVAILABLE；Provider 实际拒绝已提供 Credential 仍归类为 AUTHENTICATION_FAILED。
D31 RoutedTextGenerationPort 必须在 submitted task 中显式捕获 Credential 并传给 operation-specific Adapter；
    不使用 ThreadLocal、global mutable context 或持久化 resolver 隐式传播。
D32 TransientProviderCredential 不使用会自动暴露字段的 record toString；secret 只能由 Adapter 读取，
    toString 必须 redacted，exception / ModelFailure 不得携带 secret。
D33 timeout cancellation 是 best effort；S7A 不承诺 worker 立即停止或 Credential 立即从 JVM heap 消失。
```

## 12. M0-S7B approved Provider Adapter decisions

```text
D34 Adapter 按 OpenAI-compatible protocol family + TEXT_GENERATION operation 命名，不按每个兼容厂商复制代码。
D35 OpenAiCompatibleProviderConfig 只保存 ProviderId 与 trusted HTTPS Chat Completions endpoint；ModelId 仍由
    selected route 提供，endpoint 不来自 request 或 Credential。
D36 Credential 只在 Adapter 构造 Authorization Bearer header 时读取；HttpClient 必须禁止 redirect，避免
    Credential 被转发到 configured endpoint 之外的位置。
D37 Adapter 只实现 non-streaming portable text subset；Provider-specific thinking、Tool Calling、Structured
    Output、streaming 与 raw finish reason 不通过 option Map 偷渡。
D38 HTTP error body、transport exception、JSON parsing exception、Prompt 与 Credential 不进入 typed failure；
    selected Provider / Model identity 仍由 Gateway route 负责。
```

## 13. Explicit S6 / S7 non-goals

- Browser / HTTPS Credential ingress、Credential storage、rotation 或 UI；
- Spring bean / route / Executor production wiring 与 live DeepSeek network verification；
- 第二个 Provider configuration 或 native Provider protocol；
- Structured Output validation；
- Trace persistence；
- Planner、Evaluator、Conversation 或 Content Workflow；
- Speech、Vision、Image、Embedding Port implementation；
- automatic retry、fallback、health router 或 circuit breaker；
- Spring Executor bean / pool sizing / lifecycle configuration；
- Model Call Job persistence、interactive wait、late-result recovery 或用户 confirmation；
- 任何 Learning State mutation。
