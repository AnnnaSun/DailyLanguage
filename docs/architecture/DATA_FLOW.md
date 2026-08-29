# AI Language Tutor — Data Flow

> 本文描述 AI Language Tutor 中核心数据如何产生、读取、验证、聚合、持久化和重新进入学习闭环。
>
> 本文重点回答：
>
> - 数据从哪里来？
> - 哪个模块可以读取？
> - 哪个模块可以修改？
> - 哪些数据属于长期状态？
> - 哪些数据只是一次 Session 的 Evidence？
> - RAG、LLM、Tool、Frontend 分别拥有什么数据权限？
> - 数据失败时如何避免污染 Persistent Learner Model？
>
> 本文描述的是 Logical Data Flow（逻辑数据流）。
>
> 实际 Java class、repository、transaction boundary 和 database table 必须在真实实现后补充，不得根据本文自行编造。

---

# 1. Source & Scope / 文档依据与范围

当前 Architecture Data Flow 以以下顺序为主要依据：

1. 当前已批准 V1 Scope；
2. Detailed Function Design；
3. Core User Flow；
4. PRD。

如果旧文档与更新后的 Architecture Design 出现冲突：

应显式记录并处理。

不要静默混合两个不同版本的数据模型。

---

## 1.1 Current Data Architecture Decision / 当前数据架构决定

Hosted Mode 当前采用：

    Vue / PWA
        ↓
    HTTPS
        ↓
    Spring Boot
        ↓
    PostgreSQL + pgvector
    Redis
    Object Storage

其中：

    PostgreSQL
    → 核心 structured learning state

    pgvector
    → semantic retrieval index

    Redis
    → cache / rate limit / temporary state

    Object Storage
    → imported files / audio / large objects

    IndexedDB
    → local cache / offline / recent-detail session data

IndexedDB 不是 Hosted Mode 下长期学习状态的唯一 Source of Truth。

---

# 2. Core Data Principle / 核心数据原则

整个系统最重要的数据原则是：

    Practice
        ↓
    Observation
        ↓
    Evaluation
        ↓
    Evidence
        ↓
    Aggregation
        ↓
    Long-term State

禁止：

    Practice
        ↓
    LLM Judgment
        ↓
    Long-term State

中间必须存在：

- Structured Evaluation；
- Validation；
- Evidence；
- Java Qualification；
- Aggregation。

---

# 3. Data Authority / 数据权责

系统中的数据大致分为五层。

| Layer | 中文 | Example | Authority |
|---|---|---|---|
| User Input | 用户输入 | message、answer、feedback | User |
| Session Data | 单次会话数据 | PracticeSession | Application |
| Deterministic Assessment | 确定性评估 | completion、attempt、assistance、exact result | Java + trusted Practice Event |
| Semantic Candidate | AI 语义候选 | naturalness、semantic issue、communication quality | LLM + Java Validation |
| Evidence | 学习证据 | ErrorEvent、Success Evidence | Java-qualified |
| Long-term State | 长期状态 | WeaknessState、SkillState | Java Domain Rules |

核心关系：

    LLM
    → proposes / diagnoses

    Java
    → validates / qualifies / aggregates / persists

---

# 4. Core Data Objects / 核心数据对象

## 4.1 LanguageProfile / 语言画像

用途：

当前某门语言长期学习状态的 compact summary。

核心身份：

    languageProfileId

典型字段：

- languageCode；
- overallLevel；
- skillStates；
- weaknessSummary；
- vocabularySummary；
- goals；
- recentState；
- preferences；
- evidenceSufficiency。

主要来源：

    Learning Memory
    +
    Skill State
    +
    Weakness State
    +
    Vocabulary State
    +
    User-editable Preferences

主要消费者：

- Planner；
- Today；
- Evaluator；
- Progress。

LanguageProfile 不保存：

- 完整 Conversation；
- 全部 Raw Evidence；
- 完整 Practice History。

---

## 4.2 LearningTask / 学习任务

Planner 的主要输出。

典型字段：

- taskId；
- languageProfileId；
- taskType；
- estimatedDuration；
- difficulty；
- primaryGoal；
- secondaryGoals；
- targetWeaknesses；
- targetVocabulary；
- communicationGoals；
- topic；
- scenario；
- sourceType；
- contentId；
- reviewTargets；
- assistLevel；
- reason；
- status。

LearningTask 表示：

> 本次应该练什么以及为什么。

它不是：

> 本次练习结果。

---

## 4.3 PracticeSession / 练习会话

一次实际学习活动的主要运行记录。

典型字段：

- sessionId；
- taskId；
- languageProfileId；
- practiceType；
- startTime；
- endTime；
- duration；
- userInputs；
- assistantOutputs；
- helpRequests；
- vocabularyActions；
- contentInteractions；
- userFeedback；
- status。

PracticeSession 状态：

    CREATED
        ↓
    IN_PROGRESS
        ↓
    COMPLETED

其他可能状态：

    PAUSED
    ABANDONED

PracticeSession 保存：

> 这次实际发生了什么。

Evaluation 使用独立 lifecycle：

    PENDING
        ↓
    RUNNING
        ↓
    SUCCEEDED / FAILED

Evaluation failure 不改变已完成 PracticeSession 的事实。

---

## 4.4 EvaluationResult / 单次评估结果

一次 EvaluationResult 由两个具有不同 provenance 的部分组成。

### DeterministicAssessment

由 trusted Practice event 和 Java rule 产生：

- taskCompletion；
- duration；
- attempts；
- assistance usage；
- exact / rule-verifiable result。

### SemanticEvaluationCandidate

由可选 LLM 产生并经过 validation：

典型内容：

- strengths；
- detectedIssues；
- vocabularyResults；
- communicationResults；
- independenceLevel；
- overallPerformance；
- confidence；
- evaluationStatus。

EvaluationResult 是：

**Session-level Judgment。**

它不是：

**Long-term Learner State。**

Model failure 只令 `SemanticEvaluationCandidate` unavailable / failed，不抹掉独立成立的
`DeterministicAssessment`。

---

## 4.5 Evidence / 学习证据

Evidence 是 Practice 与 Long-term State 之间的关键层。

典型 Evidence：

- ErrorEvent；
- Correct Evidence；
- Vocabulary Evidence；
- Communication Evidence；
- PracticeResult；
- UserFeedbackEvent；
- Success Evidence。

Evidence 需要保留适当：

- languageProfileId；
- source session；
- practice type；
- scenario；
- difficulty；
- independence；
- confidence；
- timestamp；
- provenance。

---

## 4.6 Long-term State / 长期状态

主要包括：

- WeaknessState；
- SkillState；
- VocabularyMastery；
- CommunicationSkill；
- Level。

长期状态只能通过：

    Qualified Evidence
        ↓
    Java Aggregation
        ↓
    State Transition

产生或变化。

---

# 5. Main Learning Data Flow / 核心学习数据流

完整闭环：

    User
      ↓
    Select Language
      ↓
    languageProfileId
      ↓
    Load LanguageProfile
      ↓
    Java Eligible Task Candidates
      ↓
    Optional LLM Plan Enrichment
      ↓
    Java Plan Validation
      ↓
    LearningTask
      ↓
    Practice
      ↓
    PracticeSession
      ↓
    Deterministic Assessment
      +
    Optional Semantic Evaluation
      ↓
    Qualification by Provenance
      ↓
    Qualified Evidence
      ↓
    Learning Memory
      ↓
    Weakness / Skill /
    Vocabulary State
      ↓
    LanguageProfile Summary
      ↓
    Next Planner

这是系统最重要的数据链。

---

# 6. Planner Read Flow / Planner 读取数据流

Planner 主要是：

**Read-heavy + Plan-generation。**

数据流：

    languageProfileId
          ↓
    Load Structured State
          ├─ LanguageProfile
          ├─ Active Weakness
          ├─ Skill State
          ├─ Due Review
          ├─ Vocabulary Summary
          ├─ Recent Practice
          ├─ Preferences
          └─ Available Time
          ↓
    Java Eligible Candidate Generation
          ↓
    Java Hard Constraint Filtering
          ↓
    Optional Planner Model Enrichment
          ↓
    Java Final Validation / Fallback
          ↓
    LearningTask

Planner 可以产生：

`LearningTask`

Planner 不可以直接产生：

- ACTIVE Weakness；
- Level Change；
- Vocabulary Mastery Change；
- Learning Memory mutation。

---

## 6.1 Planner Hard vs Soft Data

Soft decision：

    LLM

例如：

- 今天更适合 Conversation 还是 Reading；
- 哪个训练目标更值得优先；
- 如何解释推荐原因。

Soft decision 必须限制在 Java 已确认合法的 candidate set 内。

Hard constraint：

    Java

例如：

- languageProfileId 是否属于当前用户；
- Practice 是否被用户 DISABLED；
- duration 是否超过可用时间；
- enum 是否合法；
- task type 是否受支持。

Model unavailable、timeout 或最终 output invalid 时，Java 使用 deterministic fallback priority
生成合法 LearningTask。

---

# 7. PracticeSession Flow / 单次练习生命周期

一次 Practice：

    LearningTask
        ↓
    Create PracticeSession
        ↓
    CREATED
        ↓
    User Starts
        ↓
    IN_PROGRESS
        ↓
    Collect Interaction
        ├─ user input
        ├─ AI response
        ├─ help request
        ├─ vocabulary action
        ├─ content interaction
        └─ assistance usage
        ↓
    COMPLETED

EvaluationRun 独立执行：

    PENDING
        ↓
    RUNNING
        ↓
    SUCCEEDED / FAILED

如果用户退出：

    IN_PROGRESS
        ↓
    PAUSED

或者：

    IN_PROGRESS
        ↓
    ABANDONED

未完成 Session 是否进入 Evaluator：

根据 Practice Type 和产品规则决定。

不得默认把所有中断视为学习失败。

---

# 8. Evaluator Flow / Evaluator 数据流

Evaluator 输入：

    PracticeSession
    +
    LearningTask
    +
    Compact LanguageProfile
    +
    Task-specific Rubric

先从 trusted Practice event 计算：

    DeterministicAssessment

需要语义判断时再运行：

    Context Manager
        ↓
    Evaluator Model Call
        ↓
    Structured Evaluation
        ↓
    Schema Validation
        ↓
    Enum Validation
        ↓
    Semantic Validation
        ↓
    Qualification

输出：

    SemanticEvaluationCandidate

两类结果分别由 Java 判断：

    DeterministicAssessment /
    SemanticEvaluationCandidate
        ↓
    Qualified Evidence

Evaluator 不直接访问整个长期 Memory。

这样可以降低：

- historical bias；
- token cost；
- false confirmation of existing weakness。

---

## 8.1 Evaluation Failure / Evaluation 失败

如果出现：

- Provider Error；
- Timeout；
- Invalid Structured Output；
- Schema Failure；
- unrecoverable Evaluation failure；

数据流必须停在：

    PracticeSession
        ↓
    SemanticEvaluationStatus = FAILED

结果：

    Practice remains
    +
    DeterministicAssessment remains
    +
    No model-derived Evidence mutation

禁止：

    Semantic Evaluation Failure
        ↓
    partial invalid data
        ↓
    Learning Memory

---

# 9. Evidence Qualification Flow / Evidence 资格判断

LLM Output 不能自动成为 Evidence。

Model-derived Evidence 流程：

    Evaluation Candidate
        ↓
    Schema Valid?
        ↓
    Enum Valid?
        ↓
    Confidence acceptable?
        ↓
    Category valid?
        ↓
    Evidence linked to source?
        ↓
    Java Qualification
        ↓
    Qualified Evidence

Trusted Practice event 可以通过独立 deterministic qualification 形成 Evidence，不经过 LLM
confidence 或 semantic schema。两条路径都必须保留 source、languageProfileId 与 provenance。

低置信信息可以保留为：

- CandidateIssue；
- Session-level Feedback；

但不必进入长期聚合。

---

# 10. Learning Memory Flow / 学习记忆聚合流

Learning Memory 使用三层结构：

    A. Raw Evidence
            ↓
    B. Aggregated Memory
            ↓
    C. Long-term State

---

## 10.1 Raw Evidence

包括：

- ErrorEvent；
- Correct Evidence；
- PracticeResult；
- Communication Evidence；
- Vocabulary Evidence；
- UserFeedbackEvent。

必须同时保存：

    Failure Evidence
    +
    Success Evidence

不能建立只会积累错误的 Memory。

---

## 10.2 Aggregated Memory

Java 聚合时考虑：

- frequency；
- recency；
- cross-scenario evidence；
- difficulty；
- evaluator confidence；
- independence；
- correct evidence；
- practice type；
- trend。

聚合规则应可版本化。

长期 Trace 应能回答：

> 为什么这个状态发生了变化？

---

## 10.3 Long-term State Mutation

聚合结果才允许推动：

    WeaknessState
    SkillState
    VocabularyMastery
    CommunicationSkill

这些状态再形成：

    LanguageProfile Summary

供下一次 Planner 使用。

---

# 11. Weakness Data Flow / Weakness 数据流

错误第一次出现：

    PracticeSession
        ↓
    Evaluator
        ↓
    Error Candidate
        ↓
    Qualified Error Evidence

此时：

**不直接形成 ACTIVE Weakness。**

之后：

    Error Evidence #1
    Error Evidence #2
    Correct Evidence
    Cross-scenario Evidence
    Independence Evidence
            ↓
    Learning Memory Aggregation
            ↓
    Weakness Qualification

可能形成：

    CANDIDATE
        ↓
    ACTIVE
        ↓
    IMPROVING
        ↓
    INACTIVE

未来再次出现：

    INACTIVE
        ↓
    REACTIVATED

具体阈值不应简单固定为：

`错误次数 >= 3`

需要结合多维 Evidence。

---

## 11.1 Weakness Improvement Flow / 弱点改善

专项 Grammar Repair 成功：

    Repair Success Evidence

真实场景成功：

    Transfer Success Evidence

其中：

    Transfer Success
    > Repair Success

在长期判断中通常更有价值。

因此：

    Micro Exercise Correct
        ≠
    Weakness Resolved

---

# 12. Skill State Flow / Skill 能力状态流

Skill Evidence 可来自：

- Reading；
- Conversation；
- Listening；
- Writing；
- Review；
- other Practice。

数据流：

    Multiple Practice Evidence
            ↓
    Skill Aggregation
            ↓
    mastery
    confidence
    evidenceSufficiency
    trend
    recentSuccessRate
    independentSuccessRate
            ↓
    SkillState

Level 进一步基于多个 SkillState 推导。

不得：

    One Evaluation
        ↓
    Overall Level Upgrade

---

# 13. Vocabulary Data Flow / 词汇数据流

词汇进入 Personal Vocabulary Bank 的来源包括：

    Reading
    Conversation
    Manual Lookup
    Manual Add
    Evaluator
    Future Listening / Writing

形成：

    LexicalItem

类型包括：

- WORD；
- PHRASE；
- COLLOCATION；
- EXPRESSION。

---

## 13.1 Vocabulary Evidence

可能包括：

    Encounter
    Recognition
    Recall
    Usage
    Assisted Usage
    Independent Usage
    Error

这些 Evidence：

    Vocabulary Evidence
        ↓
    Learning Memory / Vocabulary Aggregation
        ↓
    Vocabulary Mastery

最终状态可能为：

    NEW
    LEARNING
    WEAK
    MASTERED
    IGNORED

禁止：

    encounterCount >= N
        ↓
    MASTERED

---

# 14. Review Data Flow / 复习数据流

Review 回答：

> 哪些对象值得再次出现？

数据源：

    Vocabulary State
    Weakness State
    Communication Skill
    Practice History
    Recent Success / Failure

Review System 输出：

    Review Candidate

然后：

    Review Candidate
        ↓
    Planner
        ↓
    Decide whether to schedule
        ↓
    Practice
        ↓
    Evaluator
        ↓
    New Evidence

默认：

`AUTO_INTERLEAVED`

即优先让旧知识重新进入真实 Practice。

---

# 15. Conversation Data Flow / 对话数据流

Conversation：

    LearningTask
        ↓
    Scenario
    Communication Goals
    Target Vocabulary
    Target Weakness
        ↓
    Context Manager
        ↓
    Recent Turns
    Session Summary
    Minimal Learner State
        ↓
    Conversation Model
        ↓
    Assistant Response
        ↓
    PracticeSession Append

用户可能产生：

- message；
- follow-up；
- clarification；
- topic shift；
- elaboration；
- help request；
- assistance request。

这些首先是：

`Session Data`

结束后：

    PracticeSession
        ↓
    Evaluator
        ↓
    Communication Evidence
        ↓
    Learning Memory
        ↓
    Communication Skill

Conversation Agent 本身不直接更新长期 Communication Skill。

---

# 16. Reading Data Flow / 阅读数据流

Reading：

    LearningMaterial
        ↓
    Reading PracticeSession

期间收集：

- reading interaction；
- vocabulary lookup；
- sentence explain；
- Translation Practice；
- comprehension；
- assistance；
- subjective difficulty feedback。

结束：

    Reading Session
        ↓
    Evaluator
        ↓
    Comprehension Evidence
    Vocabulary Evidence
    Error Evidence
        ↓
    Learning Memory

材料过难时，应优先产生：

`CONTENT_DIFFICULTY_MISMATCH`

而不是把所有错误全部写入用户 Weakness。

---

# 17. Content Import Data Flow / 内容导入数据流

V1 用户导入重点：

- TXT；
- Markdown；
- PDF。

数据流：

    User Upload
        ↓
    Upload Validation
        ↓
    Object Storage
        ↓
    Metadata Record
        ↓
    Dedup
        ↓
    Parse
        ↓
    Normalize
        ↓
    Language Detection
        ↓
    Structure Detection
        ↓
    READY_BASIC
        ↓
    User may start learning
        ↓
    Chunk
        ↓
    Metadata / Difficulty
        ↓
    Embedding
        ↓
    pgvector Index
        ↓
    READY_FULL

核心设计：

`READY_BASIC` 与 `READY_FULL` 分离。

Embedding 尚未完成时：

只要解析成功且业务允许，

用户仍然可以开始基础 Reading。

---

## 17.1 Imported Content State

    UPLOADING
        ↓
    PARSING
        ↓
    READY_BASIC
        ↓
    INDEXING
        ↓
    READY_FULL

异常：

    FAILED

可能失败类型包括：

- PARSE_FAILED；
- LANGUAGE_UNSUPPORTED；
- CONTENT_CORRUPTED；
- INDEX_FAILED；
- ADAPTATION_FAILED；
- AUDIO_GENERATION_FAILED。

---

# 18. RAG Data Flow / RAG 检索数据流

RAG 分为三个逻辑 Scope：

    Personal Memory RAG
    Content RAG
    User Import RAG

V1 基础检索：

    Query Intent
        ↓
    UserContext
        ↓
    languageProfileId
        ↓
    Scope / Metadata Filter
        ↓
    pgvector Retrieval
        ↓
    Top-K
        ↓
    Context Manager
        ↓
    Selected Context

精确学习状态：

    Profile
    Level
    Active Weakness
    Due Vocabulary
    Review State

不通过向量搜索确定。

---

## 18.1 Personal Memory Retrieval

Personal Memory 的 Retrieval Unit 应是有意义的 Episode，例如：

- SESSION_SUMMARY；
- ERROR_EPISODE；
- SUCCESS_EPISODE；
- EXPRESSION_EPISODE。

避免直接：

    Entire Conversation
        ↓
    One Embedding

RAG Result 只表示：

> 与当前任务语义相关的历史 Context。

不表示：

> 这是当前长期状态事实。

---

## 18.2 Retrieval Failure

RAG Failure：

    Retrieval Error
        ↓
    Trace Failure
        ↓
    Graceful Degradation

如果当前业务允许：

继续使用：

    Structured State
    +
    Available Context

不应因为 Personal Memory RAG 临时不可用：

直接阻断整个学习流程。

---

## 18.3 M3 Controlled Multi-role Agent Content Preparation

M3 Content preparation 数据流：

    Imported / Curated Content
        ↓
    Retrieval Query + UserContext + languageProfileId
        ↓
    Permission-filtered Source Chunks
        + provenance / retrieval metadata
        ↓
    Lesson Design Candidate
        + referenced source IDs / spans
        ↓
    Quality Review Result
        ↓
    ACCEPT / REVISE_ONCE / REJECT
        ↓
    Java Validation + Idempotent Publish
        ↓
    Learning Material

Java 保存独立 workflow / job state、attempt、model / prompt / context version、selected source IDs、
tool calls、terminal status 与 sanitized Trace metadata。

禁止：

    Model-generated unsupported claim
        ↓
    published material

也禁止 Agent 因内容生成结果直接修改 Learning Memory。后续用户 Practice 才能通过统一
Evaluation / Evidence 流进入长期状态。

---

# 19. Context Assembly Flow / 上下文组装流

Context Manager 数据来源：

    Static Context
        +
    Learner State
        +
    Session Context
        +
    Retrieved Context

然后：

    Context Priority
        ↓
    CRITICAL
    HIGH
    MEDIUM
    LOW

如果超出 Token Budget：

    Remove low priority
        ↓
    Dedupe
        ↓
    Reduce Top-K
        ↓
    Summarize old turns
        ↓
    Optional Compression

最终产生：

    Model-specific Context

---

## 19.1 Agent-specific Context

Planner：

    Compact Profile
    Active Weakness
    Review
    Preferences
    Recent Practice

Conversation：

    LearningTask
    Scenario
    Recent Turns
    Session Summary
    Limited Learning Targets

Evaluator：

    PracticeSession
    LearningTask
    Rubric
    Minimal Profile

禁止：

    All Memory
        ↓
    Every Agent Prompt

---

# 20. Tool Calling Data Flow / Tool Calling 数据流

正常流程：

    Agent
        ↓
    Tool Request
        ↓
    Tool Schema
        ↓
    Tool Gateway
        ↓
    Authenticated UserContext
        ↓
    Permission
        ↓
    Validation
        ↓
    Execute
        ↓
    Tool Result
        ↓
    Trace
        ↓
    Agent

Tool Gateway 负责适用的：

- schema validation；
- auth；
- permission；
- rate limit；
- idempotency；
- retry；
- timeout；
- trace。

---

## 20.1 Identity Flow

身份来自：

    AuthenticatedContext

不是来自：

    LLM-provided userId

因此：

    Tool Args:
    userId = xxx

不能被视为可信身份。

真实身份必须由 Backend Context 注入。

---

## 20.2 Mutation Tool Flow

普通 Agent：

    Agent
        ↓
    submitEvidence
        ↓
    Java Validation
        ↓
    Learning Memory
        ↓
    State Mutation

禁止暴露普通 Tool：

- setWeakness；
- setLevel；
- arbitrary updateVocabularyMastery；
- arbitrary database mutation。

---

# 21. Model / BYOK Data Flow / 模型与 BYOK 数据流

Hosted Mode 默认：

    Browser
        ↓
    API Key local/session storage
        ↓
    HTTPS Request
        ↓
    Spring Boot
        ↓
    transient credential access
        ↓
    Model Gateway
        ↓
    Provider Adapter
        ↓
    External Provider

API Key 不得进入：

- PostgreSQL；
- Redis；
- Trace；
- Log；
- Exception Dump。

---

## 21.1 Model Request Flow

业务模型调用：

    Planner / Evaluator /
    Conversation / Other AI Task
        ↓
    Context Manager
        ↓
    Typed Model Operation Port
        ↓
    Purpose + Operation Route
        ↓
    Capability Check / Timeout
        ↓
    Provider Adapter
        ↓
    External Model
        ↓
    Model Response
        ↓
    Usage / Trace
        ↓
    Structured Validation
        ↓
    Calling Domain

业务模块不直接依赖具体 Provider。

每次调用只执行一个明确 Model Operation。Text → Speech → optional Image 等多阶段流程由具体
Application Workflow 编排，并独立记录每一步 success / failure / skipped；Gateway 不决定 required /
optional、partial success 或 Learning State mutation。S6 默认不自动 retry 或静默切换 Provider。

---

# 22. Storage Ownership / 存储责任

## PostgreSQL

长期 structured state：

- User；
- LanguageProfile；
- SkillState；
- WeaknessState；
- Vocabulary State；
- Evidence；
- Practice metadata；
- Review state；
- Content metadata；
- Background Job state；
- Trace / Eval metadata。

---

## pgvector

语义 Retrieval Index：

- Personal Memory；
- Content Library；
- User Import。

pgvector 不是：

长期学习状态 authority。

---

## Redis

主要：

- Cache；
- Rate Limit；
- Temporary State；
- Optional Lock。

不能：

保存唯一版本的核心学习状态。

---

## Object Storage

主要：

- imported original files；
- audio；
- large objects；
- generated assets。

---

## IndexedDB

主要：

- client cache；
- offline data；
- recent/detail session；
- pending offline interactions；
- browser-local configuration。

Hosted 模式不能仅依赖 IndexedDB 恢复核心 Learner State。

---

# 23. Multi-language Data Isolation / 多语言数据隔离

核心模型：

    User
      ├─ LanguageProfile EN
      ├─ LanguageProfile JA
      └─ LanguageProfile ES

学习状态全链必须携带：

    languageProfileId

包括：

- Practice；
- Evidence；
- Weakness；
- Skill；
- Vocabulary；
- Review；
- Memory；
- RAG Metadata；
- Planner Context。

查询链必须类似：

    Authenticated User
        ↓
    Verify LanguageProfile ownership
        ↓
    languageProfileId-scoped Query

禁止：

    userId
        ↓
    Load all learning state
        ↓
    let LLM distinguish language

语言隔离必须在 Backend / DB / Retrieval 层完成。

---

# 24. Trace Data Flow / Trace 数据流

Trace 是旁路 Observation Data。

业务链：

    Request
        ↓
    Application
        ↓
    Agent
        ↓
    Context
        ↓
    RAG
        ↓
    Tool
        ↓
    Model
        ↓
    Evaluator
        ↓
    State Mutation

旁路记录：

    traceId
    spanId
    parentSpanId
    provider
    model
    taskType
    promptVersion
    rubricVersion
    contextStrategyVersion
    token usage
    latency
    status
    retry
    selected context IDs

Trace：

    observes system

Trace 不：

    mutate learner state

---

# 25. State Mutation Traceability / 长期状态变化追踪

重要长期状态变化，例如：

    Weakness
    CANDIDATE
        ↓
    ACTIVE

需要能够追溯：

- 哪些 Evidence；
- aggregationVersion；
- timestamp；
- triggering flow；
- related PracticeSession。

这样系统才能回答：

> 为什么认为用户存在这个 Weakness？

---

# 26. Eval Data Flow / AI Eval 数据流

Eval 与 Production Learning State 分离。

基本流程：

    Dataset
        ↓
    Prompt / Model /
    Rubric / Context Version
        ↓
    Eval Runner
        ↓
    AI Runtime
        ↓
    Eval Result
        ↓
    Quality / Schema /
    Cost / Latency Metrics

Eval Result：

用于判断 AI Runtime 是否可靠。

默认不直接修改：

- User Level；
- Weakness；
- Vocabulary Mastery。

Required V1 metrics 按 capability 选择：

- groundedness / unsupported claim rate；
- issue false positive / false negative；
- retrieval relevance / permission isolation；
- task completion / constraint satisfaction；
- token、latency 与 cost；
- retry / fallback / recovery result；
- deterministic replay equality。

---

# 27. Background Job Data Flow / 后台任务流

长任务：

    HTTP Request
        ↓
    Create Job
        ↓
    DB Job State
        ↓
    TaskExecutor
        ↓
    Process
        ↓
    Update Job State

适用：

- PDF Parsing；
- Embedding；
- Indexing；
- Audio generation；
- later Memory aggregation；
- Trace cleanup。

V1：

    TaskExecutor
    +
    DB Job State

当前不因这些任务提前引入 Kafka。

---

# 28. Failure Safety / 失败安全规则

以下失败不得污染长期 Learner State。

### Model Failure

    Provider Failure
        ↓
    no valid output
        ↓
    no model-derived Evidence mutation

Planner 使用合法 deterministic fallback；Practice、deterministic assessment、已存在 Qualified
Evidence、state replay 与 recovery 不受影响。

### Structured Output Failure

    Parse / Schema Failure
        ↓
    Repair / Retry if allowed
        ↓
    final failure
        ↓
    stop mutation

### Evaluator Failure

    Practice preserved
        ↓
    Semantic Evaluation FAILED
        ↓
    deterministic assessment preserved
        ↓
    no model-derived long-term update

### RAG Failure

    Retrieval unavailable
        ↓
    graceful degradation where possible

### Tool Failure

    bounded retry
        ↓
    explicit Result Status
        ↓
    no infinite agent loop

### Background Job Failure

    Job FAILED
        ↓
    preserve recoverable metadata
        ↓
    retry / user-visible failure according to job policy

---

# 29. Mutation Authority Matrix / 状态修改权限矩阵

| Component | Read State | Create Session Data | Create Candidate | Create Evidence | Change Long-term State |
|---|---:|---:|---:|---:|---:|
| Vue / PWA | Yes | Via Backend | No | No | No |
| Planner | Yes | No | LearningTask | No | No |
| Practice Runtime | Limited | Yes | No | Raw interaction only | No |
| Conversation Agent | Limited | Yes | AI response | No direct | No |
| Deterministic Assessment | Limited | No | Rule-derived result | Yes, after deterministic qualification | No |
| Semantic Evaluator | Limited | No | Yes | Candidate only | No |
| Java Validation | Yes | No | No | Yes | No |
| Learning Memory | Yes | No | No | Consume | Yes, via rules |
| RAG | Retrieval only | No | Context | No | No |
| Context Manager | Read/select | No | Context | No | No |
| Tool Gateway | Controlled | Controlled | No | Controlled | Only via approved service |
| Model Gateway | No domain authority | No | Model response | No | No |
| Trace | Observe | No | No | No | No |
| Eval System | Test/read | No | EvalResult | No production evidence by default | No |

核心原则：

> 能生成自然语言判断，不代表拥有状态写权限。

---

# 30. Forbidden Data Paths / 禁止数据路径

以下数据流属于 Architecture Drift。

## 30.1 Evaluator → Weakness

禁止：

    Evaluator
        ↓
    WeaknessRepository
        ↓
    ACTIVE

正确：

    Evaluator
        ↓
    Candidate
        ↓
    Validation
        ↓
    Evidence
        ↓
    Learning Memory
        ↓
    WeaknessState

---

## 30.2 Planner → Memory Mutation

禁止：

    Planner
        ↓
    updateMemory()

Planner 应主要读取长期状态并生成 LearningTask。

---

## 30.3 RAG → State Mutation

禁止：

    RAG Result
        ↓
    Weakness = ACTIVE

正确：

    RAG Result
        ↓
    Context

---

## 30.4 LLM → Level

禁止：

    LLM:
    "User is now B2"
        ↓
    Level = B2

正确：

    Multiple Skill Evidence
        ↓
    Java Aggregation
        ↓
    Level Qualification

---

## 30.5 Frontend → Final Learning State

禁止：

    Vue
        ↓
    calculate Weakness
        ↓
    persist final state

Frontend 负责 interaction。

Backend Domain 负责长期状态。

---

## 30.6 Cross-language Query

禁止：

    getWeaknessByUser(userId)

在 Planner 等学习链直接使用而没有语言 Scope。

需要：

    userId
    +
    languageProfileId

以及后端 ownership validation。

---

# 31. Pending Data Flows / 尚未最终确认的流

当前 V1 Scope 中以下模块仍处于 PENDING。

因此本文只记录其数据边界，不把它们视为已经批准的 V1 Implementation。

---

## 31.1 Progress

概念数据流：

    Long-term State
        ↓
    Read-only Projection
        ↓
    Progress UI

Progress 不应该重新计算另一套学习状态。

---

## 31.2 Continuous Assessment / Milestone

概念数据流：

    Evidence Window
        ↓
    Long-term Aggregation
        ↓
    Possible Level Change
        ↓
    Optional Verification / Milestone

详细 V1 深度待 Final Scope 决定。

---

## 31.3 Practice Feedback

概念数据流：

    User Feedback
        ↓
    UserFeedbackEvent
        ├─ Planner short-term signal
        └─ Learning Memory auxiliary evidence

一次 Feedback 不直接改变 Level。

---

## 31.4 Notifications

概念数据流：

    Learning State
    Preferences
        ↓
    Notification Candidate
        ↓
    Policy / Rate Limit
        ↓
    Notification

是否进入当前 V1 仍待正式 Scope Decision。

---

# 32. Source-of-Truth Summary / 数据真相来源摘要

    User Identity
    → Authenticated UserContext

    Current Long-term Learning State
    → PostgreSQL / Domain Services

    Semantic Historical Context
    → RAG / pgvector

    Temporary Cache
    → Redis

    Imported Binary Content
    → Object Storage

    Local Offline / Cache
    → IndexedDB

    AI Judgment
    → Structured Model Output

    Qualified Learning Evidence
    → Java Validation

    Long-term State Transition
    → Java Learning Memory Rules

    AI Runtime History
    → Trace

    AI Quality Measurement
    → Eval

如果某个实现引入新的 Source of Truth：

需要 Architecture Review。

---

# 33. Implementation Mapping / 实现映射

当前本文不记录虚构的：

- Controller；
- Service；
- Repository；
- Entity；
- Database Table；
- Transaction Boundary。

真实开发后，可以逐步补充：

| Logical Flow | Entry | Application Service | Domain Service | Repository | Storage | Main Test |
|---|---|---|---|---|---|---|
| Planner Flow | TBD | TBD | TBD | TBD | TBD | TBD |
| Practice Flow | TBD | TBD | TBD | TBD | TBD | TBD |
| Evaluator Flow | TBD | TBD | TBD | TBD | TBD | TBD |
| Memory Flow | TBD | TBD | TBD | TBD | TBD | TBD |
| Content Flow | TBD | TBD | TBD | TBD | TBD | TBD |
| RAG Flow | TBD | TBD | TBD | TBD | TBD | TBD |

只有真实实现存在后才能填写。

---

# 34. Maintenance Rule / 维护规则

以下情况应检查 DATA_FLOW.md：

- 新核心数据对象；
- 新长期状态；
- State Mutation Authority 改变；
- 新 Tool 写能力；
- Evidence 模型改变；
- Learning Memory Aggregation 改变；
- RAG Scope 改变；
- 新 Storage Source of Truth；
- Hosted / Self-hosted 数据责任改变；
- Offline Sync 模型改变；
- Authentication / Permission Flow 改变。

普通字段新增：

如果不影响数据权责和核心 Flow，

无需更新本文。

---

# 35. Final Data Flow Summary / 最终数据流摘要

整个项目最核心的数据链可以压缩为：

    User Interaction
        ↓
    PracticeSession
        ↓
    Evaluator
        ↓
    Candidate Judgment
        ↓
    Java Validation
        ↓
    Evidence
        ↓
    Learning Memory
        ↓
    Weakness / Skill /
    Vocabulary State
        ↓
    LanguageProfile
        ↓
    Planner
        ↓
    Next Practice

AI Runtime 辅助链：

    Domain
        ↓
    Context Manager
        ├─ Structured State
        ├─ Session Context
        └─ RAG Context
        ↓
    Model Gateway
        ↓
    Provider

受控工具链：

    Agent
        ↓
    Tool Gateway
        ↓
    Auth / Permission /
    Validation / Execution
        ↓
    Application / Domain

最终必须保持：

**Practice creates behavior.**

**Evaluator creates diagnosis.**

**Evidence records observations.**

**Learning Memory creates long-term learning state.**

**Planner consumes that state to change future practice.**
