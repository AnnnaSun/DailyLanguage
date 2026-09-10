# M1-S9 Optional Planner Enrichment

> Status: APPROVED DESIGN — S9A_IMPLEMENTATION
> Updated: 2026-09-10
> Architecture-sensitive Feature: YES
> Design / Slice Plan: APPROVED — 2026-09-10
> Current Slice Contract: APPROVED — S9A Deterministic Candidate Set — 2026-09-10
> Implementation: NOT_STARTED

本文定义 M1-S9 已批准的整体 Design / Scope 与 slice breakdown。2026-09-10，用户批准 recommended
`PlanningRun + candidate snapshot + recommendationReason` 方向、当前受控 two-candidate soft decision 价值及
S9A–S9F 拆分顺序。用户随后批准 S9A Current Slice Contract 交由 Zcode 实现；该授权只覆盖本文第 11 节，后续
slice 仍需一次只批准并实现一个 Current Slice Contract。

## 1. Current situation

当前 `LearningTaskPlanningService` 在完成 owner-scoped `LanguageProfile` 读取后，调用
`DeterministicLearningTaskPlanner` 从 `LearningMaterialCatalog` 生成合法 task，再通过
`LearningTaskRepository.createOwned` 原子重校验 owner / profile / target language 并创建 durable `PLANNED`
task。该路径已经能够在无 Provider 时稳定工作。

M0-S9 已提供 PostgreSQL-backed `ModelCallJob`、独立 Job `TaskExecutor`、provider-neutral
`TextGenerationPort`、transient Credential、typed result / failure 与 consume / stale CAS。M1-S8 已证明
Application Workflow 可以原子绑定自己的 Run 与 Job，并在 transaction 外 dispatch。当前 Planner 尚未接入
该能力，`PLANNING` fixed route 也尚未配置。

当前真实 Content 有两个符合 `en + zh-CN + FOUNDATION` 的 `PLANNABLE` material：
`en-builtin-greeting-intro/v1` 与 `en-builtin-cafe-request/v2`。因此 S9 可以真实验证 bounded two-candidate
selection 与用户可见推荐理由；但 M1 仍没有 Goal、Weakness、Recent Practice 或 Review context，Model 只能依据
request constraints 与 published candidate metadata 做 soft decision。S9 是受控 hybrid Planner 的 engineering
walking skeleton，不声称已经实现 learner-state personalization。

## 2. Problem

M1 已批准 hybrid Planner 方向：Java 生成并过滤合法 candidate，Model 只做 soft selection / reason
enrichment，最后仍由 Java 验证并持久化。缺少 S9 时：

- Planner 只有 deterministic fallback，尚未证明 `PLANNING` Model workflow；
- 技术枚举 `planningReason` 不能替代用户可读的“为什么推荐这个任务”；
- 直接同步调用 Model Gateway 会绕开已批准的 durable Job、双 timeout 与 late-result 语义；
- Model 若能直接改写 task 字段，会破坏 Content provenance、exact-version replay 与 Java authority。

## 3. Proposed feature contract

### Goal

在保留现有 deterministic planning 可用性的前提下，可选地让 Model 从 Java 已确认合法的 candidate snapshot
中选择一个 material，并生成短的用户可读推荐理由；无论 Model failure、capacity、timeout、invalid output 或
wait budget 耗尽，正常 Content 路径都回到同一 deterministic fallback 并返回 durable task。

### Trigger

authenticated、CSRF-protected `POST /api/language-profiles/{languageProfileId}/learning-tasks` 继续是唯一入口。
request 可选提供 `providerId`，Credential 只通过 `X-Model-Provider-Credential` header 瞬时进入 Backend。

- `providerId` 与 Credential 都缺失：不创建 Model Job，直接走现有 deterministic path；
- 两者同时合法：请求 optional enrichment；
- 只提供其中一个、blank 或非法 `providerId`：`400`，不静默吞掉调用方错误；
- 请求的 provider 与 fixed `PLANNING` route 不匹配：`422`，不调用 Provider、不创建 task；
- route / Prompt 内部不可用：optional branch 不可用，创建 deterministic fallback task。

### Happy path

```text
Authenticated UserContext + owned LanguageProfile
→ Java resolved candidate snapshot + deterministic fallback order
→ optional PLANNING request with transient Credential
→ atomic PlanningRun + ModelCallJob + candidate identities
→ transaction-free Job dispatch
→ bounded read-only wait for durable Job status
→ strict JSON validation + exact candidate membership validation
→ atomic Job consume + one LearningTask create + PlanningRun finalize
→ 201 Created with durable LearningTask
```

Model output 只包含：

```json
{
  "materialId": "en-builtin-cafe-request",
  "publishedVersion": "v2",
  "recommendationReason": "这个练习帮助你在咖啡店先理解示范，再尝试独立点单。"
}
```

`recommendationReason` 是 plain text、使用 request 的 `supportLanguage`。M1 proposal 将 shortlist 限制为按
deterministic order 排列的前 8 个 candidate，将 raw JSON 限制为 8 KiB；reason 经过 NFC + `strip` 后必须为
1–240 Unicode code points，不能包含换行或 control character。Java 对 exact schema、字段、candidate membership
与上述边界全部验证后才可保存；API / UI 只能把它当 text 渲染，不能当 HTML / Markdown 执行。它不是
Evidence、能力判断或 Content 本体。

M1 proposed runtime values 为 `interactive-wait = 2s`、`poll-interval = 50ms`、planning result TTL `5m`，均放入
typed Planner configuration；Gateway final execution timeout 继续由 fixed `PLANNING` route 独立控制，默认不与
interactive wait 共用同一个值。具体默认值可在 S9D / S9E1 Current Slice Contract 审批时调整。

### Failure and fallback path

```text
Prompt/config unavailable
or capacity unavailable
or Job terminal failure / final timeout / outcome unknown
or interactive wait budget exhausted
or malformed / extra-field / oversized / unoffered candidate output
→ Java selects the same first deterministic candidate
→ atomically creates exactly one LearningTask
→ PlanningRun finalizes as FALLBACK_APPLIED
→ advanced workflowVersion makes any later Model success inapplicable
```

若 fallback transaction 观察到 Job 已成功完成，则在同一 transaction 将其标记 `STALE`；若 Job 仍在执行，
Run 的新 workflow version 已使迟到结果在语义上 stale。M1 不新增 scheduler 或 generic consumer registry，
因此进程 crash / 后台完成后无人再次读取的 Job 可能保留 `NOT_READY`，留到 M1-S12 recovery/cleanup audit
重新评估；它不能再修改已创建 task。

若 Job 已成功但 output validation 失败，Workflow 已经读取并裁决该结果，因此 Job 原子进入 `CONSUMED`，
PlanningRun 记录 bounded safe fallback reason 并创建 deterministic task；rejected generated text 不复制到
PlanningRun、LearningTask、API response 或 Log。现有 Job typed text result 可以按既有 retention policy 保留，
但 Provider envelope / SDK exception 仍不持久化。

### State and side effects

- 新增 durable `PlanningRun`，只拥有一次 optional model decision，不取代 `LearningTask` lifecycle；
- 新增 normalized candidate identity snapshot，保存本次真正允许 Model 选择的 exact
  `materialId + publishedVersion` 与 fallback order，不保存完整 Content、Prompt 或 learner history；
- `LearningTask` 新增 nullable、长度受限的 user-facing `recommendationReason`，并扩展 closed
  `planningReason` 以区分 `MODEL_ENRICHED` 与既有 `DETERMINISTIC_BUILT_IN_FALLBACK`；
- Model 成功结果与 fallback 都必须在 PlanningRun row lock 下最多创建一个 task；
- Credential、Prompt、raw Provider response、exception detail 不进入 PostgreSQL、Redis 或 Trace/Log；Credential
  只存在于 inbound header 和当前 Worker 内存，以上数据均不进入 API response。

## 4. Java / Model authority

Java 始终负责：

- owner / `languageProfileId` authorization；
- target / support language、difficulty、duration、exclusion 与 availability hard filtering；
- deterministic fallback order；
- candidate exact identity、published material re-resolution 与 final task validation；
- recommendation output schema / length validation；
- Job consumption、PlanningRun transition 与 task persistence。

Model 只负责：

- 在 Java 提供的 candidate identity set 中选择一个 candidate；
- 基于提供的 scenario / communication objective 生成一条 bounded recommendation reason。

Model 不得生成或修改 material、`scenario`、`primaryGoal`、difficulty、duration、task type、support language、
Learning Memory、Weakness、Level 或 Mastery。M1 不向 Planner Prompt 提供完整 Profile、Practice history、
Evaluator result 或长期 Memory，也不提供 userId / languageProfileId。Prompt 只包含 target/support language、
available time、difficulty 与最多 8 个 trusted published candidate metadata；M2 才设计 learner-state-aware
planning context。

## 5. Architecture decision material

### Proposed change

在既有 `planner` module 内增加 candidate snapshot、versioned request/validator、`PlanningRun` workflow 与
Job-owned consumption；复用 `modelcalljob` 和 `modelgateway`，不新增顶层 Agent module、generic workflow engine、
dynamic handler registry 或 production dependency。

### Alternatives

1. **直接同步调用 `TextGenerationPort`**：代码较少，但绕过已批准的 durable Job、interactive/final timeout
   分离与 late-result audit，和当前 Architecture Contract 冲突。
2. **只创建 `ModelCallJob`，不建 `PlanningRun`**：schema 较少，但 Job 没有 durable owning workflow，无法把
   candidate snapshot、exactly-one task outcome 与 workflow version 原子关联。
3. **让 Model 改写 `scenario` / `primaryGoal`**：表面上 enrichment 更明显，但使 task 文本脱离 immutable
   material provenance，历史 Session 解释不再只由 exact version 决定。
4. **S9 整体推迟到 M2/M3**：当前虽有两个真实 candidate，但缺少 learner-state context，个性化收益仍有限；
   延后可以等到决策信息更充分，但会把已批准的 M1 hybrid Planner / ModelCallJob integration walking skeleton 延后。

### Recommended decision

选择显式 `PlanningRun` + normalized candidate identity snapshot + separate `recommendationReason`。这是当前能同时
守住 Job ownership、exact candidate boundary、immutable Content provenance 与 deterministic fallback 的最小完整
方案。若用户认为当前 two-candidate 但无 learner-state context 的工程价值不足，也可以批准 Alternative 4，
而不是把 metadata-only selection 描述成已经实现个性化。

### PlanningRun lifecycle

```text
PENDING (workflowVersion = 0)
├─ MODEL_APPLIED    → exactly one task + Job CONSUMED
└─ FALLBACK_APPLIED → exactly one task + workflowVersion = 1
                       └─ already-successful Job STALE；later success remains inapplicable
```

`MODEL_APPLIED` / `FALLBACK_APPLIED` 是 PlanningRun outcome，不替代 LearningTask 的
`PLANNED → STARTED → COMPLETED` lifecycle。Candidate table 只保存 exact identity 与 fallback order；finalizer
必须重新按 exact identity resolve immutable material，不能把 snapshot 当成 Content authority。

### Trade-offs and impact

- 增加 Planner workflow schema、transaction / concurrency tests 与 bounded DB polling；
- planning POST 在请求 enrichment 时增加最多一个 interactive wait budget，但默认无 Credential 路径不增加等待；
- Model slow/failure 不降低 task availability，但会产生一个可审计的 terminal/stale Job；
- public request/response additive 演进，现有不带 provider/credential 的客户端保持兼容；
- 不改变 Persistent Learner Model、Evaluator、PracticeSession、Content publish authority 或多语言隔离。

## 6. Extensibility fit

```text
Change Axis Evidence: FACT — M1 已批准 optional Planner Model path；M3 将增加 Content candidate；既有 ModelCallJob
Variation Type: Behavior + external dependency + independent workflow lifecycle
Decision: typed candidate snapshot + Composition + provider-neutral Job/Gateway boundary + explicit PlanningRun
Current Problem Solved: Model 选择范围、fallback、迟到结果与 exactly-one task outcome 可验证
Complexity Introduced: two normalized tables、task provenance migration、workflow transaction、strict validator、bounded polling
Revisit Trigger: M2 learner-state context、M3 多 Content source、真实吞吐要求 scheduler/durable backlog
Extensibility Fit: RIGHT_SIZED for approved M1 path；若 S9 延后，则不提前创建任何 abstraction
```

## 7. Proposed implementation slices

每个 slice 都是 A 类，完成后进入 `REVIEW_PENDING` 并停止。预计某 slice 超过主要 Production files 5 个或
Production changed LOC 250 时，在批准 Current Slice Contract 前继续拆分。

| Slice | Goal | Observable behavior | Main impact | Verification | Explicit non-scope |
| --- | --- | --- | --- | --- | --- |
| S9A — Deterministic Candidate Set | 从一次 hard filtering / exact resolution 产生 ordered candidate snapshot 与 fallback | 现有无 Model planning 输出完全不变；多 candidate 顺序稳定，任一不一致 fail closed | planner domain/application only | candidate/filter/order/list-resolve unit + existing Planner regression | Model、schema、API、Credential |
| S9B — Enrichment Contract | 建立 versioned Prompt input/output 与 strict Java validator | offered candidate 可通过；extra field、unknown identity、blank/oversized reason 全部 rejected | planner request/validator + classpath prompt | request serialization、strict JSON、candidate membership unit | Model call、DB、API |
| S9C1 — LearningTask Enrichment Projection | 为 task 增加 nullable bounded recommendation reason 与 closed `MODEL_ENRICHED` reason | 既有 deterministic row/API 兼容；Model reason 可作为 durable user-facing projection | Flyway + LearningTask domain/repository/mapper | empty-schema/upgrade、legacy/new round-trip、DB constraints | PlanningRun、Model call、Controller wiring |
| S9C2 — Durable PlanningRun | 原子创建 PlanningRun、candidate identities 与唯一 PLANNING ModelCallJob | Run/Job/candidates all-or-nothing；owner/profile/workflow identity 由 DB gate 保护 | next Flyway + Planner Run domain/repository/mapper | empty-schema/upgrade、round-trip、rollback、constraint/concurrency integration | dispatch、wait、HTTP |
| S9D — Planning Job Dispatch | 使用 fixed `PLANNING` route 构造 request，提交 transient Credential；只 dispatch 新 Run | Job 在 commit 后被 Worker 认领；capacity rejection 可识别；Credential 不落 durable state | planner dispatch service、route/config | dispatch order、route identity、capacity、Gateway/Job regression | wait、result consume、API |
| S9E1 — Bounded Job Awaiter | 在 typed duration 内只轮询 owner-scoped durable Job status，不消费或取消 Worker | terminal state 尽快返回；wait budget 到期明确返回，不伪造 Gateway timeout | narrow modelcalljob await boundary + typed config | fake clock/delay、terminal/budget/query failure unit | Workflow decision、task mutation |
| S9E2 — Atomic Decision / Fallback | 在 Run lock 下 consume valid success 或应用 fallback，最多创建一个 task | valid output → `MODEL_ENRICHED`；failure/invalid/timeout → deterministic task；race/replay 不重复 task | planner consumption/finalization service | success/failure/invalid/expiry/race/replay integration | Controller / client UI、scheduler |
| S9F — Owner-scoped API Wiring | 将 optional provider/Credential 接到现有 planning POST，并完成 Behavior Flow | 无 Credential 兼容现有 201；合法 fast enrichment 201；Model failure/wait timeout 仍 201 fallback；malformed optional request 400，provider mismatch 422 | planning service/controller + docs/flow | HTTP security/secret absence、end-to-end fake worker、Planner/Practice/Evaluator affected regression | live Provider、frontend、M2 context、retry/recovery |

建议顺序：`S9A → S9B → S9C1 → S9C2 → S9D → S9E1 → S9E2 → S9F`。S9A/B 先证明 Java authority；
S9C1/C2 分开 task projection 与 workflow persistence，避免 migration/repository slice 过大；S9E1/E2 分开
只读等待与有副作用的原子裁决；S9F 最后改变 public behavior，避免半成品 API 暴露。

## 8. Verification and invariants

最小验证阶梯：

```text
Planner candidate / validator unit
→ LearningTask + PlanningRun PostgreSQL integration
→ ModelCallJob dispatch / consumption affected regression
→ owner-scoped HTTP security and fallback contract
→ Practice start + Grounded Evaluator exact-material regression
→ wider server regression and Behavior Flow sync
```

必须覆盖：

- wrong-owner / wrong-profile 在 Prompt/credential processing 前拒绝；
- candidate 不能跨 target language、support language、difficulty、duration 或 exclusion；
- output exact identity 必须属于本 Run snapshot；
- Model 不能修改 material-owned task fields；
- fast success 与 timeout/fallback race 最多创建一个 task；
- fallback 后的迟到 success 不能覆盖 task；
- capacity、Model failure、final timeout、OUTCOME_UNKNOWN、invalid JSON、unknown candidate 都保持 deterministic
  availability；
- Credential / Prompt / raw output / exception detail 不进入 API、DB、Trace 或 Log；
- historical material identity 仍可被 Practice / Evaluator exact resolve。

## 9. Explicit non-scope

- M2 Profile / Evidence / Weakness / Review context 与 learner-state personalization；
- Model 生成 Content、task steps、rubric、difficulty 或 duration；
- replace / skip / easier / harder / replan API；
- request idempotency、active-task uniqueness 或 automatic retry；
- background scheduler、durable backlog、Kafka / RabbitMQ、generic workflow engine；
- cross-provider fallback、provider health routing、Credential persistence；
- frontend Credential storage / UX、SSE / WebSocket、push notification；
- live Provider quality validation；
- 修改 Practice、Evaluator 或长期 learner-state authority。

## 10. Approved decision and current gate

2026-09-10 用户批准：

1. recommended `PlanningRun + candidate snapshot + recommendationReason` 整体方向；
2. 当前 two-candidate、尚无 learner-state context 的受控 soft decision，定位为 engineering walking skeleton，
   不宣称 learner-state personalization；
3. `S9A → S9B → S9C1 → S9C2 → S9D → S9E1 → S9E2 → S9F` 的 slice 顺序。

整体 Design / Scope approval 不等于所有 Production implementation approval。2026-09-10，用户批准 S9A Current
Slice Contract 交由 Zcode 实现；后续每个 slice 仍需单独批准，且 S9A 完成 Review / Ownership / Commit Decision
前不得自动开始 S9B。

## 11. Approved S9A Current Slice Contract — Deterministic Candidate Set

```text
Task / Slice: M1-S9A — Deterministic Candidate Set
Ownership Level: A — Critical
Status: APPROVED FOR IMPLEMENTATION
Executor: Zcode
Reviewer / External Verifier: Codex
Baseline: branch architecture/M1S9-OptionalPlannerEnrichment; HEAD c2dbb4f
Required End State: REVIEW_PENDING
Next Slice: S9B — NOT_AUTHORIZED
```

### Goal

把现有 `DeterministicLearningTaskPlanner` 内的 Java hard filtering、stable order、exact material resolution 与
task-plan projection 提取为单一 concrete component，产生 immutable、non-empty、最多 8 个元素的 ordered
candidate set；第一个 candidate 同时是唯一 deterministic fallback。现有 `LearningTaskPlanner.plan` public
contract 与健康 Catalog 下的 provider-free observable result 保持不变。

### Expected files

预计只修改以下 4 个 Production files：

1. 新增 `planner/domain/PlanningCandidateSet.java`：保存 defensive-copied ordered `LearningTaskPlan` list，校验
   non-empty / max 8，并显式返回 first fallback；
2. 新增 `planner/domain/PlanningCandidateSetResult.java`：以 sealed `Available / Unavailable` 表达 candidate set
   或复用现有 `PlanningResult.UnavailableReason` 的 fail-closed 原因；
3. 新增 `planner/application/EligibleLearningTaskCandidateReader.java`：读取 Catalog、执行所有 hard constraints、
   stable sort、最多取前 8 个、逐个 exact resolve 并投影为 task plan；保持 concrete，不增加 pass-through
   Interface、Factory 或 Registry；
4. 修改 `planner/application/DeterministicLearningTaskPlanner.java`：委托上述 component，并只返回 candidate set
   的 first fallback 或原有 typed unavailable result。

预计测试范围：

5. 新增 `planner/application/EligibleLearningTaskCandidateReaderTests.java`；
6. 修改 `planner/application/DeterministicLearningTaskPlannerTests.java`；
7. `LearningTaskPlanningServiceTests.java` 仅在 constructor wiring 导致必要 regression adjustment 时允许修改，
   不得借机改变 service behavior。

除以上文件外不得修改 Production、test fixture、Content 或 documentation。若实现时发现必须增加 Production
file、改变 public API、调整 unavailable reason、修改其他 module，或超出主要 Production files 5 个 /
Production changed LOC 250，则停止并报告 `SCOPE CHANGE REQUIRED`，不得自行扩大。

### Target call flow

```text
[EXISTING] LearningTaskPlanningService
    → [CHANGE] DeterministicLearningTaskPlanner.plan(request)
        → [NEW] EligibleLearningTaskCandidateReader.readCandidates(request)
            → [EXISTING] LearningMaterialCatalog.listAvailable(...)
            → [EXISTING] LearningMaterialCatalog.findByIdentity(...) for each shortlisted candidate
            → [NEW] PlanningCandidateSetResult
        → [NEW] PlanningCandidateSet.deterministicFallback()
        → [EXISTING] PlanningResult.Planned / PlanningResult.Unavailable
```

### Required behavior and invariants

- `availableMinutes < 5` 继续返回 `AVAILABLE_TIME_TOO_SHORT`，且不读取 Catalog；
- hard filter 继续覆盖 target language、support language、difficulty、excluded exact identity 与 malformed summary；
- eligible summary 不依赖 Catalog 返回顺序，按 `materialId + publishedVersion` stable sort，shortlist 最多 8 个；
- shortlist 中每个 candidate 都必须按 exact identity resolve，并重新验证 identity、target/support language、
  difficulty、scenario 与 communication objective；
- shortlist 为空继续返回 `NO_ELIGIBLE_MATERIAL`；任一 shortlisted summary / resolved material 不一致时，整个
  candidate-set read 返回 `SELECTED_MATERIAL_UNAVAILABLE`，不得把 partial set 交给后续 Model；
- healthy Catalog 下，现有 deterministic Planner 仍选择完全相同的 first candidate，并保持 duration、task type、
  task fields 与 `DETERMINISTIC_BUILT_IN_FALLBACK` 不变；
- candidate set 只包含 Java 已验证的 `LearningTaskPlan`，不保存 Content body、Prompt、Credential、learner state
  或 durable workflow state。

这里唯一需要特别审核的行为收紧是：当前实现只 exact-resolve first candidate，因此损坏的 secondary candidate
不会影响 deterministic result；S9A 为保证未来 Model 看到的整个 shortlist 都可信，会让任一 shortlisted
candidate 的 list/resolve inconsistency 使本次 planning fail closed。这不改变健康 Catalog 的结果，但扩大了
Content integrity failure 的检测范围。

### Architecture impact and extensibility fit

S9A 只在现有 `planner` module 内拆出 deterministic candidate boundary，不新增 schema、public HTTP field、Model
call、Prompt、Credential flow、top-level module 或 dependency。`PlanningCandidateSet` 是必要的 Domain Type，
因为它承载 non-empty、bounded order 与 first-fallback invariant；candidate reader 保持 concrete，因为当前没有
第二个 hard-filter implementation。它解决现有 provider-free Planner 与已批准 S9 Model path 必须共享同一合法
candidate truth 的当前 coupling，不为假想 variation 引入 abstraction。

### Verification

```text
cd server
./mvnw -q -Dtest=EligibleLearningTaskCandidateReaderTests,DeterministicLearningTaskPlannerTests,LearningTaskPlanningServiceTests test
git diff --check
```

测试至少覆盖：time-too-short no-read、filter/order/cap、exclusion、empty set、malformed identity、每个 shortlisted
candidate 的 exact resolve 与 mismatch fail-closed、first fallback regression，以及 immutable/non-empty/max-size
Domain invariant。S9A 不新增数据库行为，因此不以 PostgreSQL Integration 作为本 slice 的 required verification。

Zcode 必须报告实际 executed test count、failures / errors / skips；未运行的检查必须标为 `NOT_RUN`，不得沿用历史
evidence 作为 fresh result。

### Explicit non-scope

- S9B versioned Prompt、JSON output 与 validator；
- `PlanningRun`、candidate snapshot table、Flyway 或任何 durable schema；
- `LearningTask.recommendationReason` / `MODEL_ENRICHED`；
- Model Job、route、dispatch、wait、consume、fallback finalizer；
- Controller、request/header、Credential 或 API response；
- learner-state context、Practice、Evaluator、Content artifact 或 long-term state 修改。

### Zcode execution and handoff requirements

1. 开始前按 repository `AGENTS.md` 读取 `docs/planning/CURRENT_HANDOFF.md`，并核对实际 branch、HEAD、
   `git status --short`、本 slice 相关 Diff 与 `PROJECT_STATUS.md`；Git / source / tests 优先于 snapshot；
2. 保留当前已有 documentation changes，不覆盖、不提交、不把它们混入 Production implementation ownership；
3. 只实现 S9A，并执行上面的 targeted verification 与 `git diff --check`；
4. 完成后提供真实 Diff summary、verification evidence、Scope Match 与 Human Review Focus；
5. 将状态停在 `REVIEW_PENDING`，交由 Codex 执行 Critical Diff Review；不得开始 S9B，不得 commit / push / merge；
6. 不更新 `CURRENT_HANDOFF.md`，除非用户明确要求，或 Zcode 能确认自己的剩余额度严格低于 10%。

Human Review Focus：candidate set 的 defensive immutability、stable order / max-8 cap、secondary candidate
fail-closed、现有 first fallback 完全兼容，以及 `DeterministicLearningTaskPlanner` 是否只剩清晰的 result mapping。

当前 Stop Point：`S9A_IMPLEMENTATION`。Zcode 完成实现与 targeted verification 后必须停止在
`REVIEW_PENDING`；S9B 未授权。
