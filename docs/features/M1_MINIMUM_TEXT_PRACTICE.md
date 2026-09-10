# M1 Minimum Text Practice Loop

> Status: APPROVED DESIGN
> Approved: 2026-09-03
> Production baseline: M1-S8T COMPLETE — PR #25 merged to main（`c2dbb4f`）
> Current candidate: M1-S9A Current Slice Contract APPROVED for Zcode；implementation NOT_STARTED
> Current gate: M1-S9A `IMPLEMENTATION`
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

当前 committed Production baseline 已实现 M1-S2 deterministic Planner core、M1-S3 LearningTask persistence、
M1-S4 owner-scoped planning API、M1-S5 PracticeSession start / response lifecycle、M1-S6 deterministic
completion / assessment、M1-S7 module-local Grounded Evaluator contract 与 M1-S8A owner-scoped
`GroundedEvaluationInputReader` 读取入口（见 7.7；S8A 已提交为 `226b804`，其前 S7 为 `7deb720` +
`e93f624`）。M1-S8B EvaluationRun / ModelCallJob 原子创建入口已提交为 `2d46df6`（见 7.8）；M1-S8C
durable result grounding、candidate / safe rejection persistence 与 Job/Run 原子 terminal transition 已提交为
`de29ada`（见 7.9）。M1-S8D 已提交 versioned prompt/request、EVALUATION route 与 transient dispatch（见 7.10，
`8228d64`）。S8E-R 已实现 terminal failure 与 unavailable result reconciliation 并提交为 `bf02aed`（见 7.11）；
S8E-API 已实现 owner-scoped HTTP trigger / reconciliation（见 7.12）。S8T-A/B/C 已随 PR #25 merge to main
as `c2dbb4f`，完成最小 guided text learning。scheduler、automatic retry 与遗留 CREATED/RUNNING recovery
未实现；长期 Evidence 从 M2 开始。

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

### 7.5 Implemented M1-S6 boundary

M1-S6 已在既有 `practice` module 内接入 authenticated、CSRF-protected completion
HTTP API。`PracticeSessionApplicationService.complete` 先以 owner/profile scope 对 Session 执行
`SELECT ... FOR UPDATE OF session`，并与 response submission 保持相同的 Session-row-first 锁序；随后校验
Session `IN_PROGRESS`、Task `STARTED`，按 Task 保存的 exact `materialId + publishedVersion` 解析完整
`TextPracticeStep` 集合，并要求每个 material step 都已有一条 accepted response。

Java 使用版本化 `M1_TEXT_EXACT_V1` policy 计算 step 结果：`EXACT` 只执行 outer strip、Unicode NFC 与
case-sensitive exact comparison，产生 `MATCHED / NOT_MATCHED`；`SEMANTIC_ONLY` 固定产生
`NOT_APPLICABLE`。wrong answer 不阻止 completion，`COMPLETED` 只表示练习流程完成，不表示掌握或长期状态变化。

首次合法 completion 在同一个 transaction 内依次执行 Session `IN_PROGRESS → COMPLETED`、一条
`deterministic_assessment` header、每个 material step 一条 `deterministic_step_assessment`、Task
`STARTED → COMPLETED`，最后 durable reread。任一步 mutation、constraint 或 reread 失败都会回滚整个 transaction，
不留下 completed Session、started Task 或部分 assessment。重复或并发 completion 只读取 durable Session、Task
与 assessment，不重新依赖 catalog；completion 与 response submission 的共同 Session 行锁阻止 terminal Session
接受迟到 response。

本 slice 不实现 abandon、semantic evaluation、Evidence、Weakness、Level、Mastery 或 Learning Memory mutation，
不调用 Model Gateway，也不接触 Credential。Critical Review / Architecture PASS；PostgreSQL 18.6 上 Flyway
V1–V10、targeted integration 47/47 与 wider server regression 564 tests / 0 failures / 0 errors / 11 Redis 相关
条件跳过均已验证；Behavior Flow 已同步，Ownership `UNDERSTOOD`。真实调用链见
`docs/flow/practice-session-lifecycle.md`。

### 7.6 Implemented M1-S7 boundary

M1-S7 committed baseline 在独立 `evaluator` package 内实现无副作用的 Grounded Evaluator contract。
`SemanticGroundingValidator.validate` 接收不可信 JSON 与由可信 Java 调用方组装的
`GroundedEvaluationInput`，先检查原始 JSON token 类型，防止 record binding 把 `1.9`、`"1"` 或 numeric enum
静默 coercion 成合法值，再复用 `StructuredOutputValidator` 完成封闭 record / enum binding。

Java 随后校验 Task、completed Session、DeterministicAssessment、exact material identity 与全部 learner responses
属于同一个 user / `languageProfileId` / Session / material step 集合。该对象一致性检查不是 authorization proof；
owner-scoped durable reads 由 M1-S8A `GroundedEvaluationInputReader.readOwned` 承担（见 7.7）。

每条 claim 使用 `sourceTurnId + exactQuote + occurrenceIndex` 引用 learner 原文。定位为 case-sensitive、无 strip、
无 Unicode normalization 的 literal match；occurrence 为 0-based 且包含重叠匹配，Java 计算 UTF-16
`[startOffset, endOffset)` 并拒绝切开 surrogate pair。English classpath rubric
`builtin-text-communication-rubric/v1` 当前只允许 `GRAMMAR / NATURALNESS / TASK_RESPONSE`。rubric 和 grounding
只能证明引用来源、位置与 issue 类型边界合法，不能证明 diagnosis 语义正确或形成长期 Weakness。

任一 claim 失败即整批 `Rejected`，不暴露部分 candidate；既有 completed Session 与 deterministic assessment
保持不变。当前 slice 不调用 Model、不读 Credential、不写数据库、cache、event、Evidence、Memory、Weakness、
Level 或 Mastery，也不提供 HTTP API。Critical Review 与 Architecture PASS；PostgreSQL 18.6 empty schema Flyway
V1–V10、affected integration 50/50、wider server regression 622 tests / 0 failures / 0 errors / 11 Redis 相关条件跳过
均通过；Ownership `UNDERSTOOD`，用户提交为 `7deb720`。随后批准的 non-behavioral extraction 把
`RubricSource` 与 `ClasspathRubricSource` 迁移到独立文件，当前结构为 7 个 Production Java files / 643 行；
delta Review 与本地 unit 55/55 PASS，按用户要求未重跑外部容器验证。真实调用链见
`docs/flow/grounded-semantic-validation.md`。

### 7.7 Implemented M1-S8A boundary

M1-S8A（已提交为 `226b804`）在 `evaluator` package 内新增
`GroundedEvaluationInputReader.readOwned(languageProfileId, sessionId, userContext)` 与 sealed
`GroundedEvaluationInputResult`，把 S7 integration test 手工组装 trusted input 的前提落实为生产读取边界：

- caller identity 只取自 `UserContext`；`findOwned` ownership 失败返回不可区分的 `NotFound`，且发生在读取任何
  private learner text、assessment 或 Task 之前；
- 非 COMPLETED owned Session 返回 `NotCompleted`；Task 缺失 / caller-Profile 不一致 / Task 非 COMPLETED /
  assessment 缺失或跨 Session / material identity 或 target language 不匹配 / material steps 重复 / response
  缺失、额外、重复或跨 Session 均返回 `InconsistentSnapshot`；exact material 无法按 Task 保存的完整 identity +
  supportLanguage 解析返回 `MaterialUnavailable`；HISTORICAL_ONLY 版本只要 exact identity 可解析即允许读取，
  绝不 `listAvailable` 重选或读取最新版本，也不跨 Profile / target language / support language fallback；
- 入口运行在 Spring bean 短 readOnly transaction（无 FOR UPDATE、零写入、不调用 Model / Job / Credential）；
  learner text 与全部 accepted responses 原样透传；基础设施异常原样上抛。`Ready` 只代表输入可用于
  evaluation，不代表 Model diagnosis 正确，也不授权长期状态变化；S7 validator 保留独立检查。

验证：既有 Zcode evidence 为 Reader 18/18、S7 validator 33/33、rubric 22/22、planning 18/18、practice service
33/33 本地 PASS。Codex unit regression 73/73 PASS；首轮 DB 运行发现 2 个 test-level findings（重复提交断言与
MyBatis 一级缓存遮蔽 fixture 删除），测试内修复后 delta Review PASS。2026-09-06 正常 MyBatis cache 配置下
Reader integration 5/5 与 S7 integration 3/3 PASS（0 failures / 0 errors / 0 skipped），覆盖 owner/Profile 隔离、
读取前后零 mutation 与 Reader → S7 Validator durable text offsets；disposable PostgreSQL 18.6 empty schema
Flyway V1–V10 10/10 PASS，临时容器已清理。早期因未设置 RUN_DATABASE_TESTS 的 skipped 不作为通过证据。
Critical Review / external verification / documentation reconciliation 已完成。S8A 不单独 Explain Back，正式
Ownership Check 按用户决定留到 S8 完整闭环后。

### 7.8 Implemented M1-S8B boundary

M1-S8B（Review / external verification PASS，COMPLETE `2d46df6`）在 dispatch 之前原子建立 Evaluation workflow
identity。`EvaluationRunCreationService.createForReadyInput(ready, userContext, resultExpiresAt)` 在
`@Transactional(REQUIRES_NEW)` 独立事务内执行固定顺序：

1. `Ready.userId` 与 `UserContext.userId` 一致性裁决（不一致即 `InconsistentInput`，不读取任何数据）；
2. `findOwnedForUpdate` 锁定 owner-scoped Session（`FOR UPDATE OF session` 是唯一串行化点）；
3. 锁定后再检查 COMPLETED 与 `session.taskId == ready.task.id`；
4. 查询既有 Run：存在则校验关联 Job 的完整创建期 identity（workflowId=run.id、stepId
   `SEMANTIC_EVALUATION`、version 0、purpose EVALUATION、operation TEXT_GENERATION、owner/profile 一致）；
   缺失或不一致属于持久化不变量损坏，以不携带敏感信息的 `IllegalStateException` fail closed，不创建
   替代 Job、不静默修复（不被上层当作普通输入拒绝）；完整则返回 `Existing(run, job)`；
5. `SELECT uuidv7()` 先取得 Run id（`model_call_job.workflow_id` 必须精确等于它）；
6. 创建 EVALUATION / TEXT_GENERATION Job（provider/model 空、CREATED / NOT_READY、expiresAt =
   resultExpiresAt）；
7. owner-scoped `INSERT … SELECT` 插入 `evaluation_run`：数据库内除 owner/profile/COMPLETED 外，还 JOIN
   `model_call_job` 核对完整创建期 identity（owner/profile、purpose、operation、workflowId=runId、
   stepId、version），任一不匹配零行并以异常 fail closed。

事务成功返回即 Run + Job 已 durable 提交；任一步失败整体回滚，不留 orphan Job。重复 / 并发请求经 Session
行锁串行化后返回同一 `Existing` 关联，不创建第二次 Evaluation；`UNIQUE (session_id)` 与
`UNIQUE (model_call_job_id)` 是并发第二层约束（Flyway V11，S8B 生命周期封闭为 PENDING / completed_at
NULL）。`evaluation_run` 不保存 user/profile，ownership 经 session → learning_task 还原。本 slice 不调用
`TextGenerationJobStart` 或任何 submission / dispatch boundary，不持久化 Prompt / Rubric / request /
Credential / evaluation 结果；`resultExpiresAt` 是内部可信参数，有效期配置由 S8D 决定。

验证（2026-09-07）：`EvaluationRunCreationServiceTests` 14/14 与 affected unit regression 本地 PASS；
`EvaluationRunCreationIntegrationTests` 8/8 在本地 docker compose 内临时 PostgreSQL 18.6 空库
（`daily_language_s8b_check`，非开发库，已清理）PASS，覆盖 Flyway V1–V11 全量迁移、Run/Job UUIDv7 与
workflow 字段、重复 / 并发（两线程恰一个 `Created` + 一个 `Existing`、各仅一行）、wrong owner / 同用户另一
Profile / 未知 Session 不创建、IN_PROGRESS `NotCompleted`、Job 与 Run insert 失败整体回滚零 orphan，以及
Repository 级 insert gate 直测（六类创建期 identity 偏差 Job——含 purpose / operation / foreign owner——全部
被数据库拒绝零行，正确 identity 通过）；wider server regression 667 tests / 0 failures / 0 errors / 11 Redis
条件跳过 PASS。Codex Critical Review 4 findings（insert gate 未验证 Job identity、Existing 漏检
modelOperation、持久化损坏误分类为 `InconsistentInput`、固定过期日期）已全部修复，delta Review PASS。
Codex fresh external verification 使用独立临时 PostgreSQL 18.6：empty schema Flyway V1–V11 11/11、S8B
integration 8/8、affected ModelCallJob regression 103/103 PASS，0 failures / 0 errors / 0 skipped；验证后
`evaluation_run` 零行，ModelCallJob fixture 随临时数据库整体删除，primary database 未使用。S8B 已提交为
`2d46df6`。真实调用链见 `docs/flow/evaluation-run-creation.md`。

### 7.9 Implemented M1-S8C boundary

M1-S8C（Critical Diff Review / Architecture / external verification PASS，COMPLETE `de29ada`）实现
`EvaluationResultConsumptionService.consumeForReadyInput(ready, userContext)`。入口在单一 read-write transaction
内固定执行：

1. caller identity 只取 `UserContext.userId`，先比较 Ready userId；
2. 经 `run → session → task → profile` owner/profile-scoped query 锁定 `EvaluationRun` 行；
3. 重校验 Ready 与 Run 的 Session/Task/completion identity；terminal Run 只读取 durable outcome 并返回 `Existing`；
4. PENDING Run 必须绑定完整匹配的 EVALUATION / TEXT_GENERATION Job（workflowId、stepId、version、owner/profile）；
5. 仅对 `SUCCEEDED / NOT_READY` Job 读取 durable text result，交给 S7 `SemanticGroundingValidator`；
6. 使用 PostgreSQL `CURRENT_TIMESTAMP` expiry、workflow version 与 rowVersion gate CAS 为 `CONSUMED`；
7. `Validated` 保存唯一 candidate header 与有序 claims，再 CAS Run 为 `SUCCEEDED`；`Rejected` 只保存安全
   `RejectionReason`，再 CAS Run 为 `FAILED`。

Job consumption、candidate/claims 或 rejection、Run terminal transition 在同一 transaction 提交。claim insert、
candidate gate 或 Run finalize 任一步失败都整体回滚，Job 保持 `NOT_READY`，Run 保持 `PENDING`。Run 行锁串行化
相同 Evaluation consumer；通用 Job consumer 的竞争由 Job CAS 后重读 durable row 分类。S8C 交付时，
`CREATED / RUNNING` 返回 `Pending`，其他 terminal/depleted state 返回 `DeferredToReconciliation`；当前代码已由
S8E-R 归约这些 terminal/depleted state，见 7.11。无法解释的 identity/CAS 不一致继续 fail closed。

Flyway V12 扩展 `evaluation_run` terminal lifecycle 并新增 normalized
`validated_semantic_candidate / validated_semantic_claim`。composite FK 保证 candidate Session 等于 Run Session，
claim `(session_id, source_turn_id)` 必须引用该 Session 的 accepted response；数据库同时封闭 terminal/rejection
pairing、claim index、issue type、confidence 与 grounding policy version。candidate 是 Session-level diagnosis candidate，
不创建长期 Evidence，不修改 Memory、Weakness、Level 或 Mastery；不保存 Credential、完整 Prompt 或 rejected raw output。

验证（2026-09-07）：S8C service 19/19、affected unit 106/106 PASS；disposable PostgreSQL 18.6 empty schema
Flyway V1–V12 12/12，S8C integration 12/12、affected integration regression 43/43 PASS（0 failures / 0 errors /
0 skipped），覆盖 validated / zero-claim / rejection、terminal replay、并发单次消费、owner/profile isolation、
candidate-before-consume gate、claim failure rollback 与 invariant corruption。两个临时数据库已删除，primary database
未使用，未执行 Flyway repair 或 checksum 修改。Production/test compilation、Mapper XML parse 与 whitespace checks
PASS；未重跑 repository full server suite。初始 LOC guardrail 超出已由用户明确接受；Review 无 blocking code finding。
真实调用链见 `docs/flow/evaluation-result-consumption.md`。S8D prompt / route / transient dispatch 已实现；S8E-R
已在相同入口补齐 Model failure 与 unavailable result reconciliation，见 7.11；S8E-API HTTP orchestration 见 7.12。

### 7.10 Implemented M1-S8D boundary

M1-S8D（Critical Diff Review / Architecture / external verification PASS，COMPLETE `8228d64`）实现
`EvaluationDispatchService.dispatchForReadyInput(ready, userContext, credential)`。入口先调用
`EvaluationTextRequestFactory`，把 workflow version 0 显式映射到 classpath prompt v1 与 exact target-language
rubric，生成 `ModelPurpose.EVALUATION + JsonObject` provider-neutral request。

request 只包含 target language、difficulty、scenario、Task primary goal、communication objective、target text、
step id/kind/prompt、learner `sourceTurnId + learnerText`、deterministic step result 与 rubric definitions。它不包含
user/profile/session UUID、Credential、timestamp、support scaffold、accepted answer 或长期 learner state。Prompt
把 USER JSON 限定为数据，并声明只有 learner text 可以作为 claim 引用来源；实际 schema、quote、occurrence、offset
和 rubric allowlist 仍由 S7 Java grounding 裁决。

`EvaluationDispatchService` 使用 `@Transactional(NEVER)`；request 可用后计算 UTC now + configured TTL（默认 7d），
调用 S8B `REQUIRES_NEW` 创建/读取 durable Run/Job。只有 `Created` 会调用共享
`TextGenerationJobDispatch.dispatchCreated`；`Existing` 返回同一 durable Run/Job，不重新提交 Provider call。共享
dispatch 验证 CREATED/NOT_READY、TEXT_GENERATION 与 purpose identity，通过既有 bounded TaskExecutor / Worker
传播 memory-only request/Credential；capacity unavailable CAS Job 为 `SUBMISSION_REJECTED`，unknown submission
exception 原样传播，不做可能重复调用的补偿。Model Gateway 新增固定 EVALUATION route，默认 model
`deepseek-v4-flash`、execution timeout 30s。

验证（2026-09-08）：fresh targeted unit/config regression 29/29 PASS；disposable PostgreSQL 18.6 empty schema
Flyway V1–V12 12/12，`EvaluationDispatchIntegrationTests` 1/1 PASS，验证 Worker 在 Provider call 前能读取已提交
Run/Job、异步结果可由 S8C 消费、repeat dispatch 只调用一次 Provider、route 与 TTL 生效。Affected verification
reports 243/243 PASS；full server regression 700 tests / 0 failures / 0 errors / 159 environment-conditional skips
（实际执行 541）。Integration 使用受控 `TextGenerationPort` mock，未访问 live Provider；临时数据库已删除，
primary database 未使用，PostgreSQL / Redis 恢复停止。真实调用链见
`docs/flow/evaluation-model-dispatch.md`；共享 Job 链路见 `docs/flow/text-generation-job-start.md`。

已批准且仍保留的限制：durable commit 后、memory submission 前若进程终止，Job 可能停留在 `CREATED`；S8D
不自动 retry。S8E-R 已能在 trusted caller 重新进入 consumption flow 时归约 terminal Model failure、expiry 与
stale/depleted result；S8E-API 可显式返回 `Pending` 并提供 reconciliation 入口，但自动扫描与 retry 仍未实现。

### 7.11 Implemented M1-S8E-R boundary

M1-S8E-R（Critical Diff Review / Architecture / PostgreSQL-Flyway-Integration PASS，COMPLETE `bf02aed`）
在既有 `EvaluationResultConsumptionService.consumeForReadyInput` 内实现 workflow-owned reconciliation kernel，未新增
HTTP entry、scheduler、retry 或 Provider call：

1. owner/profile-scoped Run 行锁、Ready/Run/Job full identity gate 与 terminal replay 继续复用 S8C；
2. `CREATED / RUNNING` Job 返回 `Pending`，不猜测 execution outcome；
3. `FAILED / TIMED_OUT / OUTCOME_UNKNOWN / SUBMISSION_REJECTED` 把 Run 终结为
   `FAILED + MODEL_CALL_FAILED`；Job 保存具体 execution status；
4. `PENDING_CONFIRMATION / EXPIRED / STALE / DISCARDED`、旧 workflow result 或数据库判定已过期的 result 把 Run
   终结为 `FAILED + MODEL_RESULT_UNAVAILABLE`；
5. current workflow 的 `SUCCEEDED / NOT_READY` 仍进入 S8C grounding + consume；旧 workflow 在读取/grounding raw
   result 前通过 Job CAS 标记 `STALE`；
6. Job consumption/expiry/stale 与 Run terminal mutation 位于同一 transaction；无法解释的 identity、rowVersion 或
   state race 抛出不携带敏感数据的异常并回滚；
7. terminal model failure replay 返回 durable Run 与空 grounding result，不重新调用 Validator 或 Provider。

`EvaluationRun.FailureReason` 是 closed workflow-level category：`GROUNDING_REJECTED` 必须与安全
`groundingRejectionReason` 成对；`MODEL_CALL_FAILED / MODEL_RESULT_UNAVAILABLE` 必须没有 grounding reason。
Flyway V13 在 PostgreSQL 中保存相同约束，回填 V12 已有 `FAILED` Run 为 `GROUNDING_REJECTED`，并增加
`(created_at, id) WHERE status='PENDING'` partial index，为后续受控查询保留数据库访问路径。具体 Model failure/status
仍只保存在绑定 `ModelCallJob`，不复制 raw output、Prompt、Credential 或 private context。

验证（2026-09-08）：final targeted 35/35 PASS；implementation-stage full server regression 703 tests / 0 failures / 0 errors /
160 environment-conditional skips（实际执行 543）。Fresh disposable PostgreSQL 18.6 empty schema 从 V1 应用 Flyway
V1–V13 13/13；Evaluation result consumption、Run creation、dispatch 与 ModelCallJob consumption integration 合计
28/28 PASS。独立 V12→V13 upgrade probe 在 V12 与升级后各 10/10 PASS，历史 grounding `FAILED` Run 保留
`QUOTE_MISMATCH` 并正确回填 `GROUNDING_REJECTED`；V13 constraints 与 partial index 已查询确认。临时容器与数据库
已删除，primary database 未使用，既有 PostgreSQL / Redis 保持 healthy。真实调用链见
`docs/flow/evaluation-result-consumption.md`。

S8E-R 只提供可复用的幂等 reconciliation kernel。S8E-API 已通过两个显式 HTTP mutation entry 复用该 kernel；
background scheduler、automatic retry 与遗留 `CREATED / RUNNING` recovery 仍未实现。

### 7.12 Implemented M1-S8E-API boundary

M1-S8E-API（implementation / Critical Diff Review / Architecture / PostgreSQL-Flyway-Integration PASS，未 commit）
新增 `EvaluationController` 与 `PracticeSessionEvaluationService`，接通 completed Practice 到 durable Evaluation
outcome 的 owner-scoped HTTP vertical slice：

1. `PUT .../evaluation` 接收 body `providerId` 与 header `X-Model-Provider-Credential`，要求 authentication + CSRF；
2. Application service 先调用 `GroundedEvaluationInputReader.readOwned`，ownership/profile failure 在读取 private
   learner text 与处理 Credential 前返回；
3. Java 验证 `ProviderId`、non-blank Credential 与 fixed `EVALUATION / TEXT_GENERATION` route 精确匹配，再使用
   `TransientProviderCredential` 调用 S8D dispatch；
4. `Created / Existing` 都立即进入 S8E-R consumption。新建 Job 仍在运行时返回带 durable Run snapshot 的
   `Pending`；重复 trigger 读取同一 Run/Job，不重新提交 Provider；
5. `PUT .../evaluation/reconciliation` 不接收 Credential、Provider、Job id 或 raw Model output，也不 route lookup
   或 dispatch，只通过 owner-scoped Reader 与 consumption kernel 推进或 replay 同一 durable Run；
6. `PENDING → 202 Accepted + reconciliation Location`；durable `SUCCEEDED / FAILED → 200 OK`。Response 只包含
   Run/Profile/Session、status/timestamps、closed failure category、safe grounding reason 与 validated candidate /
   claims，不返回 userId、Job id、workflow/row version、Credential、Prompt 或 raw output；
7. invalid provider/credential 为 400，Session not found 为 404，未 completed 为 409，route provider mismatch 为
   422，trusted input 或 configuration unavailable 为 503；持久化不变量损坏继续异常 fail closed。

两个 orchestration method 使用 `@Transactional(NEVER)`，保留 Reader readOnly、S8B `REQUIRES_NEW`、S8D
transaction-free dispatch 与 S8C/S8E-R read-write transaction 的既有边界。Model 仍只产生 candidate；Java 保持
owner isolation、schema/semantic grounding、状态转换与 persistence authority。Evaluation failure 不删除 completed
Practice 或 deterministic assessment，也不创建长期 Evidence 或修改 Memory、Weakness、Level、Mastery。

验证（2026-09-09）：Critical Diff Review Scope MATCH，Code Review / Architecture PASS，无 blocking finding；local
targeted 45 discovered、43 executed、2 database-conditional skipped，full server 727 tests / 0 failures / 0 errors /
162 environment-conditional skips（实际执行 565）。Fresh disposable PostgreSQL 18.6 empty schema 从 V1 应用 Flyway
V1–V13 13/13，pgvector 0.8.6；S8 evaluator integration 31/31 PASS，覆盖 Reader 5、Grounding 3、Run creation 8、
Dispatch / HTTP API 2、Result consumption 13。HTTP integration 验证 trigger → async Job → reconciliation →
`SUCCEEDED`、terminal replay 不二次调用 Provider、foreign owner 返回 404，以及 Credential 不进入 Job durable JSON。
Integration 使用受控 `TextGenerationPort` mock，未调用 live Provider；临时容器已删除，primary database 未使用，
未执行 Flyway repair 或 schema-history 修改。真实调用链见 `docs/flow/evaluation-api-orchestration.md`。

S8E-API 不包含 scheduler、automatic retry、legacy `CREATED / RUNNING` recovery、GET polling、SSE/WebSocket、
Frontend UI、Trace persistence 或 M2 qualification。完整 M1-S8 implementation 已形成；按批准 cadence，
2026-09-09 full-loop Ownership Check 已通过：用户能够
区分 `ModelCallJob` execution/consumption 状态与 `EvaluationRun` business outcome，并正确说明 Model execution
`SUCCEEDED + CONSUMED` 仍可因 Java grounding rejection 形成 EvaluationRun
`FAILED + GROUNDING_REJECTED`。Understanding `UNDERSTOOD`；Human Touch `NOT_REQUIRED`。

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

LLM semantic issue 输出不直接拥有 numeric text span authority。M1-S7 已实现的 typed claim 包含：

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
失败，不影响 deterministic result。当前只发布 English versioned rubric；Japanese 将在 M1-S10 使用同一
schema / validator 与独立 versioned prompt / rubric resource 验证 language isolation。

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
| M1-S8T | Minimum guided text learning | COMPLETE — 示范、解释、理解检查、辅助使用及必要辅助条件记录已随 PR #25 merge | A / Complete |
| M1-S9 | Optional Planner enrichment | DESIGN APPROVED — S9A Deterministic Candidate Set APPROVED for Zcode | A / Implementation |
| M1-S10 | Japanese validation pack | `ja + zh-CN` 使用同一 workflow，cross-language fallback / pollution 被拒绝 | A / Review |
| M1-S11 | Minimum Vue Practice UX | 用户可完成 task/session/evaluation；Credential 保持 transient | B / Review |
| M1-S12 | M1 integrated closeout | E2E、DB、security、Eval、Trace、client build 与 docs evidence 满足 exit criteria | Phase Closeout |

2026-09-07 Scope Decision：S8T 在完整 S8 结束后、S9 前设计并交付；标识独立于 S8A–E，保留既有编号。
教学目标与证据边界见 [`GUIDED_LANGUAGE_LEARNING.md`](GUIDED_LANGUAGE_LEARNING.md)。本增量不改写
S1–S8 的历史实现合同；新材料结构、API、schema、评价兼容性及支持的起始能力需在 S8T Current Slice
Contract 中明确并批准，必要时拆分。S11 最小 UX 应支持该教学场景，S12 按更新后的 Phase criteria 验收。

M1-S9 Design / Scope、Architecture alternatives 与 implementation slice breakdown 见
[`PLANNER_ENRICHMENT.md`](PLANNER_ENRICHMENT.md)，已于 2026-09-10 获用户批准。S9A Current Slice Contract
随后获批交由 Zcode 实现；该授权不包含 S9B 或其他后续 slice。

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
Production Baseline: M1-S8T COMPLETE（PR #25 merge `c2dbb4f`）
Current Candidate: M1-S9A Current Slice Contract APPROVED for Zcode；implementation NOT_STARTED
```

本设计不改变 Persistent Learner Model、Multi-language Isolation、AI vs Java Authority、Provider-agnostic Model
Gateway、BYOK Credential boundary 或 Hosted + Self-hosted core path。

当前 Stop Point：`M1-S9A IMPLEMENTATION`。Zcode 只实现 S9A Deterministic Candidate Set，完成 targeted
verification 后停在 `REVIEW_PENDING` 并交由 Codex Review；不得自动开始 S9B。
