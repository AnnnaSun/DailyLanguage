# AI Language Tutor — V1 Phase Plan

> Status: APPROVED  
> Version: 1.3
> Approved: 2026-08-20  
> Last updated: 2026-08-30
> Scope baseline: `docs/product/V1_SCOPE.md`

## 1. Delivery Strategy

V1 按 M0–M6 顺序推进。每个 Phase 只在前一 Phase 的 exit criteria 通过后进入实现。

```text
M0 Foundation
  → M1 Minimum Practice
  → M2 Persistent Adaptation
  → M3 Content / RAG / Multi-role Agent Workflow
  → M4 Learning Completeness
  → M5 Listening / Voice
  → M6 Hardening / Delivery
```

每个 implementation slice 均遵循：

```text
Scope
  → Implement
  → Verification
  → Diff Review
  → Human Ownership Check
  → Ready to Commit
```

完成一个 slice 后停止，不自动开始下一个 slice。

每个核心 AI / Java capability 还必须按 `ENGINEERING_EVIDENCE_PLAN.md` 形成适用的 failure test、
Eval、Trace、measurement 与 interview demo evidence。Design 或正常路径通过不等于 Evidence Gate 完成。

### 1.1 Provider-free Learning Baseline

V1 已批准用户未提供 Model Provider 时仍可执行的最小学习路径。它复用同一 Planner、Practice、
Evaluator、Evidence、Learning Memory 与 `languageProfileId` isolation，不建立第二套 offline Tutor。

首个 Built-in Content Pack 使用：

```text
targetLanguage = en
supportLanguage = zh-CN
```

`supportLanguage` 只提供翻译、解释与提示，不产生独立学习状态。起始能力范围和内容数量留到 M1
Scope Decision。详细 Product / Architecture Contract 见
`docs/features/PROVIDER_FREE_LEARNING.md`。

跨 Phase 交付：

```text
M1 Built-in Text Practice walking skeleton
  → M2 deterministic Evidence / Memory / Re-planning
  → M3 versioned Content productionization
  → M5 verified Built-in Audio
```

## 2. Phase Overview

### M0 — Engineering Foundation & Language Workspace

**Goal**

建立后续核心闭环所依赖的可运行工程边界，不实现完整学习体验。

**Done Criteria**

- frontend、backend 与 local infrastructure 可以按文档启动；
- User / Language Profile 的身份和语言隔离边界可验证；
- PostgreSQL 是 Hosted long-term state authority；
- Model 调用只经过 Model Gateway；
- BYOK Credential 不进入 DB、Redis、Trace 或 Log；
- Structured Output 的失败路径不会写入长期状态；
- 至少一条 walking skeleton 能验证请求、模型边界、validation 与 trace metadata。
- Model Call Job foundation 可以保存迟到结果，且 Credential 不进入 durable Job state。

### M1 — Minimum Text Practice Loop

**Goal**

让用户在一个 Language Profile 下完成一次最小 text practice，并得到 Session-level evaluation。

**Done Criteria**

- Planner 生成最小 LearningTask；
- 用户完成 text conversation / writing practice；
- Practice 产生 trusted event 可确定的 deterministic assessment；
- Model 可用时 Evaluator 生成经过 validation 的 semantic diagnosis；
- semantic issue 可以定位到具体 Practice turn / span；缺少 grounding 的 claim 不得进入 Evidence；
- Model failure 只隔离 model-derived result，Practice 与 deterministic assessment 被正确保存；
- Planner 在 Model unavailable / invalid output 时仍能生成合法的 deterministic fallback task；
- 无 Provider 时至少一条 `en + zh-CN` Built-in Text Practice 可以完成 LearningTask、PracticeSession
  与 deterministic assessment，且不调用 Model Gateway；
- Built-in task 引用稳定 `materialId + version`，语言不匹配、内容损坏或无可用材料时 fail closed；
- Provider-free baseline 只产生 exact / rule-verifiable 结果及可信 assistance event 支持的
  deterministic assessment，不伪造 semantic、naturalness 或 pronunciation Evidence；
- Evaluator 不直接改变 Weakness、Level 或 Mastery；
- invalid structure、unsupported claim、Model failure 与 Provider contract 有自动化验证；
- Planner / Evaluator Trace 记录 model、version、token、latency 与 result status，不记录 Secret。
- Text Model Call 在 interactive wait budget 耗尽后可以继续后台执行；迟到结果根据 owning Workflow
  version 自动消费、等待用户确认或标记 stale。

### M2 — Persistent Adaptation Loop

**Goal**

让多次 Practice Evidence 形成长期状态，并影响下一次训练计划。

**Done Criteria**

- 正确与错误 Evidence 都被记录；
- Aggregated Memory 综合 recency、frequency、confidence、scenario 与 independence；
- Weakness / Skill State 由 Java 规则执行确定性 transition；
- Aggregation、Weakness lifecycle 与 Profile projection 可以基于已有 Qualified Evidence
  在不调用模型的情况下重放并得到相同结果；
- aggregation policy version、mutation lineage 与 replay result 可追踪；
- duplicate evaluation、retry、concurrent aggregation 与 lost-update path 有自动化验证；
- Planner 使用 compact Profile、active state、due review 与 recent practice；
- Progress 是现有长期状态的 read-only projection；
- 支持 core continuous assessment 与 lightweight Practice Feedback；
- Built-in Practice 的正确、错误与 assistance Evidence 进入同一 aggregation、Review 与 re-planning
  链路，并区分 assisted / independent 语义；
- 不同 `languageProfileId` 的状态不可串用。

### M3 — Content / RAG / Multi-role Agent Workflow

**Goal**

加入内容驱动的练习、grounded retrieval、Tool Gateway 与受控 Multi-role Agent Workflow，
同时维持 structured state authority。

**Done Criteria**

- Reading / imported content 可以生成 LearningTask 与 Evidence；
- Retrieval 具备 language isolation、provenance 与基础 relevance metadata；
- RAG Result 只作为 Context，不直接成为长期状态事实；
- `Content Retrieval Role → Lesson Design Role → Quality Review Role → bounded revision` 由 Java
  workflow state、turn/tool limit、validation 与 publish authority 控制；
- Tool Gateway 覆盖 schema、allowlist、permission、timeout、retry、idempotency 与 trace；
- source / chunk grounding、prompt injection、cross-language retrieval 与 tool-loop termination 有验证；
- Full Context baseline 与 budgeted Context strategy 完成 quality、token、latency、cost 对比；
- Content Practice 进入统一 Evaluation / Memory 链路。
- 根据真实 dogfooding evidence 确定有限 Built-in Content Pack 的数量和覆盖，并将 M1 artifact 接入
  versioned provenance / publish boundary；不提前承诺完整 curriculum 或全语言内容。

### M4 — Learning Completeness

**Goal**

补齐 V1 的阶段性评估、Review 与学习路径控制能力。

**Done Criteria**

- Milestone Check 使用明确 rubric 和 evidence sufficiency；
- Review System 与 Planner 职责分离；
- 用户可以 Skip、Replace、Easier、Harder、Change Topic 或 Replan；
- Grammar Repair 回到真实使用场景验证 transfer；
- advanced assessment mechanisms 仍留在 Backlog。

### M5 — Listening / Turn-based Voice

**Goal**

在现有闭环上增加可控、可评估的听说练习。

**Done Criteria**

- Listening / turn-based Voice 复用 Language Profile、Planner、Evaluator 与 Memory 边界；
- 经过验证的固定音频可以作为 Built-in Listening material；浏览器或设备 TTS 仅作为可选 UX，
  不成为 audio authenticity、评分或 Evidence authority；
- 音频失败、超时与重试不污染长期状态；
- Voice 不绕过 Tool / Model Gateway；
- realtime full-duplex voice 不进入 V1。

### M6 — V1 Hardening & Evidence Delivery

**Goal**

完成 V1 的安全、可靠性、Eval、capacity、CI、部署、interview evidence 和 ownership 验收。

**Done Criteria**

- 关键路径具备 targeted regression eval；
- secret leakage、language isolation 与 state mutation boundary 有自动化验证；
- timeout、retry、idempotency 与 failure recovery 按实际 Tool 风险覆盖；
- Provider / Model 的 quality、latency、token 与 cost 结果可比较；
- 关键 AI / Java path 完成 failure injection、concurrency、load / capacity 与 recovery 验证；
- CI 执行 build、migration、test 与 required Eval gate；
- Hosted / Self-hosted 使用同一核心业务代码并可重复部署；
- Architecture Docs 与真实实现一致；
- 核心调用链完成人工 Ownership Check；
- 可重复 demo 展示 adaptation、grounding、fallback、trace、replay 与 language isolation。

## 3. Current Phase: M0 Implementation Slices

M0 先拆为以下认知边界。每个 slice 开始前仍需确认具体 file scope 和 architecture-sensitive decision。

| Slice | Scope | Verification focus |
| --- | --- | --- |
| M0-S1 | Build 与 application skeleton | backend/frontend 能启动；无 Domain 行为 |
| M0-S2 | Local infrastructure baseline | PostgreSQL、pgvector、Redis 与配置边界可启动 |
| M0-S3 | User / Language Profile persistence identity | migration、repository boundary、`languageProfileId` 归属 |
| M0-S4 | Authentication / UserContext umbrella | trusted identity、local credential、Session、resource protection 与 deployment mode |
| M0-S5 | Language workspace minimum use case | create/list/switch profile；语言状态硬隔离 |
| M0-S6 | Model Gateway contract | 业务代码不依赖 concrete provider；timeout/error contract 明确 |
| M0-S7 | BYOK transient credential path | Credential 不持久化、不进入 logs/traces/exceptions |
| M0-S8 | Structured Output 与 minimal Trace walking skeleton | invalid output 不落长期状态；metadata 可追踪；unknown Provider finish reason 具有受控诊断策略 |
| M0-S9 | Model Call Job foundation | PostgreSQL Job state + TaskExecutor 回收迟到结果；versioned consume；Credential 不持久化 |

#### M0-S8 Reserved Observability Decision

M0-S8 设计 minimal Trace 时，必须显式处理 Provider raw finish reason：

- 业务 `TextGenerationResponse` 与持久化 Trace 只保存 normalized finish reason；
- 无法可靠映射时返回 `UNKNOWN`，并产生 rate-limited structured warning，记录安全的
  `ProviderId`、`ModelId`、Adapter version 与 Trace ID；
- raw value 符合受限 token allowlist 和长度上限时，warning 可以记录经过结构化转义的值，以便定位
  Provider 新增枚举、Adapter 映射遗漏或响应解析 Bug；
- raw value 不符合 allowlist、包含控制字符或超过长度上限时，不直接记录内容，只记录 missing / invalid
  分类、原始长度与安全 digest，必要时再通过显式启用的受控 Debug 复现；
- 正常、已知的 finish reason 不重复记录 raw value；raw value 不得写入持久化 Trace 或 metric label；
- 受控 Debug 必须定义环境开关、访问控制与保留期限；
- 即使开启受控诊断，也不得记录完整 Provider raw response、Prompt、Conversation、Credential 或其他 Secret。

以上只保留 S8 的设计约束；具体 allowlist、长度、rate limit、digest、日志级别与 Debug 开关留到 S8
Scope / Architecture Decision，不在 S6C 实现。

#### M0-S9 Approved Model Call Job Decision

M0-S9 在 S7 transient Credential 与 S8 Structured Output / Trace foundation 之后实现 V1 backend
`ModelCallJob` foundation：

- production Application Workflow 在调用 Provider 前创建 Job；
- interactive wait timeout 只切换为 pending，不取消仍在最终 execution deadline 内的后台调用；
- execution 与 consumption status 分离；`workflowVersion` 判断 stale，`rowVersion` 保护 consume-once；
- PostgreSQL 保存 durable status / safe typed result，`Spring TaskExecutor` 执行当前内存任务；
- BYOK Credential 不进入 DB、Redis、Trace、Log 或 durable task payload；
- 不引入 Kafka / RabbitMQ、automatic retry、Push Notification 或 generic workflow engine；
- Planner / Evaluator 等内部结果由 owning Workflow 自动消费或标记 stale；用户可感知结果可以进入站内
  confirmation，但不得未经确认自动继续后续 Operation。

Detailed Design 已批准并记录在 [`MODEL_CALL_JOB.md`](../features/MODEL_CALL_JOB.md)。M0-S9 implementation
slice、schema、API 与 file scope 仍需在 S8 完成后单独批准；本决定不扩大当前 M0-S6E implementation。

### M0 Slice Control

- 一个 slice 默认控制在主要 production files `≤ 5`、production changed LOC `≤ 250`；
- 如果实现无法在认知预算内完成，继续拆分，不自动扩大范围；
- 新 dependency、public contract、schema 或核心 abstraction 仍需先经过 Architecture Decision；
- M0-S1 application skeleton、Java 25 原生环境验证、Diff Review 与 Human Ownership Check 已完成。

### M0-S4 Detailed Slices

`M0-S4` 是 Architecture-sensitive Security umbrella，采用 `ADR-0002`。它不能作为一个
大 implementation slice 一次完成，必须按以下认知边界依次推进；每个 slice 完成后停止并
进入独立 Review。

| Slice | Scope | Verification focus |
| --- | --- | --- |
| M0-S4A | Spring Security 与 trusted `UserContext` walking skeleton | unauthenticated request 被拒绝；owner 可访问；cross-user profile 返回 not found；request / LLM `userId` 无 authority |
| M0-S4B1 | `auth_identity` 与 local credential schema / persistence | internal User 与 login channel 分离；identity uniqueness；transaction / FK boundary；不实现外部 Provider |
| M0-S4B2 | Local registration 与 versioned Argon2id verifier | registration atomicity；password policy；hash uniqueness / match / malformed fail-closed / upgrade；raw password 不进入 persistence、Redis、Log 或 Trace |
| M0-S4C1 | Login / Logout / Current User 与 Redis-backed Session | Spring Security lifecycle；Session creation / rotation / expiry / invalidation；cookie boundary；generic authentication failure |
| M0-S4C2 | SPA CSRF、authentication throttling、global hash concurrency gate 与 provisional resource verification | Rate Limit 先于 Argon2；active hash 有硬上限；capacity fail fast；无 unbounded queue / permit leak；restricted-Container mixed workload / recovery evidence |
| M0-S4D | Self-hosted `SINGLE_USER` bootstrap 与 deployment-mode isolation | public registration boundary；server-side identity；Hosted / Self-hosted 产生相同 `UserContext`；misconfiguration fail-closed |

#### M0-S4 Fixed Security Decisions

- Hosted V1 使用 Spring Security + Redis-backed server-side HTTP Session，不使用
  browser-stored JWT；
- Redis-backed HTTP Session 使用 Spring Boot 管理的
  `spring-boot-starter-session-data-redis` 与 auto-configuration，不手写 Session ID / Redis key
  lifecycle，也不无理由用 `@EnableRedisHttpSession` 接管配置；
- local login 使用 form-urlencoded `POST /api/auth/login` 与 Spring Security
  username/password filter → local `AuthenticationProvider` lifecycle；unknown account 仍执行
  dummy Argon2id，credential failure 统一 401，infrastructure failure 使用通用 503，成功后清除
  credentials、rotation Session ID 并返回 204；
- Session attributes 使用 Jackson JSON、Spring Security Jackson modules 与 strict
  `UserContext` allowlist；namespace 为 `daily-language:session:v1`，idle TTL 为 24 hours，不启用
  remember-me，允许多设备且 logout 只失效当前 Session，Redis unavailable 不回退到 JVM
  in-memory Session；
- S4C1 public contract 固定为 CSRF-protected form-urlencoded `POST /api/auth/login`、
  CSRF-protected `POST /api/auth/logout` 与 authenticated `GET /api/auth/me`；login / logout 成功均
  返回 204，`/me` 只返回 `userId`，不提供 GET logout、redirect、saved request 或 mutable
  account profile；
- Session cookie 使用 `SESSION`、`HttpOnly=true`、`SameSite=Lax`、`Path=/`，不设置 Domain 或
  persistent Max-Age；Hosted HTTPS 强制 Secure，local HTTP development 通过显式 profile
  override 关闭 Secure；server-side Redis 24-hour idle TTL 是 Session validity authority；
- local password verifier 使用 code-versioned `argon2id-v1`；algorithm parameters 不是可随意
  调低的 runtime configuration；
- local password policy 接受 12–64 printable ASCII characters（`U+0020`–`U+007E`，包括
  普通半角空格），不做 composition rule、trim、字符替换或 Unicode normalization；该 V1
  usability / compatibility trade-off 低于当前 NIST password-only 15-character minimum；
- offline blocklist 固定为 SecLists 2026.1 Top 250,000 prefix 经 12–64 printable ASCII exact
  filter 生成的 sorted binary SHA-256 fingerprints；baseline 是 2,065 entries / 66,080 bytes，
  只在 registration / password change / reset 的 Argon2id 前检查；
- registration failure 只在 `LocalRegistrationService` 记录 structured safe metadata；expected
  policy / duplicate rejection 不记 ERROR，unexpected failure 禁止记录 email、raw password、
  fingerprint、verifier、SQL parameter、database exception message 或完整 cause chain；
- PostgreSQL 只保存 encoded verifier，不保存 plaintext 或 reversible encrypted password；
- Session cookie、CSRF、session fixation protection、logout invalidation 与 same-site deployment
  boundary 必须在 M0-S4 内验证；
- `app_user` 是内部稳定 identity，login channel 通过 `auth_identity` 分离；
- Sign in with Apple、phone OTP、Passkey、其他 OIDC Provider 与 Account Linking 保留在
  `IDEA-006`；
- pepper 不在 M0-S4 自行实现，由 M6 Hosted Security Gate 在具备 managed secret lifecycle
  后重新评估。

#### M0-S4B1 Scope

Production files 限定为：

1. `server/src/main/resources/db/migration/V2__add_local_authentication_identity.sql`；
2. `server/src/main/java/com/dailylanguage/authentication/LocalEmailNormalizer.java`；
3. `server/src/main/java/com/dailylanguage/authentication/LocalAuthenticationMapper.java`；
4. `server/src/main/java/com/dailylanguage/authentication/LocalAuthenticationRepository.java`；
5. `server/src/main/resources/mapper/LocalAuthenticationMapper.xml`。

Repository 只暴露 encoded-verifier persistence 与 local credential lookup；identity + credential
使用内部 transaction，未来可加入 S4B2 registration 的外层 transaction。Sensitive lookup
record 保持在 authentication package，不进入 Controller DTO、Log、Trace 或 Domain Event。

Focused verification 限定为：

- normalized email identity 与 UUIDv7；
- `(provider, provider_subject)` uniqueness；
- missing `app_user` foreign key rejection；
- one credential per identity；
- credential insert 失败时 identity rollback；
- `app_user → auth_identity` RESTRICT 与 `auth_identity → credential` CASCADE；
- Mapper 继续只使用 prepared parameter binding。

明确不进入本 slice：public Register/Login API、raw password、Argon2id dependency / hashing、
password policy / compromised-password check、Session、CSRF、Rate Limit 与外部 Provider。

#### M0-S4B2b Scope

Production files 限定为：

1. `server/src/main/java/com/dailylanguage/authentication/LocalPasswordPolicy.java`；
2. `server/src/main/java/com/dailylanguage/authentication/LocalPasswordBlocklist.java`；
3. `server/src/main/resources/security/local-password-blocklist-v1.bin`。

Development-only generation tool 限定为：

4. `server/tools/GenerateLocalPasswordBlocklist.java`。

Policy 只接受 12–64 printable ASCII，按完整 candidate 检查 normalized email / local part 与
offline blocklist。Blocklist runtime 只加载并查询 pinned、strictly sorted binary SHA-256
fingerprints；generator 验证固定 source checksum 后生成 asset，不进入 Maven build 或 runtime。

Focused verification 限定为：

- 11 / 12 / 64 / 65 长度边界与完整 printable-ASCII range；
- control、Unicode、full-width 与 emoji rejection，且不 trim / normalization；
- common、service-context、email 与 email-local-part rejection；
- pinned entry count / checksum、strict ordering、malformed fail-closed 与 concurrent reads；
- raw password 不进入 output；固定 source 可生成 byte-identical asset。

明确不进入本 slice：Register/Login Controller、registration transaction、数据库、Redis
Session、Rate Limit、password reset API、HIBP network integration、frontend message component、
新 dependency 或 `LocalPasswordHasher` 修改。

#### M0-S4C1 Feature Plan

Status: COMPLETE — API contract、task breakdown、implementation、verification、Review、Ownership 与
人工 commit / push 均已完成。

Goal：在不手写 Session lifecycle 的前提下，通过 Spring Security + Spring Session 建立 local
login、current-user、current-session logout 的 Redis-backed authentication lifecycle。

Ownership Level：A — Authentication / Session / CSRF / public API security boundary。

Target flow：

```text
POST /api/auth/login + CSRF
    → Spring Security username/password filter
    → LocalPasswordAuthenticationProvider
    → credential lookup + dummy/real Argon2id verification
    → authenticated UserContext(userId), credentials cleared
    → Session ID rotation
    → Redis-backed SecurityContext

SESSION cookie
    → Spring Session restores SecurityContext
    → GET /api/auth/me
    → {"userId":"<uuid>"}

POST /api/auth/logout + CSRF
    → invalidate current Redis Session
    → clear SecurityContext + SESSION cookie
    → 204 No Content
```

Public contract：

| Endpoint | Success | Failure boundary |
| --- | --- | --- |
| `POST /api/auth/login` | `204` + rotated `SESSION` cookie | credential rejection `401 INVALID_CREDENTIALS`；infrastructure unavailable `503 AUTHENTICATION_UNAVAILABLE`；CSRF rejection `403` |
| `POST /api/auth/logout` | `204`，仅当前 Session 失效 | CSRF rejection `403` |
| `GET /api/auth/me` | `200 {"userId":"<uuid>"}` | unauthenticated / expired Session `401 UNAUTHENTICATED` |

Cookie contract：`SESSION`、HttpOnly、SameSite=Lax、Path `/`、无 Domain、无 persistent Max-Age；
Hosted Secure=true，local HTTP development 显式 Secure=false。Cookie 不携带 user data；Redis
24-hour idle TTL 是有效期 authority。

Task breakdown：

| Task | Goal | Ownership | Depends on |
| --- | --- | --- | --- |
| M0-S4C1a | 引入 Boot-managed Spring Session Redis、JSON / Security serialization allowlist、namespace、idle TTL 与 Cookie configuration；验证 Redis restore / expiry / fail-closed | A | 已批准 S4C1 dependency / Session policy |
| M0-S4C1b | 实现 local `AuthenticationProvider`，覆盖 normalize / lookup、unknown-account dummy Argon2id、uniform credential rejection、infrastructure failure 与 credential clearing | A | S4B1 repository、S4B2a hasher、S4C1a Session foundation |
| M0-S4C1c | 接入 login/logout/me HTTP contract，验证 CSRF、Session rotation / reuse / expiry / invalidation、Cookie 与 error response | A | S4C1a、S4C1b |

Architecture impact：新增已批准的 Spring Session Redis starter；修改 Spring Security / Session
configuration 与 public authentication API；不修改 database schema、transaction boundary、
learning Domain、Model / Tool boundary 或 Hosted / Self-hosted core business path。

Explicit non-goals：SPA CSRF token delivery、registration Controller、rate limit、global Argon2id
concurrency gate、frontend、remember-me、device list、logout-all、maximum-session policy、external
Provider、password reset、email verification，以及 `IDEA-007` Account Profile / `display_name`。

Current implementation state：`M0-S4C1`、`M0-S4C2` 与 `M0-S4D` 均已完成独立 Design / Scope、
implementation、verification、Review 与 Ownership。下一 task 是 `M0-S5`，必须先完成独立
Design 与 Scope，不得直接实现 Language workspace use case。

##### M0-S4C1a Completed Task Contract

Status: SCOPE APPROVED；IMPLEMENTATION / VERIFICATION / REVIEW / OWNERSHIP COMPLETE；COMPLETE。

Goal：使用 Spring Boot 4.1 auto-configuration 建立 Redis-backed `HttpSession` foundation，固定
Jackson 3 Security serialization allowlist、Redis namespace、24-hour idle TTL 与 Session cookie
configuration，并形成真实 Redis / unavailable Redis verification evidence。

Files to modify：

1. `server/pom.xml`：用 `spring-boot-starter-session-data-redis` 替代当前直接的
   `spring-boot-starter-data-redis`；由 Maven dependency tree 验证 Redis client / health 所需依赖
   仍由 Session starter 提供；
2. `server/src/main/resources/application.yml`：配置 `spring.session.timeout=24h`、Boot 4.1
   `spring.session.data.redis.namespace=daily-language:session:v1`、显式 default repository 与
   `SESSION` cookie boundary；Hosted `Secure=true` 为默认值，local HTTP 只允许显式 externalized
   configuration override；
3. `README.md`：记录 local HTTP 的 cookie Secure override 与 Redis integration test command。

Files to add：

1. `server/src/main/java/com/dailylanguage/security/SessionConfiguration.java`：只提供固定 bean name
   `springSessionDefaultRedisSerializer`；使用 Jackson 3 `JacksonJsonRedisSerializer<Object>`、
   `SecurityJacksonModules` 和只额外允许 `UserContext` 的 `BasicPolymorphicTypeValidator`；
2. `server/src/test/java/com/dailylanguage/security/SessionSerializationConfigurationTests.java`：
   验证 authenticated `UserContext` / SecurityContext JSON round trip、非 Java native serialization
   与未 allowlist custom type fail-closed；
3. `server/src/test/java/com/dailylanguage/security/RedisSessionIntegrationTests.java`：由
   `RUN_REDIS_TESTS=true` 显式启用，验证 default non-indexed repository、namespace、24-hour idle
   interval 与跨 repository read 的 SecurityContext round trip；
4. `server/src/test/java/com/dailylanguage/security/RedisSessionUnavailableIntegrationTests.java`：
   使用短 Redis connection timeout 与不可用 test port，验证 Session persistence failure 不回退到
   servlet/JVM in-memory Session。

Actual diff：主要 production files 3 个（其中 1 个新增），production changed LOC 约 55；test
files 3 个；documentation 1 个。若 serializer 需要修改 `UserContext` annotation、
新增 custom Session abstraction 或手工 repository/filter，即视为 Scope Change，必须停止。

Architecture impact：使用已批准的 Spring Session Redis starter 与 Boot auto-configuration；不使用
`@EnableRedisHttpSession`，不手写 Session ID、Redis key、repository 或 servlet filter lifecycle。

Database/API/transaction impact：NONE。C1a 不新增 endpoint，不修改 database schema、registration
transaction 或 `SecurityConfiguration` authentication rules。

Security impact：Session attribute 从 Java native serialization 改为带 Spring Security modules 的
Jackson 3 JSON；polymorphic type validation 只放行 Spring Security 所需类型与 `UserContext`；
Redis unavailable 时 fail closed；Cookie 不携带 user data。

Explicitly out of scope：`AuthenticationProvider`、login/logout/me endpoint、CSRF token delivery、
Session rotation/invalidation HTTP behavior、rate limit、Argon2id concurrency gate、Account Profile、
`display_name`、frontend 与 Hosted hardware capacity confirmation。

Verification evidence：

- serializer focused test：SecurityContext / UserContext JSON round trip、credentials remain null、
  Java native serialization signature absent、non-allowlisted type fail-closed；
- Redis-unavailable focused test：唯一 `SessionRepository` 为 `RedisSessionRepository`，save 在
  unavailable Redis 上抛出 `DataAccessException`，无 JVM repository fallback；
- real Redis integration：default repository、24-hour interval、`daily-language:session:v1`
  namespace、second repository instance restore、JSON payload 与 Hosted Cookie property binding；
- full backend default regression：PASS；真实 Redis test key 已在 finally 清理并通过 scan 确认。

##### M0-S4C1b Completed Task Contract

Status: DESIGN / SCOPE APPROVED；IMPLEMENTATION / VERIFICATION / REVIEW / OWNERSHIP COMPLETE；
COMPLETE。

Goal：通过 Spring Security `AuthenticationProvider` 连接现有 local credential repository 与
`LocalPasswordHasher`，使成功认证只产生 credentials 已清除的 `UserContext(userId)`；unknown
account、malformed email 与 wrong password 采用统一 credential rejection，unexpected repository /
hasher failure 转换为不泄露底层详情的 authentication-unavailable failure。

Implemented behavior：

1. `LocalPasswordAuthenticationProvider` 只支持 `UsernamePasswordAuthenticationToken`，从请求
   token 读取 submitted email / password，并由 repository 复用既有 normalization 与 credential
   lookup boundary；
2. known account 校验 stored password hash；unknown / malformed account 校验启动时生成且不持久化的
   unknown-account password hash，避免用 Hash 工作量直接暴露账号是否存在；
3. lookup / password verification 的 unexpected `RuntimeException` 分别映射为安全的
   `ACCOUNT_LOOKUP` / `PASSWORD_VERIFY` stage，只记录 exception type，不记录 email、password、Hash、
   exception message 或 cause chain；
4. 成功结果的 principal 是 `UserContext(userId)`、credentials 为 `null`；原始 login request 在
   `finally` 中清除 credentials，成功和失败路径均覆盖；
5. 删除仅用于 walking skeleton 的空 `InMemoryUserDetailsManager`，让 Spring Security 使用当前
   `AuthenticationProvider`；C1c 才接入 HTTP login filter、failure handler 与 Session lifecycle。

Approved clarity scope：为降低 authentication 代码理解成本，将既有 `verifier`、`credential`、
`rawPassword` 等容易混淆的局部命名统一为更直白的 `storedPasswordHash`、`submittedPassword`、
`authenticationIdentityId` 等语义，并同步 mapper、registration、language-profile 调用点与测试；
该命名整理不改变 database schema、SQL column、public HTTP API 或 Domain semantics。

Explicitly out of scope：login / logout / me HTTP endpoint、Session creation / rotation / invalidation、
CSRF token delivery、rate limit、global Argon2id concurrency gate、password re-hash persistence、
Account Profile、frontend 与 external Provider。

Verification evidence：

- provider focused tests：成功 principal / null credentials、request credential clearing、unknown / malformed
  account fallback Hash、wrong / missing password uniform rejection、unsupported token 与 safe failure mapping；
- regression for failure classification：repository `NullPointerException` 不再伪装成 invalid credential，
  与 database failure 一样进入 authentication-unavailable path；
- PostgreSQL integration：真实持久化 local credential 可通过 provider 认证；
- related hasher、repository 与 persistence tests：PASS；最终 provider focused tests 9 tests、0 failures、
  0 errors；`git diff --check` PASS。

##### M0-S4C1c Completed Task Contract

Status: DESIGN / SCOPE APPROVED；IMPLEMENTATION / VERIFICATION / REVIEW / OWNERSHIP COMPLETE；
COMPLETE。

Goal：使用 Spring Security framework-managed form login / logout 与 Spring Session Redis 接入
`POST /api/auth/login`、`POST /api/auth/logout`、`GET /api/auth/me` public contract；成功认证只把
credentials 已清除的 `UserContext(userId)` 保存进 Redis Session，Session storage unavailable 时
fail closed 并返回固定、无敏感细节的 HTTP error。

Implemented behavior：

1. `SecurityConfiguration` 配置 form-urlencoded login、current-session logout、Session ID rotation、
   request cache / HTTP Basic disable，以及 authenticated-request entry point；不生成 HTML login page；
2. credential rejection 返回 `401 INVALID_CREDENTIALS`，未认证访问返回 `401 UNAUTHENTICATED`，
   authentication / Session infrastructure unavailable 返回 `503 AUTHENTICATION_UNAVAILABLE`；
3. `GET /api/auth/me` 只从 authenticated `UserContext` 返回 `userId`，request body / parameter 对身份
   没有 authority；
4. logout 清除 Authentication 并 invalidate 当前 `HttpSession`；Redis Session 删除与过期 Cookie
   由 Spring Session lifecycle 单独负责，不重复手写 `SESSION` Cookie 删除；
5. `SessionStorageFailureFilter` 只在 login / logout / me 边界映射
   `RedisConnectionFailureException`，保留已写入的 Spring Security response headers，并不把其他
   `RuntimeException` 伪装成 Session storage failure。

Architecture / Scope impact：production files 4 个，沿用已批准的 Spring Security、Spring Session
Redis 与 local `AuthenticationProvider` boundary；不新增 dependency、database schema、transaction、
custom Session repository 或手写 Session ID / Redis key lifecycle。Implementation commit：`5b191f7`。

Explicitly out of scope：SPA CSRF token delivery、registration Controller、rate limit、global Argon2id
concurrency gate、password re-hash persistence、Account Profile、frontend、external Provider、password
reset、email verification、remember-me、device list、logout-all 与 maximum-session policy。

Verification evidence：

- HTTP contract focused tests：login success / failure、Session ID rotation、CSRF rejection、logout
  idempotency、current-user 与 fixed error response；
- real PostgreSQL + Redis integration：真实 registration / Argon2id / provider login、Redis
  SecurityContext restore、credentials remain null、Session deletion 与单一安全 Cookie expiry；
- Redis-unavailable integration：login save 与 `/me` restore 均返回固定 503，response 不泄露 Redis
  connection detail，并保留 Spring Security headers；
- C1c combined suite 12 tests 全部通过；full backend default regression 与 `git diff --check` PASS。

#### M0-S4 Capacity Decision

Argon2id security parameters 现在确定；password-hash concurrency 由 hardware capacity 决定：

```text
DEV / TEST default:       1
Self-hosted safe default: 1
Hosted:                   explicit configuration required
```

M0-S4C2 只在受限 Container profile 上形成 `PROVISIONAL` evidence。Hosted 未显式配置
`maxConcurrentHashes` 时 startup fail。M6 在真实或等价 Hosted hardware 上完成 benchmark、
open-model login load、mixed workload、soak 与 recovery test 后，才能把 capacity 标记为
`CONFIRMED`。

M0-S4C2d 已在 `1 CPU / 512 MiB / JVM Xmx 256 MiB` 的本地受限 Container 中完成 saturation、
mixed authentication workload 与 recovery verification，结果为 `PASS / PROVISIONAL`。实际 profile、
latency、resource / GC observations 与限制记录在
[`server/verification/m0-s4c2d/PROVISIONAL_RESULTS.md`](../../server/verification/m0-s4c2d/PROVISIONAL_RESULTS.md)。

#### M0-S4D Final Registration Capability Decision

最终实现使用 `REGISTRATION_ENABLED`，不新增独立 deployment-mode enum：

- 默认 `false`：关闭 public registration，原子 bootstrap / reuse persistent singleton User，并通过
  Security Filter 建立可信 `UserContext`；login 在 Rate Limit / Argon2 前隐藏；
- 显式 `true`：开放 registration，使用正常 login 与 Redis Session；
- 两条路径共用后续 Domain / authorization path；Hosted 应显式设置 `true`，Self-hosted 可以按需
  选择 singleton 或 public multi-user registration。

#### M0-S4 Exit Criteria

- request identity 只能来自 authenticated `UserContext`；
- cross-user Language Profile access 在 HTTP → Service → Repository 全链被阻止；
- local registration、login、logout 与 current-user request 可验证；
- password storage、Session、CSRF 与 authentication failure path 满足 ADR-0002；
- Rate Limit 与 global password-hash concurrency gate 阻止 Argon2 resource exhaustion；
- raw password / verifier / Session secret 不进入禁止的 persistence、Log 或 Trace boundary；
- Hosted / Self-hosted 共用 Domain / authorization path；
- 每个子 slice 完成 focused verification、Diff Review 与 Human Ownership Check。

Closeout：以上 Exit Criteria 已于 2026-08-29 基于 committed implementation、自动 / 人工 flow、
真实 PostgreSQL / Redis integration、restricted-Container provisional evidence 与 Human Ownership
审计完成。Hosted capacity confirmation 仍是 M6 的明确 deferred item，不阻塞 M0-S5。

### M0-S6 Approved Detailed Design

`M0-S6` 是 A 类核心 AI infrastructure slice。2026-08-29 已批准
[`MODEL_GATEWAY.md`](../features/MODEL_GATEWAY.md) 中的 Detailed Design：

- Model Gateway 是 provider-agnostic logical module boundary，不是万能 Java interface；
- 每次调用只执行一个明确 `ModelOperation`；
- fixed route key 使用 `ModelPurpose + ModelOperation`；
- 不同 Operation 使用独立 Typed Port 与 Request / Response；
- M0-S6 只实现第一个 Text Generation Port，不提前实现 Speech / Vision / Image / Embedding；
- Gateway 负责 capability check、timeout 与 safe failure translation；
- Application Workflow 负责多 Operation 的顺序、REQUIRED / OPTIONAL、partial success 与 degradation；
- 默认不自动 retry，也不静默 cross-provider fallback；
- Model infrastructure failure 不产生 Learning Evidence 或长期学习状态变化；
- Credential、Structured Output validation 与 minimal Trace 分别留在 M0-S7 / M0-S8；
- S6 不新增 Spring AI 或 concrete Provider SDK dependency。

#### Proposed M0-S6 implementation slices

| Slice | Goal | Observable behavior | Status |
| --- | --- | --- | --- |
| M0-S6A | Portable route vocabulary | 可以类型化表示 Purpose + Operation route 与 Provider / Model identity；无 Provider SDK type | COMPLETE |
| M0-S6B | Typed result / failure contract | 调用方可以显式区分 success 与 normalized operational failure；failure 不携带 unsafe detail | COMPLETE |
| M0-S6C | Text Generation Typed Port | 调用方只通过 provider-neutral text Request / Response contract 发起 Text Generation；无万能 option Map | COMPLETE |
| M0-S6D | Fixed route 与 Provider Adapter seam | Purpose + Text Generation 解析为 configured Provider / Model；unsupported route / capability 明确失败 | COMPLETE |
| M0-S6E | Timeout 与 safe failure translation | slow / rejected / unavailable fake Adapter 被转换为稳定 failure；默认无 retry / cross-provider fallback | COMPLETE (`c374449`) |
| M0-S6F | Integrated contract verification 与收口 | dependency boundary、routing、timeout、failure isolation、Diff Review 与 Ownership Check 完成 | PARTIAL — implementation / Architecture / verification / docs PASS；Ownership L2 |

以上是已批准 Design 下的 implementation slices。`M0-S6A` 至 `M0-S6E` 已完成并提交。`M0-S6F`
已完成 integrated closeout：implementation、Architecture、verification 与 Documentation PASS；Model Gateway
Ownership 保持 L2，因此 closeout 结果为 `PARTIAL`，但不构成 S7 Design / Scope 的实现阻塞。

#### Proposed M0-S6A Current Slice Contract

```text
Task / Slice: M0-S6A — Portable route vocabulary
Goal: 建立 Provider-neutral 的 Purpose、Operation、route key 与 Provider / Model identity。
Expected Behavior:
- ModelPurpose 与 ModelOperation 是两个独立受控维度；
- 相同 Purpose 的不同 Operation 形成不同 route key；
- ProviderId / ModelId 接受外部可配置 identifier，但拒绝 null、blank 与未规范化外围空白；
- 所有类型均不依赖 Spring AI、Provider SDK、HTTP 或 Credential。
Expected Production Files:
- server/src/main/java/com/dailylanguage/modelgateway/routing/ModelPurpose.java
- server/src/main/java/com/dailylanguage/modelgateway/routing/ModelOperation.java
- server/src/main/java/com/dailylanguage/modelgateway/routing/ModelRouteKey.java
- server/src/main/java/com/dailylanguage/modelgateway/routing/ProviderId.java
- server/src/main/java/com/dailylanguage/modelgateway/routing/ModelId.java
Expected Tests:
- server/src/test/java/com/dailylanguage/modelgateway/routing/ModelRouteKeyTests.java
- server/src/test/java/com/dailylanguage/modelgateway/routing/ProviderModelIdentityTests.java
Architecture / Data / API / Security Impact:
- 实现已批准的 Model Gateway contract；无 schema、public HTTP API、Credential 或 dependency 变化。
Explicitly Out of Scope:
- ModelResult / ModelFailure；Text Generation Port；route configuration；Provider Adapter；
  timeout；retry；fallback；BYOK；Structured Output；Trace；任何 Workflow 或 Learning State mutation。
Verification:
- focused unit tests；server compile；targeted Diff review。
```

#### Proposed M0-S6B Current Slice Contract

```text
Task / Slice: M0-S6B — Typed result / failure contract
Goal: 建立调用方必须显式处理的 success / normalized operational failure contract。
Expected Behavior:
- ModelResult<T> 是 sealed result，只允许非 null Success<T> 或非 null Failure<T>；
- ModelFailureKind 固化 S6 approved taxonomy：CAPABILITY_UNAVAILABLE、REQUEST_REJECTED、
  AUTHENTICATION_FAILED、RATE_LIMITED、TIMEOUT、TEMPORARY_UNAVAILABLE、PROVIDER_FAILURE；
- ModelFailure 必须包含 kind，可以不含 route identity，也可以同时包含 ProviderId + ModelId；
- ProviderId / ModelId 必须同时存在或同时缺失，不允许 partial route identity；
- retryAfter 是 optional positive Duration，只允许 RATE_LIMITED / TEMPORARY_UNAVAILABLE 使用；
- retryAfter 存在时必须同时存在 ProviderId + ModelId；
- Failure 不提供 raw response、exception message、stack trace、Prompt、Credential 或 arbitrary metadata。
Expected Production Files:
- server/src/main/java/com/dailylanguage/modelgateway/result/ModelResult.java
- server/src/main/java/com/dailylanguage/modelgateway/result/ModelFailure.java
- server/src/main/java/com/dailylanguage/modelgateway/result/ModelFailureKind.java
Expected Tests:
- server/src/test/java/com/dailylanguage/modelgateway/result/ModelResultTests.java
- server/src/test/java/com/dailylanguage/modelgateway/result/ModelFailureTests.java
Architecture / Data / API / Security Impact:
- 实现已批准的 explicit failure contract；无 schema、public HTTP API、Credential 或 dependency 变化。
Critical Code Expected:
- ModelResult 的 success / failure exclusivity 与 null rejection；
- ModelFailure 的 route identity pairing 与 retryAfter invariant。
Explicitly Out of Scope:
- Text Generation Request / Response / Port；route configuration；Provider Adapter / exception translation；
  timeout execution；retry；fallback；BYOK；Structured Output；Trace；Workflow / Learning State mutation。
Verification:
- focused unit tests；S6A + S6B domain regression；server compile；targeted Diff review。
```

#### Approved M0-S6C Current Slice Contract

```text
Task / Slice: M0-S6C — Text Generation Typed Port
Goal: 让业务调用方只通过 provider-neutral typed request / response 发起单次 Text Generation。
Expected Behavior:
- TextGenerationPort 只接受 TextGenerationRequest，并返回 ModelResult<TextGenerationResponse>；
- Request 包含 ModelPurpose、非空有序 messages 与 TextOutputSpecification，不包含 Provider / Model、
  Credential、Domain identity、timeout、Trace detail 或 arbitrary options；
- TextMessage 使用内部 INSTRUCTION / USER / MODEL role；content 拒绝 null / blank，但不自动 trim；
- TextOutputSpecification 是为已确认 S8 Structured Output change axis 保留的 sealed typed boundary，
  S6C 只实现 PlainText；
- Response 包含 selected ProviderId / ModelId、非 null text、normalized finish reason 与 optional ModelUsage；
- finish reason 固化为 COMPLETED、LENGTH_LIMIT、CONTENT_FILTERED、UNKNOWN；
- ModelUsage 只保存非负 inputTokens / outputTokens，不估算、不包含 cost 或 Provider-specific usage。
Expected Production Files:
- server/src/main/java/com/dailylanguage/modelgateway/text/TextGenerationPort.java
- server/src/main/java/com/dailylanguage/modelgateway/text/TextGenerationRequest.java
- server/src/main/java/com/dailylanguage/modelgateway/text/TextMessage.java
- server/src/main/java/com/dailylanguage/modelgateway/text/TextOutputSpecification.java
- server/src/main/java/com/dailylanguage/modelgateway/text/TextGenerationResponse.java
- server/src/main/java/com/dailylanguage/modelgateway/result/ModelUsage.java
Expected Tests:
- server/src/test/java/com/dailylanguage/modelgateway/text/TextGenerationRequestTests.java
- server/src/test/java/com/dailylanguage/modelgateway/text/TextGenerationResponseTests.java
- server/src/test/java/com/dailylanguage/modelgateway/text/TextGenerationPortTests.java
Architecture / Data / API / Security Impact:
- 新增第一个 Typed Operation Port；无 schema、HTTP API、Credential、Provider SDK 或 dependency 变化。
Critical Code Expected:
- messages defensive copy、顺序保持与 null / blank rejection；
- ModelResult<TextGenerationResponse> 的显式 success / failure；
- Response portable fields 与 token usage invariant。
Explicitly Out of Scope:
- route resolution；Provider Adapter / actual call；Credential；timeout；failure translation；retry / fallback；
  Structured Output schema；Tool Calling；sampling options；Trace implementation；Workflow / Learning State mutation。
Verification:
- focused S6C tests；S6A + S6B + S6C contract regression；server compile；targeted Diff review。
```

#### Approved M0-S6D Current Slice Contract

```text
Task / Slice: M0-S6D — Fixed route and Provider Adapter seam
Goal: 让 TextGenerationPort 通过 fixed Purpose + TEXT_GENERATION route 选择 Provider / Model，并只调用
对应的 operation-specific Adapter 一次。
Expected Behavior:
- TextGenerationRoute 将 ProviderId、ModelId 与 TextGenerationProviderAdapter 组合为可执行 runtime route；
- FixedTextGenerationRoutes defensive-copy route map，只接受 TEXT_GENERATION key，不实现自动 routing；
- RoutedTextGenerationPort 使用 request purpose + TEXT_GENERATION 查找 route；
- route 缺失返回无 Provider / Model identity 的 CAPABILITY_UNAVAILABLE；
- route 存在时只调用绑定 Adapter 一次，并原样传播合法 ModelResult；
- Adapter 返回 null，或 Success / Failure route identity 与 selected route 不一致，视为 programming / wiring
  bug 并 fail fast，不转换为普通 Provider failure。
Expected Production Files:
- server/src/main/java/com/dailylanguage/modelgateway/text/execution/TextGenerationProviderAdapter.java
- server/src/main/java/com/dailylanguage/modelgateway/text/execution/TextGenerationRoute.java
- server/src/main/java/com/dailylanguage/modelgateway/text/execution/FixedTextGenerationRoutes.java
- server/src/main/java/com/dailylanguage/modelgateway/text/execution/RoutedTextGenerationPort.java
Expected Tests:
- server/src/test/java/com/dailylanguage/modelgateway/text/execution/FixedTextGenerationRoutesTests.java
- server/src/test/java/com/dailylanguage/modelgateway/text/execution/RoutedTextGenerationPortTests.java
Architecture / Data / API / Security Impact:
- 新增 approved fixed routing 与 operation-specific external Adapter seam；无 schema、HTTP API、Credential、
  Provider SDK、Spring wiring 或 dependency 变化。
Critical Code Expected:
- immutable route mapping 与 TEXT_GENERATION-only invariant；
- no-route CAPABILITY_UNAVAILABLE；
- exactly-once Adapter delegation 与 returned route identity validation。
Explicitly Out of Scope:
- concrete Provider / HTTP / SDK call；external configuration binding；Credential；timeout；exception translation；
  retry / fallback；Trace；Workflow / Learning State mutation。
Verification:
- focused S6D tests；S6A-S6D Model Gateway regression；server compile；targeted Diff review。
```

#### Approved M0-S6E Current Slice Contract

```text
Task / Slice: M0-S6E — Final execution timeout and safe Provider failure translation
Goal: 为 routed Text Generation 增加单次 Provider call 的最终 execution deadline，并安全归一化已知
Provider operational exception。
Expected Behavior:
- TextGenerationRoute 必须配置 positive Duration executionTimeout，不提供 silent default；
- TextGenerationProviderAdapter 同时接收 selected ProviderId、ModelId、request 与 executionTimeout；
- Gateway 使用同一个 executionTimeout 控制外层 final deadline，Adapter 使用它配置 Provider HTTP / client timeout；
- RoutedTextGenerationPort 通过注入的 dedicated JDK ExecutorService 执行 Adapter，一次 route 只调用一次 Adapter；
- deadline 到期时 best-effort future.cancel(true)，返回包含 selected route identity 的 TIMEOUT；
- timeout 不证明 Provider 已停止、未执行或未计费，也不授权自动 retry / fallback；
- checked ModelProviderCallException 只携带 ModelFailureKind 与 optional positive retryAfter，不携带 arbitrary
  message、raw response、SDK exception / cause、Prompt、Credential 或 metadata；
- typed Provider exception 转为带 selected route identity 的 ModelFailure；只有显式 PROVIDER_FAILURE 才返回
  PROVIDER_FAILURE，不使用 catch-all Provider failure；
- caller interrupt 恢复 interrupt flag 后 fail fast；executor rejection 与 unclassified RuntimeException 以不泄漏
  raw message / cause 的安全 internal failure fail fast；Error 不转换；
- Adapter 返回 null，或 Success / Failure route identity mismatch，继续作为 programming / wiring bug fail fast；
- S6E executor 只负责 final Model call deadline；M0-S9 TaskExecutor 负责 Job lifecycle / interactive waiting，
  两者不得共用同一个 bounded fixed pool。
Expected Production Files:
- server/src/main/java/com/dailylanguage/modelgateway/execution/ModelProviderCallException.java
- server/src/main/java/com/dailylanguage/modelgateway/text/execution/TextGenerationProviderAdapter.java
- server/src/main/java/com/dailylanguage/modelgateway/text/execution/TextGenerationRoute.java
- server/src/main/java/com/dailylanguage/modelgateway/text/execution/RoutedTextGenerationPort.java
Expected Tests:
- server/src/test/java/com/dailylanguage/modelgateway/execution/ModelProviderCallExceptionTests.java
- server/src/test/java/com/dailylanguage/modelgateway/text/execution/TextGenerationRouteTests.java
- server/src/test/java/com/dailylanguage/modelgateway/text/execution/FixedTextGenerationRoutesTests.java
- server/src/test/java/com/dailylanguage/modelgateway/text/execution/RoutedTextGenerationPortTests.java
Architecture / Data / API / Security Impact:
- 扩展 approved internal Adapter / route execution contract；不改变 public HTTP API、Request / Response、schema、
  Credential persistence 或 dependency；ExecutorService 由外部注入，本 slice 不新增 Spring wiring。
Critical Code Expected:
- positive timeout invariant 与同一 Duration 的内外层传播；
- exactly-once Adapter delegation、deadline cancellation 与 route-aware TIMEOUT；
- safe typed exception translation、retryAfter invariant 与 unknown/internal failure boundary；
- no retry / fallback，以及 Job TaskExecutor 与 model-call ExecutorService 的职责隔离。
Explicitly Out of Scope:
- M0-S9 Job persistence、interactive pending、late-result capture / consume；
- concrete Provider / HTTP / SDK integration 与 Spring Executor bean / configuration；
- BYOK implementation、retry、fallback、circuit breaker、Structured Output、Trace；
- Workflow / Learning State mutation，或保证 timeout 后 Provider 停止执行。
Verification:
- focused S6E tests；S6A-S6E Model Gateway regression；server compile；git diff check；targeted Diff review。
```

## 4. Later-phase Planning Rule

M1–M6 当前只批准 Goal、顺序与 exit criteria，不预先生成详细 implementation tasks。

当某个 Phase 即将开始时，只根据已经存在的真实代码和前一 Phase 结果拆分 slices，避免 speculative abstraction 与过早计划漂移。

## 5. Gate Status

```text
Design: APPROVED
M0-S1 Scope: APPROVED
M0-S1: COMPLETE
M0-S2 Scope: APPROVED
M0-S2 Implementation: COMPLETE
M0-S2 Review: COMPLETE
M0-S2 Ownership Check: COMPLETE
M0-S2: COMPLETE
M0-S3 Scope: APPROVED
M0-S3 Implementation: COMPLETE
M0-S3 Verification: COMPLETE
M0-S3 Review: COMPLETE
M0-S3 Ownership Check: COMPLETE
M0-S3: COMPLETE
M0-S4 Architecture Decision: ACCEPTED (ADR-0002)
M0-S4 Scope: APPROVED
M0-S4A Implementation: COMPLETE
M0-S4A Verification: COMPLETE
M0-S4A Review: COMPLETE
M0-S4A Ownership Check: COMPLETE
M0-S4A: COMPLETE
M0-S4B1 Design: APPROVED
M0-S4B1 Scope: APPROVED
M0-S4B1 Implementation: COMPLETE
M0-S4B1 Verification: COMPLETE
M0-S4B1 Review: COMPLETE
M0-S4B1 Ownership Check: COMPLETE
M0-S4B1: COMPLETE
M0-S4B2 Design: APPROVED
M0-S4B2a Scope: APPROVED
M0-S4B2a Implementation: COMPLETE
M0-S4B2a Verification: COMPLETE
M0-S4B2a Review: COMPLETE
M0-S4B2a Ownership Check: COMPLETE
M0-S4B2a: COMPLETE
M0-S4B2b Scope: APPROVED
M0-S4B2b Implementation: COMPLETE
M0-S4B2b Verification: COMPLETE
M0-S4B2b Review: COMPLETE
M0-S4B2b Ownership Check: COMPLETE
M0-S4B2b: COMPLETE
M0-S4B2c Design: APPROVED
M0-S4B2c Scope: APPROVED
M0-S4B2c Implementation: COMPLETE
M0-S4B2c Verification: COMPLETE
M0-S4B2c Review: COMPLETE
M0-S4B2c Ownership Check: COMPLETE
M0-S4B2c: COMPLETE
M0-S4C1 API Contract: APPROVED
M0-S4C1 Design: APPROVED
M0-S4C1a Scope: APPROVED
M0-S4C1a Implementation: COMPLETE
M0-S4C1a Verification: COMPLETE
M0-S4C1a Review: COMPLETE
M0-S4C1a Ownership Check: COMPLETE
M0-S4C1a: COMPLETE
M0-S4C1b Design: APPROVED
M0-S4C1b Scope: APPROVED
M0-S4C1b Implementation: COMPLETE
M0-S4C1b Verification: COMPLETE
M0-S4C1b Review: COMPLETE
M0-S4C1b Ownership Check: COMPLETE
M0-S4C1b: COMPLETE
M0-S4C1c Design: APPROVED
M0-S4C1c Scope: APPROVED
M0-S4C1c Implementation: COMPLETE
M0-S4C1c Verification: COMPLETE
M0-S4C1c Review: COMPLETE
M0-S4C1c Ownership Check: COMPLETE
M0-S4C1c: COMPLETE
M0-S4C1: COMPLETE
M0-S4C2 Detailed Design: APPROVED
M0-S4C2 Scope: APPROVED
M0-S4C2a SPA CSRF: COMPLETE
M0-S4C2b Password Hash Concurrency Gate: COMPLETE
M0-S4C2c Authentication Throttling / Registration HTTP Completion: COMPLETE
M0-S4C2d Restricted-Container Verification: PASS / PROVISIONAL
M0-S4C2 Verification: COMPLETE
M0-S4C2 Review: COMPLETE
M0-S4C2 Ownership Check: COMPLETE
M0-S4C2: COMPLETE
M0-S4D Design: APPROVED
M0-S4D Scope: APPROVED
M0-S4D Implementation: COMPLETE
M0-S4D Verification: COMPLETE
M0-S4D Review: COMPLETE
M0-S4D Ownership Check: COMPLETE
M0-S4D: COMPLETE
M0-S4 Closeout: PASS
M0-S4: COMPLETE
M0-S5: COMPLETE
M0-S6 Detailed Design: APPROVED
M0-S6 Slice Breakdown: PROPOSED (S6A → S6B → S6C → S6D → S6E → S6F)
M0-S6A Scope: APPROVED
M0-S6A Implementation: COMPLETE
M0-S6A Verification: COMPLETE
M0-S6A Review: COMPLETE
M0-S6A Ownership Check: COMPLETE
M0-S6A: COMPLETE (`e6e163a`)
M0-S6B Scope: APPROVED
M0-S6B Implementation: COMPLETE
M0-S6B Verification: COMPLETE
M0-S6B Review: COMPLETE
M0-S6B Ownership Check: COMPLETE
M0-S6B: COMPLETE (`666e2e6`)
M0-S6C Scope: APPROVED
M0-S6C Implementation: COMPLETE
M0-S6C Verification: COMPLETE
M0-S6C Review: COMPLETE
M0-S6C Ownership Check: COMPLETE (contract boundary only)
M0-S6C: COMPLETE (`1a5fcbc`)
M0-S6D Scope: APPROVED
M0-S6D Implementation: COMPLETE
M0-S6D Verification: COMPLETE
M0-S6D Review: COMPLETE
M0-S6D Ownership Check: COMPLETE (L2 traceable route / Adapter path)
M0-S6D: COMPLETE (`1e32ff7`)
M0-S6E Design: APPROVED
M0-S6E Scope: APPROVED
M0-S6E Implementation: COMPLETE
M0-S6E Verification: COMPLETE
M0-S6E Review: COMPLETE
M0-S6E Ownership Check: COMPLETE (L2 timeout / failure execution path)
M0-S6E: COMPLETE (`c374449`)
M0-S6F Scope: APPROVED
M0-S6F Implementation / Architecture / Verification: PASS
M0-S6F Documentation Reconciliation: COMPLETE
M0-S6F Ownership Check: PARTIAL (Model Gateway remains L2)
M0-S6F: PARTIAL (accepted non-blocking L2 Ownership gap)
M0-S6: PARTIAL / ACCEPTED
M0-S7A Design: APPROVED
M0-S7A Scope: APPROVED
M0-S7A Implementation: COMPLETE
M0-S7A Verification: PASS (Model Gateway 43/43；server 183 total / 0 failures / 0 errors / 33 environment-skipped)
M0-S7A Review: COMPLETE (PASS；no blocking findings)
M0-S7A Ownership Check: COMPLETE (Module-local UNDERSTOOD；Model Gateway remains L2)
M0-S7A: COMPLETE (`d8d47ac`)
M0-S7B Design: APPROVED
M0-S7B Scope: APPROVED
M0-S7B Implementation: COMPLETE
M0-S7B Verification: PASS (focused 18/18；Model Gateway 61/61；server 201 total / 0 failures / 0 errors / 33 environment-skipped)
M0-S7B Review: COMPLETE (PASS；no blocking findings)
M0-S7B Ownership Check: COMPLETE (Provider-boundary UNDERSTOOD；Model Gateway remains L2)
M0-S7B: COMPLETE (`7f5f59f`)
M0-S7C Design: APPROVED
M0-S7C Scope: APPROVED
M0-S7C Implementation: COMPLETE
M0-S7C-R1 Configuration Resource Split: COMPLETE / PASS (focused 6/6；server 207 total / 0 failures /
0 errors / 33 environment-skipped)
M0-S7C Verification: PASS (focused 6/6；Model Gateway 67/67；server 207 total / 0 failures / 0 errors / 33 environment-skipped)
M0-S7C Review: COMPLETE (PASS；no blocking findings)
M0-S7C Ownership Check: COMPLETE (Runtime-composition UNDERSTOOD；Model Gateway remains L2)
M0-S7C: COMPLETE (`59c3e24`)
M0-S9 Detailed Design: APPROVED
M0-S9 Implementation Scope: NOT_APPROVED
```

`M0-S6A` 已按批准 Scope 完成 5 个 portable route domain types、focused tests、server compile 与
targeted Diff Review。Human Ownership Check 已确认用户理解 Purpose / Operation 分离、外部 identity
value type 与 enum vocabulary 不等于 runtime capability，并已提交为 `e6e163a`。`M0-S6B` 已按批准
Scope 完成 typed result / failure contract、focused regression、server compile 与 targeted Diff Review；
Human Ownership Check 已确认用户理解 sealed result 的互斥状态、route identity pairing，以及
`retryAfter` 是 metadata 而不是 retry execution，并已提交为 `666e2e6`。`M0-S6C` 已完成 typed
Text Generation request / response / port、focused verification 与 Diff Review。Ownership 明确限制在当前
typed contract，以及识别 route resolution / Provider execution 尚未实现，并已提交为 `1a5fcbc`。
`M0-S6D` 已完成 fixed route、operation-specific Adapter seam、single delegation、route identity invariant、
focused verification、Diff Review 与 Human Ownership Check，并已提交为 `1e32ff7`。Ownership 已具备真实
Request → route → Adapter → result 调用链的 L2 traceable evidence。`M0-S6E` 已按批准 Scope 实现 route
timeout、timeout-aware Adapter、dedicated ExecutorService、route-aware TIMEOUT 与 safe typed Provider exception
translation，并已完成 focused tests、S6A-S6E regression、server compile、
Diff Review 与 Human Ownership Check。用户能够解释 Executor worker / caller wait、best-effort cancellation、
route identity attribution 与 programming bug / Provider failure 边界；因尚无 concrete Provider evidence，Ownership
保持 L2，并已提交为 `c374449`。`M0-S6F` integrated closeout 确认 Model Gateway 40 tests 与
server 180 tests 通过，Architecture boundary 无漂移，Documentation 已同步；Ownership 仍为 L2，
因此 closeout 为 `PARTIAL`。

### M0-S7 Approved Design and Current Slice

`M0-S7` 是 A 类 Security / Credential execution boundary。`M0-S7A` 已完成 Module-local Credential
propagation，`M0-S7B` 已完成 DeepSeek-first OpenAI-compatible Provider boundary；当前 `M0-S7C` 已实现
Spring runtime composition 并停在 `REVIEW_PENDING`。Browser / HTTPS ingress 与 Application Workflow 不进入本 slice。

```text
Task / Slice: M0-S7A — Explicit transient Credential propagation
Goal:
- 让 Provider-scoped transient Credential 与 provider-neutral TextGenerationRequest 分离，并在
  TextGenerationPort → fixed route → model-call ExecutorService → TextGenerationProviderAdapter 间显式传播。
Expected Behavior:
- TransientProviderCredential 保存 ProviderId 与 opaque secret，拒绝 null / blank，toString 始终 redacted；
- selected route 与 credential.providerId 不匹配时返回 route-aware CREDENTIAL_UNAVAILABLE，且不提交 Adapter task；
- matching Credential 由 submitted task 显式捕获并传给 Adapter，不依赖 ThreadLocal；
- Provider 实际拒绝 Credential 继续使用 AUTHENTICATION_FAILED；既有 timeout / typed failure / route identity
  validation 行为保持不变；
- timeout cancellation 不承诺 worker 立即停止或 Credential 立即从 JVM heap 消失。
Expected Production Files:
- server/src/main/java/com/dailylanguage/modelgateway/credential/TransientProviderCredential.java
- server/src/main/java/com/dailylanguage/modelgateway/result/ModelFailureKind.java
- server/src/main/java/com/dailylanguage/modelgateway/text/TextGenerationPort.java
- server/src/main/java/com/dailylanguage/modelgateway/text/execution/RoutedTextGenerationPort.java
- server/src/main/java/com/dailylanguage/modelgateway/text/execution/TextGenerationProviderAdapter.java
Architecture / Data / API / Security Impact:
- 修改 Java Typed Port 与 Adapter SPI，并扩展 failure taxonomy；无 database schema、external HTTP API、
  production dependency 或 Credential persistence 变化。
Explicitly Out of Scope:
- Browser / HTTPS ingress、header contract、UI / local storage、concrete Provider HTTP / SDK；
- Credential rotation、Secret Manager、heap zeroization guarantee、Spring Executor wiring；
- retry / fallback、Structured Output、Trace、ModelCallJob、Application Workflow 或 Learning State mutation。
Verification:
- focused Credential / route / Executor propagation tests；Model Gateway regression；server regression；
  Behavior Flow validation；git diff check。
```

当前实现是 `Module-local complete`：真实 flow 在 operation-specific Adapter boundary 结束。Behavior Flow 见
[`text-generation-credential-propagation.md`](../flow/text-generation-credential-propagation.md)。不得把它解释为
Browser Credential → HTTPS → External Provider 的 End-to-End complete behavior。

M0-S7A 已完成 Diff Review 与 module-local Human Ownership Check。用户能够说明 caller thread 提交 task、
Executor worker 执行 lambda、lambda 将 Credential 传给 Adapter，以及 timeout 后 `cancel(true)` 不能保证
worker 停止或 Credential 立即从 JVM heap 消失。该 slice 已提交为 `d8d47ac`；由于仍无 concrete Provider
execution evidence，Model Gateway Ownership 保持 L2。后续由已批准的 M0-S7B 继续完成 concrete Provider protocol boundary。

#### Approved M0-S7B Current Slice Contract

```text
Task / Slice: M0-S7B — DeepSeek-first OpenAI-compatible Text Adapter
Goal:
- 使用 S7A 的 TransientProviderCredential 完成 OpenAI-compatible non-streaming Chat Completions HTTP boundary；
  第一个配置目标是 DeepSeek，但按 protocol family 复用 Adapter。
Expected Behavior:
- ProviderId 与 trusted HTTPS /chat/completions endpoint 由 typed config 绑定，ModelId 继续来自 route；
- INSTRUCTION / USER / MODEL 映射为 system / user / assistant，Credential 只进入 Bearer header；
- 2xx response 映射 portable text / finish reason / optional usage，并保持 selected route identity；
- 401/403、408、429、5xx、transport failure 与 malformed payload 转换为安全 typed failure；
- HttpClient 禁止 redirect，failure 不解析或暴露 Provider raw error body。
Architecture / Security Impact:
- 新增第一个 concrete external Provider protocol Adapter；无新 dependency、schema、persistence 或 public HTTP API。
Explicitly Out of Scope:
- Spring bean / fixed route / Executor production wiring、Browser / HTTPS ingress、BYOK UI、live DeepSeek call；
- 第二个 Provider、native Gemini / Anthropic protocol、streaming、Tool Calling、Structured Output；
- retry / fallback、Trace、ModelCallJob、Application Workflow 或 Learning State mutation。
Verification:
- focused 18/18；Model Gateway 61/61；server 201 total / 0 failures / 0 errors / 33 environment-skipped；
  Behavior Flow validation；git diff check。
```

当前完成度是 `Provider-boundary implemented / runtime wiring pending`，不是 Browser Credential → HTTPS →
Application Workflow → DeepSeek 的 End-to-End complete behavior。

M0-S7B 已完成真实 Diff Review 与 Provider-boundary Human Ownership Check。用户能够追踪
`validateCall → writeRequest → buildProviderRequest → send → status classification / readResponse → result`，
能够说明 429 `RATE_LIMITED` + `Retry-After` 只形成等待提示而不授权自动 retry，并理解
`Redirect.NEVER` 在 Adapter construction 阶段阻止 Bearer Credential 随 Provider 返回的 `Location`
被转发到 configured endpoint 之外。当前没有 Spring runtime wiring、Browser / HTTPS ingress 或 live
DeepSeek network evidence，因此 Model Gateway 整体 Ownership 保持 L2。
M0-S7B 已提交为 `7f5f59f`。

#### Approved M0-S7C Current Slice Contract

```text
Task / Slice: M0-S7C — OpenAI-compatible Text Runtime Composition
Goal:
- 将 S7B concrete Adapter、fixed routes、no-redirect HttpClient 与 dedicated model-call ExecutorService 组成可注入的
  Spring TextGenerationPort；默认 Provider 为 DeepSeek。
Expected Behavior:
- typed deployment properties 保存 ProviderId、trusted endpoint、purpose routes 与 executor capacity，不保存 Credential；
- 默认只配置 CONVERSATION → deepseek / deepseek-chat / 30s，未配置 purpose 返回 CAPABILITY_UNAVAILABLE；
- OpenAI 与 DeepSeek 复用同一个 OpenAiCompatibleTextGenerationAdapter，只通过配置切换；
- model-call executor 使用独立 bounded fixed platform-thread pool（4 workers / 16 queue）与 AbortPolicy；
- HttpClient 固定 Redirect.NEVER；invalid endpoint / timeout / capacity fail startup；startup 不发起 Provider call。
- application.yml 显式导入 model-gateway.yml；配置拆分不改变 property key、默认值、environment override 或
  runtime behavior。
Architecture / Security Impact:
- 新增 Spring production composition 与独立 concurrency resource；无 dependency、schema、public HTTP API 或
  Credential persistence 变化；不得与 M0-S9 Job TaskExecutor 共用线程池。
Explicitly Out of Scope:
- Browser / HTTPS Credential ingress、Controller / Application Workflow、live DeepSeek verification；
- 第二个 Provider 配置或 Provider-specific Adapter、retry / fallback、Structured Output、Trace 与 ModelCallJob。
Verification:
- focused configuration 6/6；Model Gateway 67/67；server 207 total / 0 failures / 0 errors /
  33 environment-skipped；Behavior Flow validation；git diff check。
```

当前完成度是 `Module runtime composition complete / End-to-End incomplete`。配置测试证明 Spring bean graph、
DeepSeek 默认 route、OpenAI config-only replacement、no-redirect HttpClient、bounded executor、未配置 purpose 与
invalid configuration boundary；没有 Browser Credential 或 live Provider network evidence。

M0-S7C / S7C-R1 已完成 amended Diff Review 与 runtime-composition Ownership Check。用户能够说明
`application.yml → model-gateway.yml → @ConfigurationProperties → Spring bean graph`，能够区分 4 个 running
worker、16 个 queued task 与第 21 个 task 的本地 rejection，并确认底层 `RejectedExecutionException` 会在
`RoutedTextGenerationPort` 包装为 safe `IllegalStateException`，而不是 Provider HTTP 429 的 `RATE_LIMITED`。
当前仍无 Browser / HTTPS ingress 或 live Provider evidence，因此 Model Gateway 整体 Ownership 保持 L2。
M0-S7C / S7C-R1 implementation 已由用户提交为 `59c3e24`；本段 Review / Ownership reconciliation
保留为独立未提交文档修改，Commit Decision 继续由用户负责。
