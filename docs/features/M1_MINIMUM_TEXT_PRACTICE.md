# M1 Minimum Text Practice Loop

> Status: APPROVED DESIGN
> Approved: 2026-09-03
> Production implementation scope: M1-S5 COMPLETE (`b6cde9d`)
> Current gate: M1-S5 COMPLETE / M1-S6 SCOPE_NOT_APPROVED
> Phase: M1

本文定义 M1 的目标行为、Architecture boundary、Content composition、核心 lifecycle、ModelCallJob
integration、failure invariant 与 implementation slice 顺序。它把已批准的 M1 Phase outcome 细化为可 Review
的设计，但不授权修改 Production Code、schema 或 API。

## 1. Decision summary

M1 交付第一条真实可运行的 Session-level learning flow：

```text
LanguageProfile
→ LearningTask
→ Text PracticeSession
→ DeterministicAssessment
→ optional Grounded Semantic Evaluation
```

M1 使用一套共用 Learning Workflow 支持多个目标语言，不为 Provider-free、English 或 Japanese 建立第二套
Planner、Practice Runtime、Evaluator、Evidence 或长期状态 authority。

M1 批准的 Built-in delivery matrix 为：

| Delivery order | Target language | Support language | Minimum scope |
| --- | --- | --- | --- |
| First walking skeleton | `en` | `zh-CN` | 两个 `FOUNDATION` text communication scenarios |
| Second language validation pack | `ja` | `zh-CN` | 一个 `FOUNDATION` clarification / repetition scenario |

`supportLanguage` 是解释与提示语言，不自动等于母语，也不能用于推断用户 Weakness、Mastery 或其他长期事实。

## 2. Phase goal and boundary

### 2.1 Observable outcome

用户在自己拥有的 `LanguageProfile` 下可以：

1. 获得由 Java hard constraint 保证合法的 `LearningTask`；
2. 启动并完成一次短 Text Practice；
3. 提交可追踪到具体 step / turn 的真实输入；
4. 获得 trusted event 支持的 deterministic result；
5. 有可用 Model capability 与 transient Credential 时，获得经过 grounding validation 的 semantic diagnosis；
6. Model unavailable、timeout、invalid output 或迟到时，仍保留已完成的 Practice 与 deterministic result。

### 2.2 M1 learning-state boundary

M1 只回答：

```text
这次计划了什么？
这次实际发生了什么？
这次 Session 可以确定地判断什么？
Model 提出了哪些已通过 grounding validation 的 semantic candidates？
```

M1 不把一次结果提升为长期学习状态。Raw Evidence persistence、qualification、aggregation、Weakness / Skill
lifecycle、Review 与 re-planning 属于 M2。M1 可以保存 Session-level assessment 与 validated semantic
candidate，但不得直接修改 Weakness、Level 或 Mastery。

## 3. Current system baseline

M0 已提供：

- authenticated `UserContext` 与 owner-scoped `LanguageProfile` identity；
- provider-agnostic `TextGenerationPort`；
- strict record-based Structured Output validation；
- PostgreSQL-backed `ModelCallJob` execution / consumption lifecycle；
- transient Credential boundary 与安全 metadata Trace。

当前 Production 已实现 M1-S2 deterministic Planner core、M1-S3 LearningTask persistence、M1-S4
owner-scoped planning API 与 M1-S5 PracticeSession start / response lifecycle；尚无 completion、assessment、
Evaluator 或 Evidence implementation。后续必须继续按 slice 完成 Session-level evaluation boundary，再把已有
Model infrastructure 作为 bounded semantic capability 接入；`ModelCallJob` 不拥有 Learning Workflow 或长期状态。

## 4. Target architecture

```text
Vue Practice UI
        ↓
Owner-scoped Learning APIs
        ↓
Explicit Application Services
        ├─ Plan Practice
        │    ├─ LanguageProfile ownership
        │    ├─ LearningMaterialCatalog
        │    └─ optional Planning ModelCallJob
        ├─ Run PracticeSession
        │    ├─ immutable PublishedLearningMaterial
        │    └─ trusted response / assistance events
        └─ Complete and Evaluate
             ├─ Java DeterministicAssessment
             └─ optional Evaluation ModelCallJob
                    ↓
             Structured Output Validation
                    ↓
             Java Grounding Validation
                    ↓
             ValidatedSemanticCandidate
```

不引入 generic workflow engine、Agent graph、dynamic handler registry 或第二套 offline runtime。Application
flow 使用职责明确的 service 和 typed Domain state 显式串联。

## 5. Module responsibility and dependency direction

### 5.1 Content boundary

Content 提供最窄的 `LearningMaterialCatalog` read boundary。Planner 与 Practice 读取 typed published material，
但不依赖 classpath、JSON 或未来 M3 database storage。

M1 使用 Backend-owned classpath JSON adapter；M3 可以通过 Composition 接入 Curated / Imported /
Published Content，不改变 Planner / Practice contract。

### 5.2 Planner

Planner 负责 candidate generation、hard filtering、fallback priority、optional soft enrichment 与最终 task
validation。它不生成完整 Content，不直接修改 Session、Memory、Weakness 或 Level。

### 5.3 Practice Runtime

Practice Runtime 负责 LearningTask → PracticeSession → trusted interaction → completion lifecycle。它保存实际发生的
learner response 与 assistance usage，但不决定长期能力状态。

### 5.4 Evaluator

Evaluator 组合 Java deterministic assessment 与 optional semantic diagnosis。它只能产出 Session-level result
和可供 M2 qualification 的 candidate，不能 activate Weakness、修改 Level 或直接标记长期 Mastery。

### 5.5 Existing AI infrastructure

Planner / Evaluator 只依赖 provider-neutral Model Gateway、Structured Output 与 ModelCallJob contract。Credential
继续 request-scoped / in-memory，不能进入 PostgreSQL、Redis、Trace、Log 或 durable Job payload。

Dependency direction 保持：

```text
API
→ Learning Application
→ Learning Domain / Content read contract
→ existing ModelCallJob / Model Gateway boundary when optional AI is requested
```

Content、ModelCallJob 与 Model Gateway 不反向依赖 Planner、Practice 或 Evaluator implementation。

## 6. Built-in Content composition

### 6.1 Avoid language-pair duplication

M1 不把每个 `targetLanguage × supportLanguage` 组合实现为一套完整课程。Published material 在概念上由以下
typed components 组成：

```text
TargetPracticeCore
        +
SupportScaffold
        ↓
PublishedLearningMaterial
```

`TargetPracticeCore` 由目标语言决定，至少包含：

- stable material identity；
- target language；
- difficulty / scenario / communication objective；
- target-language prompt / text；
- optional target writing-system reading information；
- typed text interaction steps；
- accepted answers 与 deterministic rubric；
- semantic rubric reference。

`SupportScaffold` 由辅助语言决定，至少包含：

- support language；
- instruction；
- explanation / translation；
- hint；
- optional pair-relevant contrastive note。

Pair-relevant note 只用于教学支架，不表示所有使用该 support language 的用户都会产生相同错误。长期 learner
truth 仍由个人 Practice Evidence 决定。

### 6.2 M1 physical artifact

M1 可以在同一 immutable classpath artifact 中保存 Target Core 与 typed `List<SupportScaffold>`，不提前实现
M3 Content assembly / publish pipeline。对外由稳定 `materialId + publishedVersion` 标识用户实际看到的完整
published content；source manifest 必须能解析 Target Core、selected Support Scaffold、source version、license
与 content hash。

修改 target content、support scaffold、rubric 或 source lineage 必须产生新 published version，不能覆盖旧
artifact 并改变历史 PracticeSession 的解释依据。

Built-in manifest 使用显式 `planningAvailability` 区分版本用途：`PLANNABLE` 版本可以进入新的 Planner
candidate list，`HISTORICAL_ONLY` 版本只供已有 `LearningTask` 按 `materialId + publishedVersion` 精确解析。
同一 `materialId` 最多只能有一个 `PLANNABLE` 版本；历史版本仍需完成完整 hash 与 artifact validation。

### 6.3 Availability and isolation

Built-in Practice 只有在以下条件同时满足时才 available：

```text
LanguageProfile.languageCode == material.targetLanguage
requested supportLanguage has a verified SupportScaffold
material and manifest validation pass
required Practice capability is available
```

缺少 `ja + zh-CN` material 时不得借用 `en + zh-CN`；缺少 requested scaffold 时，M1 明确返回 unavailable，
不调用 live Model 临时翻译并伪装成 published Provider-free Practice。

### 6.4 M1 language-specific boundary

English 与 Japanese 共用 workflow、state、API、assessment envelope 和 grounding validation。语言差异放在 typed
Content、prompt / rubric resource 与必要的 configuration 中，不在 Application Service 散落
`if (language == JAPANESE)`。

Japanese M1 允许：

- 正常 Japanese text；
- optional kana reading line；
- Chinese instruction / explanation / hint；
- material-owned accepted answer variants。

Japanese M1 不包含完整 kana curriculum、复杂 furigana editor、romaji mastery、通用汉字 / 假名等价推断、
pronunciation scoring 或 audio。

### 6.5 Deterministic text matching

M1 使用保守规则：

- 只执行明确批准的外层 whitespace 处理与 Unicode NFC normalization；
- 合法变体由 material 的 `acceptedAnswers` 显式列出；
- 不自动把所有 kanji / kana、hiragana / katakana 或不同语序认定为等价；
- 未被 deterministic rubric 覆盖的自由表达保存为 learner input，有 Model 时进入 semantic evaluation，
  无 Model 时不得伪造 correctness / naturalness result。

## 7. Planner design

Planner 执行：

```text
Owned LanguageProfile
→ Java candidate generation
→ target/support/difficulty/duration hard filtering
→ deterministic fallback priority
→ optional LLM candidate ranking / reason enrichment
→ Java candidate-id and constraint validation
→ persist LearningTask
```

LLM 只能在 Java 给出的 candidate set 中选择，不能创造 material、跨语言选择 Content、改变 hard constraint 或
持久化未通过 validation 的 task。

Model unavailable、Credential missing、capacity rejection、timeout、invalid structure、invalid candidate 或
interactive wait budget 耗尽时，Planner 使用同一 candidate set 生成合法 deterministic task。

### 7.1 Implemented M1-S2 boundary

M1-S2 已实现 `LearningTaskPlanner` / `DeterministicLearningTaskPlanner` module-local flow：从
`LearningMaterialCatalog` 获取候选，经 target/support/difficulty/duration/exclusion hard filtering 与稳定 identity
排序后重新按完整 `materialId + publishedVersion` 解析；list/resolve 不一致时 fail closed。成功只返回尚未持久化的
`LearningTaskPlan`，失败返回 typed `Unavailable`。本 slice 不写 PostgreSQL、不调用 Model、不创建 Session，也不修改
Profile、Evidence、Weakness、Level 或 Memory；durable task identity 与 lifecycle 由下述 M1-S3 boundary 接手。

### 7.2 Implemented M1-S3 boundary

M1-S3 已在既有 `planner` module 内实现并提交 LearningTask persistence：
`LearningTaskRepository.createOwned` 接收可信 `trustedUserId` 与 S2 `LearningTaskPlan`，通过 PostgreSQL
`INSERT ... SELECT` 原子校验 owner、Profile identity 与 target language；成功创建 UUIDv7 `PLANNED` row，保存
exact `materialId + publishedVersion`。`findOwned`、`tryStart` 与 `tryComplete` 始终使用
`taskId + trustedUserId + languageProfileId` scope；PostgreSQL conditional status predicate 裁决
`PLANNED → STARTED → COMPLETED`，重复、跳级、逆向与 wrong-owner/profile 请求不改变状态。

PostgreSQL 是 id、status 与 lifecycle timestamp authority；Java `LearningTask` 只恢复并复核 durable snapshot。
target language 从同一 `language_profile` row 还原，不在 task row 重复存储。本 slice 不接入 authenticated API、
不创建 PracticeSession、不调用 Model，也不保存 Content 本体、learner response、Prompt、Credential、Evidence 或
长期学习状态。Critical Review、PostgreSQL 18.6 / Flyway V1–V8 与 Integration verification 已通过；
Ownership `UNDERSTOOD`，implementation 已提交为 `45143af`。
真实调用链见 `docs/flow/learning-task-persistence.md`。

### 7.3 Implemented M1-S4 boundary

M1-S4 已在既有 `planner` module 内接入 authenticated、CSRF-protected owner-scoped planning HTTP API：
`LearningTaskPlanningService` 使用 trusted `UserContext` 读取 owned Profile，调用 deterministic Planner，并在
持久化前校验 Planner result 仍绑定请求的 Profile；`LearningTaskRepository.createOwned` 再通过 PostgreSQL
`INSERT ... SELECT` 原子重校验 owner、Profile 与 target language。成功返回数据库创建后的 durable
`PLANNED` task；invalid request、unknown / wrong-owner Profile、无 eligible material 与 Content contract
不一致使用 stable typed result，拒绝路径不产生 row。

本 slice 不创建 PracticeSession、不推进 Task lifecycle、不调用 Model，也不保存 Content 本体、learner response、
Credential、Evidence 或长期学习状态。Critical Review、PostgreSQL 18.6 / Flyway V1–V8、Application integration、
S3 regression 与 wider server regression 均通过；Behavior Flow `CURRENT`，Ownership `UNDERSTOOD`，用户提交为
`dd9559d`。真实调用链见 `docs/flow/owner-scoped-learning-task-planning.md`。

### 7.4 Implemented M1-S5 boundary

M1-S5 已在新的 `practice` module 内接入 authenticated、CSRF-protected PracticeSession start 与 learner response
HTTP API。`PracticeSessionApplicationService.start` 在同一 Spring transaction 内执行 owned Task read、exact
material resolution、`PLANNED → STARTED` conditional transition 与唯一 Session insert；数据库
`INSERT ... SELECT` 重校验 owner/profile/`STARTED`，`UNIQUE(task_id)` 提供第二层 guard。重复或并发 start
返回同一个 durable `IN_PROGRESS` Session，insert 失败整体 rollback，不留下孤立 `STARTED` Task。

response submission 先验证 exact material version 与 stepId，再以 `FOR UPDATE OF session` 锁定 owned Session；
PostgreSQL insert gate 重校验 owner/profile/`IN_PROGRESS`，`PRIMARY KEY(session_id, step_id)` 与
`ON CONFLICT DO NOTHING` 裁决首次接受、exact payload replay 或 different-payload conflict。learner text 原样保存，
HTTP 不回显 private text；start material projection 不包含 userId、accepted answers 或 semantic rubric。

本 slice 不提供 complete / abandon transition，不做 deterministic assessment 或语义评分，不调用 Model，也不写
Evidence、Weakness、Level、Mastery 或 Learning Memory。Critical Review 的两个 HIGH findings 与 external
integration-test finding 均已关闭；PostgreSQL 18.6 / Flyway V1–V9、S5 integration/concurrency、S3+S4 affected
regression 与 wider server regression 均通过；Behavior Flow `CURRENT`，Ownership `UNDERSTOOD`，用户提交为
`b6cde9d`。真实调用链见 `docs/flow/practice-session-lifecycle.md`。

## 8. Practice lifecycle and deterministic assessment

M1 conceptual lifecycle：

```text
LearningTask:    PLANNED → STARTED → COMPLETED
PracticeSession: IN_PROGRESS → COMPLETED | ABANDONED
```

M1 invariant：

- owner `userId + languageProfileId` 必须始终匹配；
- 一个 M1 LearningTask 最多启动一个 PracticeSession；
- response 通过 `sessionId + stepId` 建立稳定 identity；
- 相同 payload 的重复提交应幂等，已接受后提交不同 payload 必须 conflict；
- completed / abandoned Session 不能继续接受 learner response；
- Session completion 与 DeterministicAssessment persistence 必须在同一 transaction 内完成；
- Evaluation failure 不回滚或删除已经完成的 PracticeSession。

第一版 text step 可以区分：

- `EXACT`：accepted answer / rule 可以确定性判断；
- `SEMANTIC_ONLY`：保存 learner text，但 deterministic assessment 不声明语义正确或自然。

## 9. Grounded Evaluator

一次 `SessionEvaluationResult` 包含不同 provenance 的两个部分：

```text
DeterministicAssessment
+
Optional ValidatedSemanticCandidate
```

LLM semantic issue 输出不直接拥有 numeric text span authority。推荐 typed claim 至少包含：

```text
sourceTurnId
exactQuote
occurrenceIndex
issueType
explanation
confidence
```

Java 根据已保存 learner text 执行：

1. 验证 turn 属于当前 Session 与 LanguageProfile；
2. 验证 `exactQuote` 在 learner text 中存在；
3. 验证 occurrence 唯一或 `occurrenceIndex` 合法；
4. 由 Java 计算并保存 `startOffset / endOffset`；
5. 验证 issue type、confidence 与 task-specific rubric；
6. 拒绝引用 support text、assistant text、其他 Session 或不存在 span 的 claim。

invalid structure、unsupported claim、fake turn、quote mismatch 或 ambiguous occurrence 只令 semantic branch
失败，不影响 deterministic result。English 与 Japanese 使用同一 schema / validator，但使用独立 versioned
prompt / rubric resources。

## 10. ModelCallJob integration

`ModelCallJob` 保持通用 execution / consumption boundary，不认识 Planner 或 Evaluator。Owning Workflow 保存 Job
reference，并使用明确 identity：

```text
Planning:
workflowId = planningRunId
workflowStepId = PLAN_ENRICHMENT

Evaluation:
workflowId = evaluationRunId
workflowStepId = SEMANTIC_EVALUATION
```

Consumption 由 workflow-owned idempotent reconciler 负责：

- interactive wait 内完成：验证并 consume；
- Planning wait budget 耗尽：先持久化 deterministic task，迟到 enrichment 因 version 不再适用而 stale；
- Evaluation wait budget 耗尽：保留 deterministic result，semantic status 为 pending；
- Evaluation result 迟到且 workflow version 仍匹配：验证后 consume；
- Model failure / invalid output：只终止 model-derived branch；
- 不自动 retry，不引入 MQ，不建立 dynamic consumer registry。

API status read 与最小后台 reconciliation 可以复用同一个 typed、幂等 consumption service；准确 scheduling / polling
mechanism、wait budget 与 Production files 必须在对应 Current Slice Contract 中批准。

## 11. Data authority

| Data | M1 authority |
| --- | --- |
| Built-in material | Immutable Backend classpath artifact + validated source manifest |
| LearningTask | PostgreSQL + Java transition |
| PracticeSession / learner response | PostgreSQL + owner/profile constraints |
| DeterministicAssessment | Trusted Practice event + Java rule + PostgreSQL |
| EvaluationRun | PostgreSQL + Java lifecycle |
| Raw Model success/failure | Existing ModelCallJob tables |
| Validated semantic candidate | Java validation + PostgreSQL |
| Credential | Browser local/session → HTTPS transient backend memory |
| Long-term Evidence / Weakness / Mastery | Not implemented in M1; M2 authority |

## 12. Failure and security invariants

- request body、path 外的 `userId` 不得成为 ownership authority；
- `languageProfileId` 必须与 authenticated `UserContext.userId` 同时命中；
- support text 不得被 Evaluator 当作 learner output；
- Credential、Prompt、完整 learner response 与 generated diagnosis 不进入安全日志；
- invalid material / manifest / language pair fail closed；
- Model failure 不删除 Session 或 DeterministicAssessment；
- duplicate completion / evaluation / result consumption 必须通过 unique constraint、status guard 或 rowVersion
  防止重复 state transition；
- M1 semantic candidate 不直接写长期 Evidence / Memory；
- Hosted 与 Self-hosted 使用同一核心 Learning Workflow。

## 13. Extensibility fit

```text
Change Axis Evidence: FACT
Variation Type: Content source + target language + support scaffold + prompt/rubric
Decision: narrow read boundary + typed data + Composition
Current Problem Solved: M1 classpath content、M3 published content 与多个语言组合不能耦合到 Planner/Practice
Complexity Introduced: one material read port、typed Target Core / Support Scaffold、versioned resource selection
Revisit Trigger: M3 publish lifecycle、第二个 physical content source、真实 language policy duplication
Extensibility Fit: RIGHT_SIZED
```

M1 不引入 generic LanguageConfig platform、dynamic registry、generic Factory、Base Content class、arbitrary
option Map 或通用 normalization engine。只有出现重复且有证据的 language-specific behavior 时，才把资源差异
提升为明确 Policy / Strategy。

## 14. Approved phase slice plan

下表批准 Phase decomposition 和顺序，不自动批准任一 Production Current Slice Contract。每个 A 类 slice 在实现前
仍需确认目标、Expected Files、schema / API impact、verification 与 stop point。

| Slice | Goal | Observable behavior | Gate |
| --- | --- | --- | --- |
| M1-D1 | Record approved M1 design | Scope、Phase、Feature 与 Architecture docs 一致 | Documentation Review |
| M1-S1 | Built-in Content boundary + English artifact | 合法 `en + zh-CN` published material 可加载；损坏/不匹配 fail closed | A / Review |
| M1-S2 | Deterministic Planner core | 无 Provider 时从合法 candidate 产生稳定 LearningTask | A / Review |
| M1-S3 | LearningTask persistence | owner/profile、material version 与 task transition 可持久化验证 | A / Review |
| M1-S4 | Owner-scoped planning API | authenticated user 获得 Built-in task 或明确 unavailable | A / Review |
| M1-S5 | PracticeSession lifecycle | task 可启动 Session，response 幂等且非法 transition 被拒绝 | A / Review |
| M1-S6 | Deterministic completion | Session completion 与 deterministic assessment 原子保存 | A / Review |
| M1-S7 | Grounded Evaluator contract | fake turn、bad quote、ambiguous span 与 unsupported claim 被拒绝 | A / Review |
| M1-S8 | Evaluator ModelCallJob integration | deterministic result 不受 Model failure；迟到结果按 version 消费或 stale | A / Review |
| M1-S9 | Optional Planner enrichment | Model 只能选择合法 candidate；失败回到同一 deterministic path | A / Review |
| M1-S10 | Japanese validation pack | `ja + zh-CN` 使用同一 workflow，cross-language fallback / pollution 被拒绝 | A / Review |
| M1-S11 | Minimum Vue Practice UX | 用户可完成 task/session/evaluation；Credential 保持 transient | B / Review |
| M1-S12 | M1 integrated closeout | E2E、DB、security、Eval、Trace、client build 与 docs evidence 满足 exit criteria | Phase Closeout |

## 15. Verification strategy

验证从 closest relevant check 逐步扩大：

```text
Domain / resource unit tests
→ PostgreSQL migration and repository integration
→ owner / language / API contract tests
→ deterministic stub and production Adapter contract
→ Model failure / timeout / late-result / invalid-output tests
→ English/Japanese cross-language isolation
→ client production build
→ M1 end-to-end and wider server regression
```

Grounded Evaluator regression dataset 至少覆盖 malformed JSON、invalid enum、fake turn、support-text claim、quote
mismatch、ambiguous occurrence、out-of-range result、unsupported issue 与 Model failure。测试通过不自动证明学习有效性；
M1 只证明 contract、grounding、failure isolation 与 workflow behavior。

## 16. Explicit non-scope

M1 不实现：

- Raw Evidence → Aggregation → Long-term State；
- Weakness / Skill / Mastery / Level mutation；
- Review scheduling 与 re-planning；
- RAG、Tool Gateway、live Public Source connector 或 Content Agent；
- Content database、publish workflow 或大规模 curriculum；
- AI free Conversation runtime；
- deferred free-writing evaluation queue；
- automatic retry、Kafka / RabbitMQ 或 generic workflow engine；
- audio、STT、TTS、pronunciation scoring；
- 完整 kana curriculum、复杂 furigana UI 或通用日语答案等价引擎；
- 根据 `supportLanguage` 推断母语或长期 learner state。

## 17. Architecture impact and stop point

```text
Architecture-sensitive Feature: YES
Architecture Decision: APPROVED
Architecture Impact: in-boundary physicalization of approved Learning Domain modules
New ADR Required: NO
Phase Slice Plan: APPROVED
Production Current Slice Scope: M1-S5 COMPLETE (`b6cde9d`)；implementation / Review / external verification PASS；
  Ownership `UNDERSTOOD`
```

本设计不改变 Persistent Learner Model、Multi-language Isolation、AI vs Java Authority、Provider-agnostic Model
Gateway、BYOK Credential boundary 或 Hosted + Self-hosted core path。

当前 Stop Point：M1-S5 implementation、Critical Review、Behavior Flow、PostgreSQL/Flyway/Integration
verification、Human Ownership 与 implementation commit 均已完成。M1-S6 deterministic completion / assessment
必须另行完成 Design / Current Slice Contract / Scope approval，不因 M1-S5 完成自动开始。
