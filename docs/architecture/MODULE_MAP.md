# AI Language Tutor — Module Map

> 本文提供 AI Language Tutor 的模块级导航。
>
> 目标：
>
> - 快速回答“某项能力应该落在哪个模块”；
> - 帮助开发者与 Coding Agent 避免重复职责和跨层实现；
> - 为后续真实代码的 package / class / entry point 建立物理映射；
> - 降低每次开发重新扫描 repository 的 Context 成本。
>
> 本文当前分为：
>
> 1. Logical Module Map：基于已批准产品与架构设计；
> 2. Physical Module Map：基于实际 source code，当前待代码实现后逐步补充。
>
> 不得根据本文件中的逻辑模块名称自行编造 Java package 或 class。

---

# 1. Module Status / 状态说明

| Status | 中文说明 |
|---|---|
| `P0` | V1 核心能力 |
| `P1` | V1 应实现 / 条件实现 |
| `INTERFACE` | V1 保留扩展接口 |
| `PENDING` | 尚未完成最终 Scope 决策 |
| `BACKLOG` | V1 后实现 |

Implementation Status 单独使用：

| Status | 中文说明 |
|---|---|
| `NOT_STARTED` | 尚未实现 |
| `IN_PROGRESS` | 正在开发 |
| `IMPLEMENTED` | 已通过实际代码确认实现 |
| `PARTIAL` | 部分实现 |
| `DRIFT` | 实际代码与当前 Architecture Contract 不一致 |

V1 Scope 与 Implementation Status 是两个不同维度。

例如：

    V1 Scope: P0
    Implementation: NOT_STARTED

是完全正常的状态。

---

# 2. High-level Module Groups / 顶层模块分组

系统逻辑上分为五个主要区域：

    Client / UX
        │
        ▼
    Learning Application / Domain
        │
        ├───────────────┐
        ▼               ▼
    Bounded AI       Content System
    Capabilities
        │               │
        └───────┬───────┘
                ▼
         Platform / Infrastructure

---

## A. Client / UX

主要负责：

- Vue / PWA；
- Today；
- Practice UI；
- Conversation UI；
- Reading UI；
- Settings；
- Local Cache；
- Offline UX；
- browser-local BYOK credential。

客户端不拥有最终长期学习状态。

---

## B. Learning Domain

负责：

- Language Profile；
- Planner；
- Practice；
- Evaluator；
- Learning Memory；
- Weakness / Skill；
- Vocabulary；
- Review；
- Learning Preferences。

这是 Persistent Learner Model 的核心。

---

## C. Bounded AI Capabilities

负责：

- role-specific Context Assembly；
- Model Gateway；
- Structured Output；
- Prompt / Rubric Version；
- RAG（M3）；
- Tool Gateway（首个真实 tool-using flow 获批后）；
- Voice Provider；
- Agent execution support。

AI capability 为 Learning Application / Domain 提供受控语义能力，不拥有 Learning Workflow、
Practice lifecycle、Evidence aggregation 或长期状态。Model unavailable 不得破坏确定性状态处理与恢复。

---

## D. Content System

负责：

- Content Library；
- User Import；
- Content Pipeline；
- Chunk；
- Metadata；
- Embedding；
- Retrieval-ready content。

---

## E. Platform / Infrastructure

负责：

- Account / Auth；
- Security；
- Trace；
- Eval；
- Background Job；
- PostgreSQL；
- pgvector；
- Redis；
- Object Storage；
- Deployment。

---

# 3. Core Learning Domain Map / 核心学习域

## 3.1 Language Management

Source Module:

`28 — Language Management`

V1:

`P0`

Responsibility:

- 添加目标语言；
- 切换语言；
- Pause / Resume；
- Set Primary；
- Delete；
- Reassess；
- 建立独立 `LanguageProfile` workspace。

Core Identity:

    User
      ↓
    languageProfileId
      ↓
    Language-specific State

Provides:

- 当前 `languageProfileId`；
- language configuration；
- lifecycle state。

Depends On:

- Account / UserContext；
- Language Profile；
- Security。

Must Not:

- 跨语言共享 Level；
- 跨语言共享 Weakness；
- 跨语言共享 Vocabulary Mastery；
- 跨语言共享 Evidence。

Architecture Importance:

`A — Critical`

---

## 3.2 Language Profile

Source Module:

`1 — Language Profile`

V1:

`P0`

Responsibility:

提供当前语言学习状态的 compact summary。

Contains conceptually:

- overall level；
- Skill state summary；
- Weakness summary；
- Vocabulary summary；
- Communication summary；
- goals；
- preferences；
- recent state；
- evidence sufficiency。

Inputs:

    Learning Memory
    Skill / Weakness State
    Vocabulary State
    User Preferences

Outputs:

    Compact Learner State

Consumers:

- Planner；
- Progress；
- Evaluator；
- Today。

Must Not:

- 保存完整 Conversation History；
- 保存全部 Error Event；
- 保存完整 Practice History；
- 成为 Raw Evidence 仓库。

Architecture Importance:

`A — Critical`

---

## 3.3 Planner

Source Module:

`3 — Planner`

V1:

`P0`

Responsibility:

决定：

- 当前最值得练什么；
- 为什么；
- 难度；
- 时间；
- 训练约束。

Planner 是 hybrid component：Java 先生成 / 过滤合法 candidate，并持有 fallback priority；可选
LLM 只在合法集合内完成 soft ranking、scenario 与 reason enrichment。

Inputs:

- Language Profile；
- Active Weakness；
- Due Review；
- Recent Practice；
- Preferences；
- Available Time；
- User Feedback；
- Evidence Sufficiency。

Outputs:

`LearningTask`

典型内容：

- task type；
- difficulty；
- duration；
- primary goal；
- secondary goals；
- target weakness；
- target vocabulary；
- communication goals；
- reason。

Depends On:

- Language Profile；
- Review；
- Learning Preferences；
- Learner-state read tools；
- role-specific structured Context Assembly；
- Model Gateway（optional enrichment）。

Must Not:

- 直接修改 Weakness；
- 直接修改 Level；
- 直接修改 Learning Memory；
- 承担 Evaluator；
- 生成所有完整 Practice Content。

Architecture Importance:

`A — Critical`

---

## 3.4 Today / Learning Hub

Source Module:

`19 — Today / Learning Hub`

V1:

`P0`

Responsibility:

将：

    Planner Intent
    +
    User Immediate Choice
    +
    Review Need

组合为实际可执行的学习入口。

Provides:

- Today Focus；
- Planner candidates；
- Start；
- Replace；
- Skip；
- Easier；
- Harder；
- Replan；
- Free Practice。

Depends On:

- Planner；
- Language Profile；
- Preferences；
- Practice Runtime。

Must Not:

- 自己计算学习能力；
- 自己维护另一份 Planner State。

Architecture Importance:

`B — Important`

---

# 4. Practice Modules / 练习模块

## 4.1 Practice Runtime

Logical Shared Module.

Responsibility:

统一承载一次 Practice 生命周期：

    LearningTask
        ↓
    PracticeSession
        ↓
    User Interaction
        ↓
    COMPLETED
        ↓
    Evaluation

Core Output:

`PracticeSession`

Conceptual states:

    CREATED
    IN_PROGRESS
    PAUSED
    COMPLETED
    ABANDONED

EvaluationRun 使用独立 `PENDING / RUNNING / SUCCEEDED / FAILED` lifecycle，Evaluator failure 不修改
已经完成的 PracticeSession 事实。

Consumers:

- Evaluator；
- Learning Memory；
- Trace。

Must Not:

- 自己决定长期 Weakness；
- 自己改变长期 Level。

Architecture Importance:

`A — Critical`

---

## 4.2 Conversation

Source Module:

`6 — Conversation`

V1:

`P0`

V1 Modes:

- `TEXT_CHAT`
- `SCENARIO_ROLEPLAY`

Voice:

`P1`

Responsibility:

训练：

- expression；
- follow-up；
- clarification；
- elaboration；
- naturalness；
- communication；
- fluency；
- register。

Inputs:

- LearningTask；
- Scenario；
- Communication Goals；
- Target Vocabulary；
- Recent Conversation Context。

Depends On:

- Practice Runtime；
- Context Manager；
- Model Gateway；
- limited Tool Gateway；
- Vocabulary；
- Evaluator。

Must Not:

- 逐句默认 Grammar correction；
- 直接写长期 Communication Skill；
- 直接修改 Weakness。

Architecture Importance:

`A — Critical`

---

## 4.3 Reading

Source Modules:

- `5 — Reading`
- `11 — Reading Practice`

V1:

`P0`

Responsibility:

真实文本理解训练。

Provides:

- Original Reading；
- Sentence Explain；
- Vocabulary Lookup；
- Translation Practice；
- Comprehension；
- Reading Evidence。

Depends On:

- Practice Runtime；
- Content Library；
- Vocabulary；
- Language Tools；
- Evaluator；
- TTS（可选）。

Must Not:

- 因大量查词直接降低用户 Level；
- 将材料过难全部归因用户 Weakness。

Architecture Importance:

`A — Critical`

---

## 4.4 Vocabulary

Source Modules:

- `4 — Vocabulary`
- `10 — Vocabulary State`

V1:

`P0`

Responsibility:

维护 Personal Vocabulary Bank。

Supports:

- WORD；
- PHRASE；
- COLLOCATION；
- EXPRESSION。

State:

    NEW
    LEARNING
    WEAK
    MASTERED
    IGNORED

Mastery dimensions:

- recognition；
- recall；
- usage；
- listening（按实现阶段）。

Depends On:

- Practice Evidence；
- Learning Memory；
- Review；
- Language Tools。

Must Not:

- 用 encounter count 直接决定 Mastery；
- 跨语言共享掌握状态。

Architecture Importance:

`A — Critical`

---

## 4.5 Listening

Source Module:

`12 — Listening Practice`

V1:

`P1`

V1 Depth:

基础。

Responsibility:

    Text
    → TTS
    → Listening
    → Comprehension
    → Evaluator

Initial Audio Authenticity:

- `CONTROLLED`
- `NATURAL_CLEAR`

Advanced:

- `CASUAL`
- `REAL_WORLD`

进入 Backlog。

Must Not:

- 将 STT 识别失败直接等同发音问题；
- 根据单次 Listening 更新整体 Level。

Architecture Importance:

`B / A depending on state logic`

---

## 4.6 Language Fundamentals

Source Module:

`13 — Language Fundamentals`

V1:

`P1`

Depth:

Minimal。

Responsibility:

帮助存在文字系统或基础发音门槛的用户尽快进入真实 Practice。

Examples:

Japanese:

- Hiragana；
- Katakana；
- basic Kanji recognition；
- basic input。

Spanish:

- basic pronunciation；
- accents；
- `ñ`。

Must Not:

- 扩张成大型基础课程；
- 长期阻挡用户进入真实 Practice。

Architecture Importance:

`B — Important`

---

## 4.7 Grammar Repair

Source Module:

`14 — Grammar Repair`

V1:

`P0 — Lightweight`

Responsibility:

针对已经由 Learning Memory 确认的重复结构性 Weakness，执行：

    Repeated Weakness
          ↓
    Short Explanation
          ↓
    2–3 Micro Exercises
          ↓
    Real Practice Transfer

Depends On:

- Learning Memory；
- Weakness State；
- Practice Runtime；
- Evaluator。

Must Not:

- 单次错误触发完整 Grammar Repair；
- 扩张成 Grammar Course。

Architecture Importance:

`B — Important`

---

# 5. Evaluation & Persistent Memory / 评估与长期学习状态

## 5.1 Evaluator

Source Module:

`7 — Evaluator`

V1:

`P0`

Responsibility:

对一个 `PracticeSession` 组合 deterministic assessment 与可选 semantic diagnosis。

Inputs:

- PracticeSession；
- LearningTask；
- compact Profile；
- task-specific Rubric。

Outputs:

- deterministic task completion / duration / attempts / assistance / exact result；
- strengths；
- issue candidates；
- vocabulary result；
- communication result；
- independence；
- confidence；
- Evidence candidates。

Depends On:

- trusted Practice event；
- role-specific Context Assembly；
- Model Gateway（semantic evaluation only）；
- Structured Output Validation；
- Prompt / Rubric Version。

Must Not:

- activate Weakness；
- change Level；
- directly mark long-term Mastery；
- 默认读取全部长期 Memory。

Model failure 只阻止 model-derived Evidence，不抹掉由 trusted Practice event 独立确定的
deterministic assessment / Evidence。

Architecture Importance:

`A — Critical`

---

## 5.2 Learning Memory

Source Module:

`8 — Learning Memory`

V1:

`P0`

Responsibility:

将 Raw Evidence 聚合为长期学习状态。

Conceptual structure:

    Raw Evidence
        ↓
    Aggregated Memory
        ↓
    Long-term State

Inputs:

- Error Evidence；
- Correct Evidence；
- Communication Evidence；
- Vocabulary Evidence；
- User Feedback Evidence。

Outputs:

- Weakness State update；
- Skill State update；
- Vocabulary State update；
- Profile summary update；
- Planner-readable summaries。

Depends On:

- Evidence Store；
- Java aggregation rules；
- state repositories。

Must Not:

- 把单个 LLM judgment 直接转成长期开关；
- 只记录错误而忽略正确 Evidence。

Architecture Importance:

`A — Critical`

---

## 5.3 Weakness / Skill State

Source Module:

`9 — Weakness / Skill State`

V1:

`P0`

Weakness lifecycle:

    CANDIDATE
    ACTIVE
    IMPROVING
    INACTIVE
    REACTIVATED

Weakness attributes:

- severity；
- confidence；
- evidence count；
- recent error rate；
- trend；
- first / last seen。

Skill attributes:

- level；
- mastery；
- confidence；
- evidence sufficiency；
- trend；
- success rate；
- independent success rate。

Depends On:

- Learning Memory aggregation。

Must Not:

- 使用固定“错 N 次”作为完整判断逻辑；
- 将 Severity 与 Confidence 合并成同一概念。

Architecture Importance:

`A — Critical`

---

## 5.4 Review System

Source Module:

`15 — Review / Spaced Repetition`

V1:

`P0`

Responsibility:

回答：

> 哪些学习对象值得再次出现？

Review targets:

- Vocabulary；
- Weakness；
- Communication Skill；
- Useful Expression；
- Grammar Repair Result；
- Listening Recognition（按 V1 实现范围）。

Default Mode:

`AUTO_INTERLEAVED`

Depends On:

- Learning Memory；
- Weakness；
- Vocabulary；
- Practice History。

Consumer:

- Planner。

Must Not:

- 代替 Planner 决定完整今日计划；
- 独立维护另一份 mastery truth。

Architecture Importance:

`A — Critical`

---

# 6. Learning Preference & Feedback / 偏好与反馈

## 6.1 Learning Preferences

Source Module:

`20 — Learning Preferences`

V1:

`P0 — Lightweight`

Responsibility:

记录用户明确选择的学习偏好。

Includes:

- daily time；
- practice preference；
- topic preference；
- difficulty；
- conversation assist；
- correction；
- review；
- audio authenticity。

Scope:

    Global
    +
    Per-language Override

Must Not:

- 参与用户能力评分；
- 把一次 Daily Choice 自动变成永久 Preference。

Architecture Importance:

`B — Important`

---

## 6.2 Practice Feedback

Source Module:

`18 — Practice Feedback`

Current Scope:

`PENDING`

Design responsibility:

- TOO_EASY；
- JUST_RIGHT；
- TOO_HARD；
- Evaluation disagreement；
- experience feedback。

Should produce:

`UserFeedbackEvent`

Must Not:

- 单次反馈直接修改 Level；
- 把用户反馈直接当 Ground Truth。

---

# 7. Content System / 内容系统

## 7.1 Content Library / Explore

Source Module:

`22 — Content Library / Explore`

V1:

`P0`

Responsibility:

统一组织：

- SYSTEM_CONTENT；
- AI_GENERATED；
- USER_IMPORT；
- 后续 OPEN_CONTENT。

Provides:

- search；
- filter；
- topic；
- difficulty；
- duration；
- content type；
- source。

Consumers:

- User；
- Planner；
- Reading；
- RAG。

Must Not:

- 根据用户等级硬锁全部内容；
- 自动删除疑似重复用户内容。

Architecture Importance:

`B / A for permission boundary`

---

## 7.2 Content Pipeline

Source Module:

`23 — Content Pipeline`

V1:

`P0`

V1 Inputs:

- TXT；
- Markdown；
- PDF。

Flow:

    Upload
      ↓
    Dedup
      ↓
    Parse
      ↓
    Normalize
      ↓
    Chunk
      ↓
    Metadata
      ↓
    Embedding
      ↓
    Index

State:

    UPLOADING
    PARSING
    READY_BASIC
    INDEXING
    READY_FULL
    FAILED

Key Design:

`READY_BASIC` 后即可部分学习。

Must Not:

- 等全部 Embedding 完成才允许使用已经成功解析的内容；
- 默认将用户导入内容公开。

Architecture Importance:

`A — Critical`

---

# 8. Retrieval & Context / 检索与上下文

## 8.1 RAG / Retrieval

Source Module:

`24 — RAG / Retrieval`

V1:

`P1 / M3`

V1 Strategy:

    Structured Metadata Filter
    +
    pgvector
    +
    Top-K

Advanced Hybrid Retrieval:

`BACKLOG / V1.1`

Logical retrieval domains:

- Personal Memory；
- Content Library；
- User Import。

Use RAG For:

- semantic history；
- long documents；
- content retrieval。

Do Not Use RAG For:

- exact Level；
- exact Weakness status；
- exact Review state；
- exact Vocabulary Mastery。

Must Not:

- 把 Retrieval Result 直接视为长期状态事实；
- 跨 user / languageProfile 检索。

Architecture Importance:

`A — Critical`

---

## 8.2 Context Manager

Source Module:

`26 — Context Manager`

V1:

`P0 minimal structured assembly / P1-M3 retrieved-context expansion`

Responsibility:

为需要模型的 bounded role 选择：

**necessary and sufficient context**

Context layers:

- Static Context；
- Learner State；
- Session Context；
- Retrieved Context。

Priority:

- CRITICAL；
- HIGH；
- MEDIUM；
- LOW。

Typical compression order:

    structured trimming
    ↓
    dedupe
    ↓
    Top-K
    ↓
    old-message summary
    ↓
    optional LLM compression

Must Not:

- 默认加载全部长期 Memory；
- 把全部 target vocabulary / weaknesses 塞给所有 Agent；
- 破坏语言隔离。

Architecture Importance:

`A — Critical`

---

# 9. Agent Runtime / Agent 基础设施

## 9.1 Tool Gateway

Source Module:

`25 — Tool Calling / Tool Gateway`

V1:

`CONDITIONAL — first approved tool-using flow`

Responsibility:

统一：

    Schema
    ↓
    Auth
    ↓
    UserContext
    ↓
    Permission
    ↓
    Validation
    ↓
    Execute
    ↓
    Timeout / Retry
    ↓
    Trace

Agent-specific Tool Allowlist:

- Planner；
- Conversation；
- Evaluator；

必须最小授权。

Planner、Evaluator 默认使用 bounded model task，不为其预建空 Tool Gateway 或 Agent Loop。

Core Mutation Rule:

    Agent
      ↓
    submitEvidence()
      ↓
    Java Domain Logic

Forbidden normal Agent tools:

- `setLevel()`；
- `setWeakness()`；
- arbitrary DB mutation。

Architecture Importance:

`A — Critical`

---

## 9.2 Model Gateway

Source Module:

`27 — AI Model / Provider & BYOK`

V1:

`P0`

Responsibility:

统一 Provider 接入。

Conceptual structure:

    Domain / Agent
         ↓
    Model Gateway
         ↓
    Provider Adapter
         ↓
    External Provider

Possible providers:

- DeepSeek；
- OpenAI；
- Gemini；
- Ollama；
- OpenAI-compatible。

Provides:

- capability check；
- model invocation；
- structured output support；
- retry；
- usage；
- trace。

Must Not:

- 让 Planner / Evaluator 等直接依赖具体 Provider SDK。

Architecture Importance:

`A — Critical`

---

## 9.3 Structured Output Validation

Logical Shared Module.

Responsibility:

    Model Output
        ↓
    Parse
        ↓
    Schema Validate
        ↓
    Enum Validate
        ↓
    Semantic Validate
        ↓
    Qualification

Consumers:

- Planner；
- Evaluator；
- Tool Calling；
- Content AI tasks。

Failure rule:

**Invalid Output must not mutate Persistent Learning State.**

Architecture Importance:

`A — Critical`

---

# 10. Voice Runtime / 语音运行层

Source Module:

`30 — Voice / Pronunciation`

V1:

- STT: `P1`
- TTS: `P1`
- Pronunciation Assessment: `BACKLOG`

Logical components:

- SpeechToText Provider；
- TextToSpeech Provider；
- Pronunciation Provider interface。

Voice Conversation:

    Audio
      ↓
    STT
      ↓
    Conversation
      ↓
    Text
      ↓
    TTS

Must Not:

- STT success = pronunciation accuracy；
- STT failure = pronunciation weakness。

Architecture Importance:

`B`, pronunciation state integration later may become `A`.

---

# 11. Account, Security & Data / 账户与数据安全

## 11.1 Account / Auth

Source Module:

`29 — Account / Auth`

V1:

- Hosted: `P0`
- Self-hosted: simplified mode

Responsibility:

Hosted:

- Register；
- Login；
- Logout；
- Current User；
- Authenticated UserContext。

Self-hosted:

- `REGISTRATION_ENABLED=false` 时 bootstrap / reuse persistent singleton User；
- `REGISTRATION_ENABLED=true` 时复用 Hosted registration / login path。

Core Rule:

业务层统一通过 `UserContext`。

Must Not:

- 信任前端自报 userId；
- 信任 LLM Tool 参数中的 userId。

Architecture Importance:

`A — Critical`

---

## 11.2 Security / Privacy / Permission

Source Module:

`35`

V1:

`P0`

Responsibility:

- user isolation；
- languageProfile isolation；
- RAG permission；
- Tool permission；
- upload validation；
- BYOK protection；
- trace redaction；
- XSS/basic rate limit。

Architecture Importance:

`A — Critical`

---

## 11.3 Learning Vault

Source Module:

`29 — Learning Vault`

V1:

`P1`

Responsibility:

产品级 Data Export / Import。

Must include versioning:

- schemaVersion；
- appVersion。

Initial import strategy:

- IMPORT_AS_NEW；
- REPLACE。

Advanced merge:

`BACKLOG`

Must Not:

- 默认导出 API Key。

Architecture Importance:

`B — Important`

---

# 12. Observability & Eval / 可观测性与评测

## 12.1 Trace / Observability

Source Module:

`31`

Engineering Priority:

`P0++`

Responsibility:

回答：

> 一次 Agent 行为究竟发生了什么？

Trace covers:

- HTTP；
- Agent；
- Tool；
- RAG；
- LLM；
- Evaluator；
- long-term state mutation。

Metadata may include:

- traceId；
- spanId；
- provider/model；
- promptVersion；
- rubricVersion；
- contextStrategyVersion；
- latency；
- token usage；
- status。

Must Not:

- 保存 API Key；
- 默认保存完整私密内容；
- 修改学习状态。

Architecture Importance:

`A — Critical`

---

## 12.2 OpenTelemetry

Source Module:

`31`

V1:

基础。

Purpose:

统一分布式 Trace 标准。

Possible flow:

    Spring Boot
        ↓
       OTLP
        ↓
    Jaeger / Tempo

Architecture Importance:

`B — Important`

---

## 12.3 Eval System

Source Module:

`32`

Engineering Priority:

`P0++`

Responsibility:

判断：

> AI behavior 是否正确？

Initial targets:

- Evaluator；
- RAG；
- Planner。

V1 target:

约 20–50 cases / important module，根据真实开发量调整。

Eval types:

- Rule-based；
- Reference-based；
- limited LLM Judge。

Must preserve versions:

- promptVersion；
- rubricVersion；
- contextStrategyVersion；
- datasetVersion。

Architecture Importance:

`A — Critical`

---

## 12.4 Prompt / Rubric / Context Version

Source Module:

`32`

V1:

Required.

Responsibility:

让 AI 行为变化：

- 可追踪；
- 可比较；
- 可回归测试。

Used By:

- Evaluation Result；
- Trace；
- Eval Result。

Architecture Importance:

`A — Critical`

---

## 12.5 Optimization / Self-improvement

Source Module:

`33`

V1:

`INTERFACE`

V1 Loop:

    Trace
      ↓
    Feedback
      ↓
    Eval
      ↓
    Human Change
      ↓
    run-eval

Advanced Optimization Agent:

`BACKLOG`

Must Not:

- 自动发布 Prompt；
- 自动修改 Production Config。

---

# 13. Infrastructure / 基础设施

## 13.1 PostgreSQL + pgvector

V1:

Core Infrastructure.

Responsibilities:

PostgreSQL:

- structured domain state；
- account；
- profile；
- evidence；
- practice metadata；
- job state。

pgvector:

- semantic retrieval index。

Core Rule:

**Structured State != Vector Retrieval State**

---

## 13.2 Redis

Source Module:

`36`

Engineering Priority:

`P0`

V1 Real Usage:

- Profile Summary Cache；
- Dictionary Cache；
- Content Metadata Cache；
- Model Proxy Rate Limit；
- temporary state。

Must Not:

- 成为唯一长期学习状态 Source of Truth。

---

## 13.3 Object Storage

Source Module:

`36`

V1:

`P1`

Logical abstraction:

`ObjectStorageService`

Initial implementation:

MinIO。

Hosted can later use:

S3-compatible storage。

Responsibility:

- imported files；
- audio；
- large objects。

---

## 13.4 Background Job

Source Module:

`36`

V1:

Basic.

Architecture:

    Spring TaskExecutor
    +
    DB Job State

Targets:

- PDF parse；
- embedding；
- indexing；
- TTS；
- other long-running tasks。

Kafka:

`BACKLOG — scale driven`

Do not introduce Kafka only for engineering display.

---

## 13.5 Deployment

Source Module:

`36`

V1:

`P0`

Modes:

- Hosted；
- Self-hosted。

Core rule:

**Same core codebase.**

Initial infrastructure:

- frontend；
- backend；
- PostgreSQL + pgvector；
- Redis；
- MinIO；
- Flyway；
- Health Check；
- Reverse Proxy；
- Docker Compose。

Later only if scale requires:

- Kubernetes；
- Kafka；
- Service Mesh；
- Redis Cluster；
- Sharding。

---

# 14. Pending Modules / 尚未最终裁剪

当前 V1 Scope 文档仍有四个模块处于 `PENDING`。

## Module 16 — Progress

Suggested:

`P1`

Do not treat suggestion as final V1 decision.

---

## Module 17 — Continuous Assessment + Milestone

Suggested:

核心规则可能 P0/P1。

Advanced mechanisms deferred.

仍需 Final Scope Decision。

---

## Module 18 — Practice Feedback

Suggested:

轻量 feedback P0/P1。

Advanced dispute verification later.

仍需 Final Scope Decision。

---

## Module 34 — Notifications / Learning Recall

Suggested:

PWA Push P1 / Backlog。

仍需 Final Scope Decision。

---

# 15. Key Dependency Direction / 核心依赖方向

推荐保持以下逻辑方向：

    UI
     ↓
    Learning Application Workflow
     ↓
    Domain Services
     ↓
    Persistence / Bounded AI Capabilities / Infrastructure

核心学习闭环：

    Planner
      ↓
    Practice
      ↓
    Evaluator
      ↓
    Evidence
      ↓
    Learning Memory
      ↓
    Skill / Weakness / Vocabulary State
      ↓
    Profile
      ↓
    Planner

AI 调用（只在需要 semantic capability 时）：

    Learning Domain
        ↓
    Role-specific Context Assembly
        ↓
    Model Gateway
        ↓
    Provider

Tool 调用：

    Agent
      ↓
    Tool Gateway
      ↓
    Application Service
      ↓
    Domain

RAG：

    Domain / Context Manager
        ↓
    Retrieval
        ↓
    pgvector / Content

RAG Result 返回 Context。

它不直接进入长期 State Mutation。

---

# 16. Forbidden Dependency Patterns / 禁止依赖模式

以下依赖应视为 Architecture Drift 信号。

### Provider Leakage

    Planner
      ↓
    DeepSeekClient

应保持：

    Planner
      ↓
    ModelGateway
      ↓
    DeepSeekAdapter

---

### Agent Direct State Mutation

    Evaluator
      ↓
    WeaknessRepository.save(ACTIVE)

应保持：

    Evaluator
      ↓
    Evidence
      ↓
    LearningMemory
      ↓
    Java Aggregation
      ↓
    Weakness State

---

### RAG as Source of Truth

    Planner
      ↓
    Vector Search
      ↓
    "Current Level"

应保持：

    Planner
      ↓
    Profile / Structured Service
      ↓
    Current Level

---

### Cross-language State Leakage

    userId
      ↓
    all Weaknesses
      ↓
    Planner

应保持：

    userId
    +
    languageProfileId
      ↓
    language-scoped state

---

### Frontend Authority

    Vue
      ↓
    calculate Weakness
      ↓
    save final state

应保持：

    Vue
      ↓
    Practice / Feedback
      ↓
    Backend Domain Rules
      ↓
    Persistent State

---

# 17. Physical Module Map / 实际代码映射

> 本节只能根据实际 repository source code 更新。
>
> 当前不得通过设计文档猜测 package / class。

初始模板：

| Logical Module | Source Path | Main Entry | Core Classes | Tests | Implementation |
|---|---|---|---|---|---|
| Client / UX | `client/src` | `client/src/main.ts` | `App.vue` application shell | Build verification only | PARTIAL |
| Language Management | `server/src/main/java/com/dailylanguage/user` | `UserRepository` | `UserRepository`, `UserMapper` | `PersistenceIdentityIntegrationTests`, `MapperSqlSafetyTests` | PARTIAL — persistence identity only |
| Language Profile | `server/src/main/java/com/dailylanguage/languageprofile` | `LanguageProfileRepository` | `LanguageProfileIdentity`, `LanguageProfileRepository`, `LanguageProfileMapper` | `PersistenceIdentityIntegrationTests`, `MapperSqlSafetyTests` | PARTIAL — identity and ownership query only |
| Planner | TBD | TBD | TBD | TBD | NOT_STARTED |
| Practice Runtime | TBD | TBD | TBD | TBD | NOT_STARTED |
| Conversation | TBD | TBD | TBD | TBD | NOT_STARTED |
| Reading | TBD | TBD | TBD | TBD | NOT_STARTED |
| Vocabulary | TBD | TBD | TBD | TBD | NOT_STARTED |
| Evaluator | TBD | TBD | TBD | TBD | NOT_STARTED |
| Learning Memory | TBD | TBD | TBD | TBD | NOT_STARTED |
| Weakness / Skill | TBD | TBD | TBD | TBD | NOT_STARTED |
| Review | TBD | TBD | TBD | TBD | NOT_STARTED |
| Content Pipeline | TBD | TBD | TBD | TBD | NOT_STARTED |
| RAG | TBD | TBD | TBD | TBD | NOT_STARTED |
| Context Manager | TBD | TBD | TBD | TBD | NOT_STARTED |
| Tool Gateway | TBD | TBD | TBD | TBD | NOT_STARTED |
| Model Gateway | TBD | TBD | TBD | TBD | NOT_STARTED |
| Trace | TBD | TBD | TBD | TBD | NOT_STARTED |
| Eval | TBD | TBD | TBD | TBD | NOT_STARTED |
| Security | `server/src/main/java/com/dailylanguage/security`, `server/src/main/java/com/dailylanguage/authentication` | `SecurityConfiguration`, `LocalRegistrationController`, `LocalAuthenticationRepository`, `LocalPasswordHasher`, `RedisAuthenticationAttemptRateLimiter`, `PersistentSingleUser` | trusted `UserContext`, ownership access boundary, local registration/login/logout/me, password policy and Argon2id, Redis Session, SPA CSRF, authentication throttling, hash concurrency gate and singleton bootstrap | `AuthenticationHttpContractTests`, `LocalRegistrationLoginIntegrationTests`, `RedisAuthenticationSessionIntegrationTests`, `PasswordHashConcurrencyGateTests`, `RedisAuthenticationAttemptRateLimiterIntegrationTests`, `SingleUserPersistenceIntegrationTests` | COMPLETE — M0-S4 authentication / UserContext foundation; Hosted capacity remains provisional until M6 |
| Persistence Infrastructure | `server/src/main/resources/db`, `server/src/main/resources/mapper`, `server/src/main/java/com/dailylanguage/persistence` | Flyway, MyBatis Mapper XML | PostgreSQL UUID TypeHandler, parameterized Mapper statements, auth identity and credential schema | `PersistenceIdentityIntegrationTests`, `LocalAuthenticationPersistenceIntegrationTests`, `MapperSqlSafetyTests` | PARTIAL — identity and local credential foundation |
| Infrastructure | `compose.yaml`, `server/src/main` | `compose.yaml`, `DailyLanguageApplication` | PostgreSQL + pgvector, Redis, externalized connection and health configuration | `DailyLanguageApplicationTests`, Compose/runtime health verification | PARTIAL |

`NOT_STARTED` 只是当前文档初始化默认值。

如果实际 repository 已经存在实现：

必须通过真实代码检查后修改。

---

# 18. Physical Mapping Update Rule / 物理映射更新规则

一个模块开始真实实现后，至少补：

    Source Path
    Main Entry
    Core Classes
    Main Dependencies
    Main Tests
    Implementation Status

例如未来可能形成：

    Planner

    Path:
    backend/.../planner/

    Entry:
    <actual class after implementation>

    Depends:
    Profile
    Review
    Context
    ModelGateway

    Tests:
    <actual test>

但以上路径和类名必须来自真实 source code。

不得提前设计成事实。

---

# 19. Navigation Strategy / Codex 导航策略

处理一个局部 Feature 时推荐：

    MODULE_MAP
        ↓
    Locate Logical Module
        ↓
    Physical Mapping
        ↓
    Main Entry
        ↓
    Direct Dependency
        ↓
    Relevant Test

如果以上信息已经足够：

**STOP SEARCHING。**

不要每次重新扫描整个 repository。

---

# 20. Module Map Maintenance / 维护规则

当出现以下变化时检查本文件：

- 新顶层逻辑模块；
- 模块职责变化；
- package 大规模移动；
- main entry 改变；
- dependency direction 改变；
- module 被拆分或合并；
- Architecture Decision 改变模块边界。

普通：

- DTO；
- Helper；
- Mapper；
- 小 Service；
- Test；

无需全部登记。

MODULE_MAP 记录：

**Architecture-relevant Components**

而不是：

**Every Class in Repository。**

---

# 21. Final Module Summary / 模块摘要

整个系统核心可以压缩为：

    Language Management
            ↓
      Language Profile
            ↓
         Planner
            ↓
     Practice Runtime
      ├─ Conversation
      ├─ Reading
      ├─ Vocabulary
      ├─ Listening
      └─ Grammar Repair
            ↓
        Evaluator
            ↓
         Evidence
            ↓
     Learning Memory
            ↓
    Weakness / Skill /
    Vocabulary State
            ↓
         Review
            ↓
         Planner

Supporting Agent Runtime:

    Context Manager
    RAG
    Tool Gateway
    Model Gateway
    Structured Output

Supporting Platform:

    Security
    Trace
    Eval
    PostgreSQL + pgvector
    Redis
    Object Storage
    Background Job
    Deployment

模块设计必须始终服务于：

**Persistent Learner Model + Controlled Agent Runtime。**
