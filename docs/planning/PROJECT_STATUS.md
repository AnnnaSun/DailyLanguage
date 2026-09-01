# AI Language Tutor — Project Status

> Last updated: 2026-09-01
> Current Phase: M0 — Engineering Foundation & Language Workspace
> Current Gate: M0-S9 / DESIGN_SCOPE_PENDING
> Production baseline: M0-S8D COMPLETE (`c9314dd`)

## Approved Decisions

- 四个 pending modules 的 V1 裁剪已确认；
- M0–M6 的 Phase 顺序已确认；
- Architecture Baseline 已确认；
- V1 Scope v1.5 已纳入 Engineering Evidence Track 与 Model Call Job，正式范围记录在
  `docs/product/V1_SCOPE.md`；
- Phase Gate 与 M0 slices 记录在 `docs/planning/V1_PHASE_PLAN.md`。
- Engineering Evidence Track 已批准：M1 Grounded Evaluator、M2 versioned Memory / replay、M3 RAG +
  Tool Gateway + Controlled Multi-role Agent Workflow、M6 Eval / capacity / CI / interview evidence；详细验收
  记录在 `docs/planning/ENGINEERING_EVIDENCE_PLAN.md`。
- M0-S1 使用 Java 25、Spring Boot 4.1、Maven、Node.js 24、Vue 3、TypeScript 与 Vite；backend/frontend 保持独立 build。
- M0-S2 使用 Docker Compose 运行 PostgreSQL 18 + pgvector 0.8.6 与 Redis 7.2；backend 通过 externalized configuration 连接，并只暴露 Actuator health endpoint。
- M0-S3 使用 PostgreSQL 18 native UUIDv7 identity、Flyway 12 与 MyBatis-Plus 3.5.17 / MyBatis Mapper XML；`languageProfileId` 是单列主键，`(user_id, language_code)` 保证单用户单语言 workspace 唯一。
- User → LanguageProfile foreign key 使用 `ON DELETE RESTRICT`；M0-S3 不实现 physical / logical deletion，lifecycle decision 已记录到 Backlog。
- Language Profile 读取通过 `languageProfileId + userId` scoped query 建立 persistence ownership boundary；可信 `UserContext` 留到 M0-S4。
- SQL variables 只通过 `#{}` parameter binding；禁止 `${}`、Java SQL annotation / string、客户端 SQL fragment 与 `last` / `apply` / `SqlRunner` 等拼接入口。
- M0-S4 采用 `ADR-0002`：Hosted V1 使用 Spring Security、Argon2id local credential、Redis-backed server-side Session、CSRF 与 authenticated `UserContext`；不使用 browser-stored JWT。
- `app_user` 保持稳定 internal identity，login channel 通过 `auth_identity` 分离；multi-channel authentication 与 Account Linking 记录为 `IDEA-006`，不进入当前 M0-S4 implementation。
- Argon2id security parameters 以 `argon2id-v1` 在 code 中版本化；password-hash concurrency 是 hardware-dependent capacity parameter，DEV / TEST 与 Self-hosted safe default 为 1，Hosted 必须显式配置。
- M0-S4C2 已完成 Rate Limit-before-Argon2、global password-hash concurrency gate、fail-fast saturation 与 provisional restricted-Container verification；Hosted capacity 仍留到 M6 在目标硬件确认。
- M0-S4B2b password policy 使用 12–64 printable ASCII characters（`U+0020`–`U+007E`，包括普通半角空格）；不做 composition rule、trim、字符替换或 Unicode normalization。该决定是 V1 usability / compatibility trade-off，低于当前 NIST password-only 15-character minimum 的 residual risk 必须明确保留。
- M0-S4B2b offline blocklist 使用 pinned SecLists 2026.1 Top 250,000 prefix，经 12–64 printable ASCII exact filter 后生成 2,065 个 sorted binary SHA-256 fingerprints（baseline 66,080 bytes）；只在 registration / password change / reset 的 Argon2id 前检查，不进入 login path。
- M0-S4B2c registration failure 只在 `LocalRegistrationService` 进行 structured safe logging；expected rejection 不记 ERROR，unexpected failure 只记录 stage、exception type 与已有 correlation ID，禁止 email、raw password、fingerprint、verifier、SQL parameter、database exception message 或完整 cause chain。
- M0-S4B2c atomic registration Design 已确认：normalize / policy / Argon2id 在 transaction 外执行，独立 `LocalRegistrationPersistence` bean 的外层 transaction 原子写入 `app_user`、`auth_identity` 与 credential；database unique constraint 裁决 concurrent duplicate，失败请求整体 rollback。
- M0-S4C1 Redis Session dependency direction 已确认：使用 Spring Boot 管理的 `spring-boot-starter-session-data-redis` 与 auto-configuration，不手写 Session lifecycle，也不无理由用 `@EnableRedisHttpSession` 绕过 Boot configuration。
- M0-S4C1 Login lifecycle 已确认：form-urlencoded `POST /api/auth/login` 经 Spring Security username/password filter 与 local `AuthenticationProvider`；unknown account 使用 dummy Argon2id，credential failure 返回统一 401，infrastructure failure 返回通用 503，成功清除 credentials、rotation Session ID 并返回 204。
- M0-S4C1 Session policy 已确认：Jackson JSON + Spring Security modules + strict `UserContext` allowlist；namespace `daily-language:session:v1`；24-hour idle TTL、无 remember-me、允许多设备、logout 仅当前 Session、默认 non-indexed repository，Redis unavailable 不回退到 in-memory Session。
- M0-S4C1 API contract 已确认：CSRF-protected `POST /api/auth/login`、CSRF-protected `POST /api/auth/logout`、authenticated `GET /api/auth/me`；成功分别返回 204 / 204 / `200 {"userId":"<uuid>"}`，credential / infrastructure / unauthenticated failure 使用固定 401 / 503 / 401 code，CSRF rejection 为 403。
- M0-S4C1 Cookie contract 已确认：opaque `SESSION` cookie 使用 HttpOnly、SameSite=Lax、Path `/`、无 Domain、无 persistent Max-Age；Hosted Secure=true、local HTTP development 显式 Secure=false，Redis 24-hour idle TTL 是 validity authority。
- M0-S4C1 feature plan 已确认：C1a Redis Session foundation → C1b local `AuthenticationProvider` → C1c login/logout/me HTTP lifecycle；一次只实现一个 slice。
- M0-S4C2 已完成 SPA CSRF delivery、Login / Registration Redis Rate Limit、共享 Argon2 concurrency hard limit，以及 restricted local Container saturation / mixed workload / recovery `PROVISIONAL` verification。
- M0-S4D 使用单一 `REGISTRATION_ENABLED` capability switch：默认 `false` 时持久化并复用 singleton User、隐藏 login 并关闭 public registration；显式 `true` 时开放 registration 并使用正常 Session login。两种路径产生相同可信 `UserContext`。
- 最小 Account Profile 与 `display_name` 已记录为 `IDEA-007`，不修改当前 S4C1 schema、`UserContext` 或 `/api/auth/me` contract。
- M0-S6 Model Gateway Detailed Design 已批准：logical module 下按 Typed Operation Port 拆分，fixed route
  使用 `ModelPurpose + ModelOperation`；Gateway 只执行单 Operation 并归一化 timeout / failure，具体
  Application Workflow 负责 multi-operation ordering、REQUIRED / OPTIONAL、partial success 与 degradation。
- M0-S6 默认不自动 retry 或静默 cross-provider fallback；后者只有在用户配置、transient Credential、
  capability compatibility 与明确 policy 共同授权后才能进入后续 Scope。完整 Contract 见
  `docs/features/MODEL_GATEWAY.md`。
- M0-S6C 使用 `INSTRUCTION` / `USER` / `MODEL` 内部 message role、sealed Text output specification、
  portable response / finish reason 与 optional token usage；不引入 Provider-specific option Map。
- M0-S6D runtime route 通过 Composition 绑定 ProviderId、ModelId 与 operation-specific Adapter；
  V1 fixed mapping 不引入 Adapter Registry、dynamic router、retry 或 fallback。
- M0-S6E 已完成并提交（`c374449`）：route 持有 positive final `executionTimeout`，Gateway 与 Adapter 使用同一个
  Duration；dedicated injected `ExecutorService` 负责最终 deadline，typed `ModelProviderCallException` 只暴露
  safe failure kind / retryAfter。
- S6E model-call ExecutorService 与 M0-S9 Job TaskExecutor 是两个 execution boundary，不得共用同一个 bounded
  fixed pool；前者负责单次外部调用 deadline，后者负责 Job lifecycle、interactive waiting 与迟到结果持久化。
- V1 Model Call Job Design 已批准：production Application Workflow 在调用 Provider 前创建 PostgreSQL-backed
  Job；interactive wait budget 耗尽后返回 pending，后台调用继续到最终 execution deadline；迟到结果按
  workflow version 自动消费、等待用户确认或标记 stale。
- Model Call Job 使用稳定 UUIDv7 `jobId`、独立 `workflowVersion` 与 optimistic-lock `rowVersion`；V1
  runtime 使用 `Spring TaskExecutor + DB Job State`，不引入 Kafka / RabbitMQ，不持久化 BYOK Credential。
- Model Call Job backend foundation 排入 M0-S9，依赖 S7 transient Credential 与 S8 Structured Output /
  Trace；详细 Contract 见 `docs/features/MODEL_CALL_JOB.md`，当前 M0-S6E 不实现 Job。
- M0-S6F non-blocking L2 Ownership gap 已由用户接受；该决定不把 Ownership 提升为 L3，也不改变
  S6F `PARTIAL` 的历史结论。
- M0-S7A Design / Scope 已批准：`TransientProviderCredential` 与 provider-neutral request 分离，
  selected route 在提交 worker 前校验 Provider identity；Credential 通过 Executor task 显式传给 Adapter，
  不使用 ThreadLocal、global mutable context 或 persistence。
- selected route 缺少 matching Credential 时返回 route-aware `CREDENTIAL_UNAVAILABLE`；Provider 实际拒绝
  Credential 仍使用 `AUTHENTICATION_FAILED`。当前只完成 Module-local flow，不宣称 BYOK End-to-End。
- M0-S7B Design / Scope 已批准：第一个 Provider 是 DeepSeek，但 Adapter 按 OpenAI-compatible protocol family
  命名和复用；ProviderId、HTTPS Chat Completions endpoint 与 ModelId 分别由 typed config / route 承载，
  不为每个兼容厂商复制 Adapter，也不提前引入 Registry、Factory 或 Base Class。
- M0-S7C Design / Scope 已批准：通过 typed properties 与 Spring composition 暴露真实 `TextGenerationPort`；
  DeepSeek 是默认 OpenAI-compatible 配置，切换 OpenAI 只改变 ProviderId、endpoint 与 ModelId，不新增 Adapter。
- model-call Executor 使用独立 bounded platform-thread pool（默认 4 workers / 16 queue）与 `AbortPolicy`；
  不与 M0-S9 Job TaskExecutor 共用，Hosted capacity 仍留到 M6 在目标硬件确认。
- M0-S7C-R1 configuration resource split 已批准：`application.yml` 显式导入 `model-gateway.yml`；只调整
  Model Gateway deployment properties 的文件组织，不改变 key、默认值、environment override 或 runtime behavior。
- M0-S7D Design / Scope 已批准：提供 authenticated fixed Provider preset 查询与 CSRF-protected connection
  verification；Credential 只通过 `X-Model-Provider-Credential` 进入当前内存调用链，path `providerId` 不能覆盖
  route、Model、endpoint 或 Adapter。当前不引入 dynamic Provider / Model selection、Registry 或 UI。
- M0-S8A–S8B 已完成 provider-neutral JsonObject request transport 与 module-local strict Structured Output
  validation；validation 通过 Java record binding、enum 与 deterministic semantic rule 形成 safe typed result，
  但尚未接入 Planner / Evaluator / Content Workflow。
- M0-S8C–S8D 已完成 Text Generation module-local minimal Trace 与 unknown finish-reason diagnostics：同一 UUID
  显式跨越 caller / Executor worker / Adapter，terminal Trace 只记录安全 metadata；未知 raw reason 保持
  portable `UNKNOWN`，并经过 allowlist、redaction 与 per-route rate limit 后输出 WARN。
- M0-S8 不持久化 Trace，不记录 Credential、Prompt、generated text 或 Provider raw response，不引入 retry、
  fallback、Application Workflow 或 `ModelCallJob`。

## Completed Review

1. M0-S1 Spring Boot 与 Vue application skeleton；
2. M0-S1 backend/frontend 独立 build 与 startup；
3. `V1_SCOPE.md` v1.3 的 Product Audience、Success Model 与 Project Goal Priority；
4. M0-S2 Compose service、PostgreSQL 18 volume layout、loopback exposure 与 host/container port boundary；
5. M0-S2 pgvector availability 与 Flyway-owned installation boundary。
6. M0-S3 UUIDv7 identity schema、Language Profile ownership boundary 与 deletion constraints；
7. M0-S3 MyBatis Mapper XML persistence path、UUID TypeHandler 与 SQL injection safety boundary。
8. M0-S4A Spring Security boundary、trusted `UserContext` 调用链与 ownership-scoped access；
9. M0-S4A unauthenticated、owner、cross-user 与 request `userId` spoofing verification。
10. M0-S4B1 authentication identity / credential persistence、transaction / foreign key boundary、Review 与 Human Ownership Check。
11. M0-S4B2a versioned Argon2id hashing、verifier resource-parameter gate、Review 与 Human Ownership Check。
12. M0-S4B2b printable-ASCII password policy、pinned offline blocklist、deterministic asset generation、Review 与 Human Ownership Check。
13. M0-S4B2c atomic registration Scope、correctness、transaction、concurrent duplicate、safe logging、failure contract Diff Review 与 Human Ownership Check。
14. M0-S4C1a Boot-managed Redis Session、JSON / Security serialization allowlist、namespace、idle TTL、Cookie configuration、Redis restore / fail-closed Diff Review 与 Human Ownership Check。
15. M0-S4C1b local `AuthenticationProvider`、unknown-account Argon2id、uniform credential rejection、infrastructure failure、safe logging、credential clearing Diff Review 与 Human Ownership Check。
16. M0-S4C1c framework-managed login/logout/me HTTP lifecycle、Session rotation / restore / invalidation、Cookie lifecycle、fixed authentication error response、Redis unavailable fail-closed Diff Review 与 Human Ownership Check。
17. M0-S4C2 SPA CSRF delivery、Rate Limit-before-Argon2、global hash concurrency hard limit、fail-fast / permit recovery Diff Review 与 Human Ownership Check。
18. M0-S4C2 restricted-Container saturation、mixed login workload、authenticated Session continuity 与 recovery `PROVISIONAL` verification。
19. M0-S4D public registration capability、persistent singleton bootstrap、login bypass / hiding、同一 `UserContext` contract 与 isolated PostgreSQL concurrency verification。
20. M0-S4 phase closeout：implementation、verification、Architecture 与 Ownership PASS；Hosted capacity confirmation 明确延期到 M6。
21. M0-S5 Language Profile create / list / switch minimum use case、ownership boundary 与 multi-language isolation。
22. M0-S6A portable route vocabulary Scope、implementation、focused verification 与 targeted Diff Review。
23. M0-S6B typed result / failure Scope、implementation、`routing` / `result` contract package separation、
    S6A + S6B focused regression、server compile 与 targeted Diff Review。
24. M0-S6C provider-neutral Text Generation request / response / port、focused contract regression、
    server compile、Diff Review 与 scope-limited Ownership Check。
25. M0-S6D fixed Text Generation route、operation-specific Adapter seam、single delegation、route identity
    invariant、focused regression、Diff Review 与 Human Ownership Check。
26. M0-S6E positive route timeout、dedicated model-call ExecutorService、route-aware TIMEOUT、safe typed Provider
    failure translation、focused regression、Diff Review 与 Human Ownership Check。
27. M0-S6F integrated closeout：Scope / Architecture / Model Gateway 40 tests / server 180 tests PASS；
    Documentation 已同步；Model Gateway Ownership 保持 L2，closeout 结果为 PARTIAL。
28. M0-S7A explicit transient Credential propagation：Scope / Architecture / Security boundary / verification PASS；
    Diff Review 无 blocking finding，module-local Ownership Check 为 UNDERSTOOD；Model Gateway 整体保持 L2。
29. M0-S7B DeepSeek-first OpenAI-compatible Text Adapter：Scope / Architecture / Security boundary / verification
    PASS；Diff Review 无 blocking finding，Provider-boundary Ownership Check 为 UNDERSTOOD；Model Gateway
    整体保持 L2。
30. M0-S7C / S7C-R1 OpenAI-compatible Text runtime composition：Scope / Architecture / verification PASS；
    amended Diff Review 无 blocking finding，runtime-composition Ownership Check 为 UNDERSTOOD；Model Gateway
    整体保持 L2。
31. M0-S7D DeepSeek-first BYOK Connection Verification：Scope / Architecture / Security boundary / verification PASS；
    Diff Review 无 blocking finding，Backend API Ownership Check 为 UNDERSTOOD；Model Gateway 与 BYOK /
    Provider Configuration 均保持 L2；已由用户提交为 `4deed20`。
32. M0-S8A JsonObject transport contract：Scope / Architecture / verification / Review / focused Ownership 完成；
    已提交为 `8d11ddd`。
33. M0-S8B strict Structured Output validation boundary：parse / shape / enum / semantic validation 与 safe typed
    result 完成；已提交为 `16635d0`。
34. M0-S8C safe terminal Model-call Trace：metadata contract、INFO recorder、fail-open 与 Flow sync 完成；
    已提交为 `3f8838d`。
35. M0-S8D same-Trace-ID Provider diagnostics：safe allowlist / redaction、concurrent per-route rate limit、Review、
    Ownership 与 default-model verification reconciliation 完成；已提交为 `c9314dd`。

## Current Gate

```text
Selected phase: M0-S9 — Model Call Job foundation
Gate: DESIGN_SCOPE_PENDING
M0-S8A: COMPLETE (`8d11ddd`)
M0-S8B: COMPLETE (`16635d0`)
M0-S8C: COMPLETE (`3f8838d`)
M0-S8D: COMPLETE (`c9314dd`)
Verification: PASS for Model Gateway scope (95/95; default runtime composition 6/6; server compile)
Wider server regression: NOT_RUN for S8; latest S7D evidence is 217 total, 0 failures/errors, 33 environment-skipped
Behavior Flow: CURRENT
Code Review: PASS (S8A-S8D no blocking findings)
M0-S8 integrated closeout: PARTIAL / ACCEPTED for progression to S9 Design / Scope
Ownership: Structured Output L2 module-local; Trace / Observability L3 module-local; Model Gateway remains L2
Completion level: S8 foundations complete; no Application Workflow integration or Trace persistence
Production baseline: M0-S8D COMPLETE (`c9314dd`)
Current target: define and approve only the first M0-S9 implementation slice
Dependency: approved `docs/features/MODEL_CALL_JOB.md` Detailed Design
```

## Next Action

基于已批准 `MODEL_CALL_JOB.md` 提出 M0-S9 第一个 implementation slice 的 Design / Scope，明确 schema、
execution / consumption state boundary、TaskExecutor 与 Gateway Executor 分离、transient Credential lifecycle、
验证范围和 stop point。等待批准前不修改 schema、Production code 或 API。

## Blockers

None for M0-S9 Design / Scope. M0-S9 implementation scope、schema、API 与 file boundary 尚未批准。
Model Gateway 与 BYOK / Provider Configuration Ownership 仍为 L2；Structured Output 只有 module-local validation，
Trace 只有安全 logging metadata。当前仍没有 Hosted TLS verification、Browser local/session storage UI、业务 Agent
Workflow、live DeepSeek Credential / network verification 或 durable Trace，因此不能宣称完整产品 BYOK / Structured
Output / Trace End-to-End complete。
Hosted model-call 与 password-hash capacity 仍为
`PROVISIONAL`，按既定 Scope 在 M6 目标硬件验证。
