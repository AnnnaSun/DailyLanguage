# AI Language Tutor — System Overview

> 本文是 AI Language Tutor 的系统级 Architecture Map（架构地图）。
>
> 目标：
>
> - 让开发者在 2–3 分钟内恢复系统整体认知；
> - 让 Coding Agent 在低 Context 成本下定位主要系统边界；
> - 描述系统主要组件、数据责任和运行链路；
> - 区分 Architecture Baseline、V1 Scope 与实际 Implementation Status。
>
> 详细产品规则见 PRD / Core User Flow。
>
> 强制架构边界见 repository `AGENTS.md`。
>
> 具体模块路径见 `MODULE_MAP.md`。
>
> 详细数据链路见 `DATA_FLOW.md`。
>
> Agent / LLM / Tool 运行链路见 `AGENT_FLOW.md`。

---

## 1. System Identity / 系统定位

AI Language Tutor 是一个：

**provider-agnostic、stateful AI Language Tutor Agent。**

它面向非应试型语言学习者，通过长期学习状态持续决定：

1. 用户当前掌握了什么；
2. 用户持续卡在哪里；
3. 当前最值得练什么；
4. 本次练习表现如何；
5. 这些表现是否足以改变长期学习状态；
6. 下一次训练应该如何调整。

产品核心强调：

**Communication + Reading Comprehension + Natural Expression + Persistent Adaptation**

而非固定课程、单纯题库或一次性聊天。

---

## 2. Architecture Status Convention / 架构状态约定

本文区分以下状态：

| Status | 中文 | 含义 |
|---|---|---|
| `DESIGN_BASELINE` | 设计基线 | 已进入当前整体架构设计 |
| `V1_CONFIRMED` | V1 已确认 | 已进入 V1 Scope，但不代表已经编码完成 |
| `V1_PENDING` | V1 待定 | V1 Scope 尚未最终裁剪 |
| `INTERFACE_ONLY` | 只保留边界 | V1 只预留接口或扩展点 |
| `BACKLOG` | 后续功能 | 当前 V1 不要求完整实现 |
| `IMPLEMENTED` | 已实现 | 必须通过实际 source code / test 验证后才能标记 |

### Current Document Rule

当前 `SYSTEM_OVERVIEW.md` 主要描述：

**Approved Architecture + V1 Target Architecture**

除非已经检查实际 repository，否则不得把模块标记为 `IMPLEMENTED`。

代码实现状态后续应根据：

- source code；
- tests；
- migration；
- runtime verification；

持续更新。

---

## 3. High-level Architecture / 总体架构

整体结构：

    ┌─────────────────────────────────────────┐
    │              Vue / PWA Client           │
    │                                         │
    │ UI / Practice UX / Local Cache          │
    │ IndexedDB / Offline / Local Credential  │
    └───────────────────┬─────────────────────┘
                        │
                      HTTPS
                        │
                        ▼
    ┌─────────────────────────────────────────┐
    │          Spring Boot Application        │
    │                                         │
    │  Controlled Learning Workflow           │
    │  ├─ Task / Practice / Evaluation Run    │
    │  ├─ Transition / Recovery / Fallback    │
    │  └─ Java State Mutation Authority       │
    │                                         │
    │  Learning Domain                        │
    │  ├─ Language Profile                    │
    │  ├─ Planner                             │
    │  ├─ Practice Runtime                    │
    │  ├─ Evaluator                           │
    │  ├─ Learning Memory                     │
    │  ├─ Weakness / Skill / Vocabulary       │
    │  └─ Review / Progress                   │
    │                                         │
    │  Bounded AI Capabilities                │
    │  ├─ Role-specific Context Assembly      │
    │  ├─ Model Gateway                       │
    │  ├─ Structured Output Validation        │
    │  ├─ RAG / Retrieval (M3)                │
    │  ├─ Tool Gateway (M3)                   │
    │  └─ Voice Gateway (M5)                  │
    │                                         │
    │  Platform                               │
    │  ├─ Content Pipeline                    │
    │  ├─ Background Job                      │
    │  ├─ Security / UserContext              │
    │  ├─ Trace / OpenTelemetry               │
    │  └─ Eval / Prompt Version               │
    └──────┬────────────┬─────────────┬───────┘
           │            │             │
           ▼            ▼             ▼
    PostgreSQL       Redis       Object Storage
    + pgvector

                        │
                        ▼
    ┌─────────────────────────────────────────┐
    │       User-configured AI Providers      │
    │                                         │
    │ LLM / Embedding / STT / TTS Providers  │
    └─────────────────────────────────────────┘

核心部署方向：

**Vue/PWA → Spring Boot → PostgreSQL + pgvector / Redis / Object Storage → External AI Providers**

初期不引入独立 Vector Database。

---

## 4. Core Learning Loop / 核心学习闭环

系统最核心的 Domain Loop：

    Language Profile
          ↓
       Planner
          ↓
    LearningTask
          ↓
       Practice
          ↓
      Evaluator
          ↓
     Raw Evidence
          ↓
    Learning Memory
          ↓
    Aggregated State
          ↓
     Profile Update
          ↓
      Re-planning

这个闭环同时承担两个目标。

### Product Loop

用户获得越来越贴合自身状态的训练。

### Engineering Loop

系统展示真实的：

- Persistent State；
- Deterministic Workflow + Bounded AI Decision；
- Structured Output；
- Evidence Aggregation；
- Model failure isolation；
- Grounding / hallucination control；
- Controlled Agent handoff / Tool Calling；
- Context Engineering；
- Token / quality / cost measurement；
- Eval；
- Observability。

RAG 与 Tool Calling 已在 M3 Content / Reading flow 获得 Scope；Voice 在 M5 进入。它们不是所有
Learning Flow 的默认前置条件，但必须在对应 Phase 形成可运行、可评测的 Engineering Evidence。

---

## 5. Persistent Learner Model / 持续学习者模型

长期学习状态的核心结构：

    PracticeSession
          ↓
      Raw Evidence
          ↓
    Aggregated Memory
          ↓
    Long-term State
          ↓
    Language Profile

### Raw Evidence

代表一次具体学习行为产生的事实，例如：

- ErrorEvent；
- Success Evidence；
- PracticeResult；
- Communication Evidence；
- Vocabulary Evidence；
- User Feedback。

### Aggregated Memory

对多次 Evidence 进行聚合，例如：

- frequency；
- recent frequency；
- trend；
- confidence；
- cross-scenario evidence；
- independent success；
- success / failure balance。

### Long-term State

形成可供 Planner 使用的长期状态，例如：

- WeaknessState；
- SkillState；
- VocabularyMastery；
- CommunicationSkill；
- Level。

核心原则：

**LLM 产生 Evidence / Candidate。**

**Java 负责长期状态 Qualification、Aggregation 和 State Transition。**

---

## 6. Main Domain Components / 核心业务组件

| Component | Responsibility / 职责 | Must Not Own / 不应负责 |
|---|---|---|
| `Language Profile` | 当前长期能力的 compact summary | 完整学习历史 |
| `Planner` | 根据当前状态选择训练目标、难度和约束 | 直接修改 Memory / Level |
| `Practice Runtime` | 承载 Reading、Conversation、Review 等真实学习行为 | 自行决定长期能力状态 |
| `Evaluator` | 对单次 Practice 产生结构化 Evaluation / Evidence | 直接 activate Weakness |
| `Learning Memory` | 聚合 Evidence 并形成长期学习状态 | 代替 Practice 执行教学 |
| `Review System` | 决定哪些已学内容值得再次出现 | 代替 Planner 决定完整 Today Plan |
| `Vocabulary State` | 保存 recognition / recall / usage 等词汇掌握状态 | 跨语言共享 mastery |
| `Weakness / Skill State` | 表达长期能力和弱项趋势 | 根据单次错误直接定性 |

---

## 7. Practice System / 练习系统

V1 架构中的主要 Practice 包括：

    Practice
    ├─ Conversation
    ├─ Reading
    ├─ Review
    ├─ Vocabulary
    ├─ Grammar Repair
    ├─ Listening
    └─ Language Fundamentals

不同 Practice 可以拥有自己的交互形式。

但结束后应尽可能进入统一链路：

    PracticeSession
    ↓
    Evaluator
    ↓
    Evidence
    ↓
    Learning Memory

这样 Persistent Learner Model 才能跨 Practice 理解用户长期能力。

---

## 8. Bounded AI Runtime / 受控 AI 运行层

AI 能力不直接等于 Domain Authority。

Learning Workflow 与长期状态由 Java Application / Domain 控制。AI Runtime 只为需要语义能力的
bounded role 提供 Context、Model、validation 和适用的 Tool / Retrieval capability。

Model unavailable 可以降低 plan richness、semantic evaluation 或 dynamic Conversation 能力，
但不得破坏 persistence integrity、language isolation、deterministic Evidence processing、state
replay 或 session recovery。

主要 bounded AI Runtime 组件：

### Context Manager

负责：

**为当前模型调用准备必要且足够的 Context。**

典型 Context：

    Recent Context
    +
    Structured Learning State
    +
    Relevant Retrieved Memory
    +
    Task-specific Context

目标同时控制：

- relevance；
- token；
- latency；
- privacy；
- language isolation。

---

### RAG / Retrieval

V1 Scope：`P1 / M3`。M1 Planner / Evaluator 不以 RAG 为前置条件。

负责语义检索：

- Personal Memory；
- Content Library；
- User Import。

精确状态，例如：

- Level；
- Active Weakness；
- Due Vocabulary；
- Language Profile；

继续通过 structured DB / service 查询。

RAG：

**负责 Retrieval，不负责长期状态判断。**

---

### Tool Gateway

V1 Scope：`P1 / M3`。首个正式 use case 是 Controlled Multi-role Agent Workflow，不为 Planner /
Evaluator 预建空的 generic tool loop。

负责受控 Tool Execution。

基本边界：

    Agent
      ↓
    Tool Schema
      ↓
    Tool Gateway
      ↓
    Permission / Validation / Execution
      ↓
    Application Service

根据 Tool 风险提供：

- schema validation；
- authentication；
- authorization；
- timeout；
- retry；
- idempotency；
- trace。

普通 Agent 不直接获得任意 Database Mutation 权限。

---

### Model Gateway

统一模型 Provider 接入。它是 logical module boundary，不要求使用一个包含 Text、Speech、Image、
Embedding 全部方法的万能 Java interface。

业务模块：

    Planner
    Evaluator
    Conversation
    Content
    ...

            ↓

        Model Gateway

            ↓

    Provider Adapter

            ↓

    DeepSeek / OpenAI / Gemini /
    Ollama / OpenAI-compatible Provider

业务逻辑不应绑定具体 Provider SDK。

Route 同时区分：

```text
ModelPurpose + ModelOperation
```

例如 Conversation 的 Text Generation、Speech Transcription 与 Speech Synthesis 可以分别选择不同
Provider / Model。不同 Operation 使用独立 Typed Port 与 Request / Response；M0-S6 只实现第一个
Text Generation Port，不提前创建 Speech / Image / Embedding placeholder。

Gateway 每次只执行一个明确 Operation，并负责 capability check、timeout 与 failure translation。
多个 Operation 的顺序、required / optional、partial success 与 degraded behavior 由具体 Application
Workflow 控制。默认不静默 retry 或 cross-provider fallback；详细 Contract 见
[`MODEL_GATEWAY.md`](../features/MODEL_GATEWAY.md)。

---

### Structured Output

用于 Domain 的 LLM 输出必须经过：

    LLM Output
        ↓
    Parse
        ↓
    Schema Validation
        ↓
    Enum Validation
        ↓
    Semantic Validation
        ↓
    Java Qualification
        ↓
    Domain Action

Validation Failure 不得污染长期学习状态。

---

## 9. Main Data Ownership / 核心数据责任

### PostgreSQL

是 Hosted Mode 下核心 structured learning state 的主要持久化层。

主要承担：

- User / LanguageProfile；
- Skill / Weakness；
- Vocabulary；
- Practice metadata；
- Evidence；
- Review state；
- Content metadata；
- Job state；
- Trace / Eval metadata；
- relational business state。

### pgvector

与 PostgreSQL 共用基础设施。

主要承担：

- Personal Memory embeddings；
- Content embeddings；
- User Import embeddings。

初期不单独引入 Vector DB。

### Redis

用于：

- Cache；
- Rate Limit；
- Temporary State；
- 可选 Distributed Lock。

核心学习状态不能只存在 Redis。

### Object Storage

用于：

- 用户上传原始文件；
- Content assets；
- Audio assets；
- 其他大型对象。

通过统一 `ObjectStorageService` 抽象：

- S3-compatible；
- MinIO；
- Local File Storage。

### IndexedDB

客户端主要用于：

- local cache；
- offline；
- recent/detail session；
- pending offline event；
- browser-local configuration。

Hosted Mode 下 IndexedDB 不是核心长期学习状态的唯一 Source of Truth。

---

## 10. Multi-language Isolation / 多语言隔离

用户模型：

    User
    ├─ English LanguageProfile
    ├─ Japanese LanguageProfile
    └─ Spanish LanguageProfile

`languageProfileId` 是学习 workspace 的核心归属键。

以下状态必须按目标语言隔离：

- Level；
- Skill；
- Weakness；
- Vocabulary Mastery；
- Communication Skill；
- Review；
- Practice History；
- Memory；
- Evidence；
- Planner Context。

可以用户级共享：

- UI language；
- timezone；
- notification preference；
- model connections；
- global infrastructure preferences。

任何 RAG / Tool / Query / Cache 设计都必须保持这种隔离。

---

## 11. Main Learning Request Flow / 主要学习请求

一次典型 Daily Learning Flow：

    User opens Today
          ↓
    Resolve UserContext
          ↓
    Select LanguageProfile
          ↓
    Load Structured Learning State
          ↓
    Planner
          ↓
    LearningTask
          ↓
    User Start / Replace / Skip /
    Easier / Harder / Free Practice
          ↓
    Practice Runtime
          ↓
    PracticeSession
          ↓
    Evaluator
          ↓
    Evidence
          ↓
    Learning Memory
          ↓
    Weakness / Skill /
    Vocabulary State Update
          ↓
    Language Profile Update
          ↓
    Next Planner uses new state

具体字段和调用路径由后续 `DATA_FLOW.md` 描述。

---

## 12. Content & RAG Flow / 内容处理概览

V1 Content Pipeline 目标链路：

    File / Raw Content
          ↓
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
          ↓
      READY_FULL

中间允许：

    Parse / Normalize Complete
          ↓
      READY_BASIC
          ↓
    User can start learning
          ↓
    Background Indexing
          ↓
      READY_FULL

V1 重点处理：

- TXT；
- Markdown；
- PDF。

更复杂的 EPUB / URL / Audio / Video 等进入后续增强范围。

M3 在 READY_BASIC / READY_FULL Content 上运行受控 Multi-role Agent Workflow：

    Content Retrieval Role
          ↓
    Grounded Source Chunks
          ↓
    Lesson Design Role
          ↓
    Structured Lesson Candidate
          ↓
    Quality Review Role
          ↓
    Accept / One Bounded Revision / Reject
          ↓
    Java Validation / Publish

每个事实性或材料相关 claim 必须保留 source / chunk provenance。Java 控制 workflow state、
permission、maximum turns / tool calls、timeout、idempotency、validation 与 publish authority。

---

## 13. Background Processing / 后台任务

长任务不应阻塞普通 HTTP Request。

可能进入 Background Job 的任务：

- Model call late-result capture；
- large PDF parsing；
- embedding；
- content indexing；
- audio processing；
- memory aggregation；
- notification；
- trace cleanup。

V1 设计优先：

    Spring TaskExecutor
    +
    DB Job Status

V1 Model call 使用两层等待语义：

    interactive wait budget exhausted
        → return pending jobId
        → background call continues

    final model execution deadline exhausted
        → Gateway TIMEOUT
        → Job terminal execution status

这两层语义对应两个独立 execution boundary：Application `TaskExecutor` 运行 Job lifecycle，Gateway 的
dedicated model-call `ExecutorService` 包围同步 Provider Adapter call 并控制 final deadline。两者不得共用
同一个 bounded fixed pool，避免 Job worker 占满线程后等待同池 Provider task 的 starvation / deadlock。

`ModelCallJob` 在调用 Provider 前创建。PostgreSQL 保存 owner、workflow reference/version、execution /
consumption status、safe typed result 与 expiry；BYOK Credential 只存在于当前 Worker 内存，不进入 durable
Job state。用户可感知的迟到结果通过站内查询等待确认；Planner / Evaluator 等内部结果由 owning Workflow
根据 version 自动消费或标记 stale。

当前架构不因为“未来可能有大量任务”提前引入 Kafka。

Kafka 属于后续规模驱动的 Architecture Decision。

---

## 14. Observability / Trace

AI Runtime 需要能够回答：

> 某次异常究竟来自 Prompt、Context、RAG、Tool、Provider 还是 Java 后处理？

主要追踪链路：

    HTTP Request
        ↓
    Application Service
        ↓
    Agent Step
        ↓
    Context
        ↓
    RAG
        ↓
    Tool
        ↓
    LLM
        ↓
    Structured Output
        ↓
    Domain Mutation

主要 metadata：

- traceId / spanId；
- agentType；
- taskType；
- model / provider；
- promptVersion；
- rubricVersion；
- contextStrategyVersion；
- latency；
- token usage；
- RAG metadata；
- Tool status；
- retry；
- result status。

生产环境默认避免无条件持久化完整私密 Prompt / Conversation。

---

## 15. Eval System / AI 评测

Eval 与 Trace 分工：

    Trace
    → What happened?

    Eval
    → Was the AI behavior correct?

V1 重点 Eval：

- Planner Eval；
- Evaluator Eval；
- RAG Eval；
- Conversation Eval；
- Tool Eval；
- Difficulty Eval。

确定性规则优先：

`Rule-based / Reference-based Eval`

开放性语言质量问题可以使用有限：

`LLM-as-Judge`

Prompt、Rubric 和 Context Strategy 应保留 Version，方便 Regression。

---

## 16. Security Boundary / 安全边界

### Identity

Hosted Mode：

    Auth
      ↓
    Authenticated UserContext
      ↓
    userId + languageProfileId
      ↓
    Service / Retrieval / Tool

后端不能信任：

- 前端自报 userId；
- LLM Tool 参数中的 userId。

### Persistence Query Safety

Persistence access 使用 MyBatis-Plus starter + MyBatis Mapper XML：

- runtime query / DML statement 集中在 Mapper XML，不写入 Java code 或 SQL annotation；
- schema DDL 继续由 Flyway migration 管理；
- 所有变量值使用 `#{}` 生成 `PreparedStatement` parameter；
- 禁止 `${}` raw substitution；
- 禁止把客户端输入直接作为 column、order、table 或任意 SQL fragment；
- dynamic identifier 如果未来确有需求，必须由 backend enum / allowlist 映射；
- 默认不开放 `last`、`apply`、`SqlRunner` 等可接收 SQL fragment 的入口。

SQL injection checker 只能作为附加防线，不能代替 parameter binding 与 allowlist。

### Retrieval

权限过滤必须发生在 Retrieval / Database 层。

禁止：

    全用户向量检索
    ↓
    再要求 LLM 忽略其他用户内容

### User Import

用户导入内容默认：

`USER_PRIVATE`

并视为：

`UNTRUSTED_CONTENT`

导入内容不能覆盖：

- System Instruction；
- Tool Policy；
- Permission Rule。

---

## 17. BYOK / Credential Flow

项目采用：

**BYOK-first。**

Hosted Mode 默认：

    Browser
    └─ API Key local/session storage
              ↓
            HTTPS
              ↓
        Spring Boot
        transient access
              ↓
         AI Provider

默认禁止 API Key 持久化进入：

- PostgreSQL；
- Redis；
- Trace；
- Log；
- Exception dump。

Self-hosted 可支持：

- browser local；
- local Secret；
- `.env`。

Credential persistence model 的改变属于 Security Architecture Change。

---

## 18. Deployment Model / 部署模型

项目使用：

**One Codebase, Two Modes。**

### Hosted Mode

目标：

    Internet
      ↓
    Reverse Proxy / HTTPS
      ↓
    Vue / PWA
      ↓
    Spring Boot
      ↓
    PostgreSQL + pgvector
    Redis
    Object Storage

第一阶段允许：

**Single Cloud VM + Docker Compose**

优先验证产品与 Agent 闭环。

### Self-hosted Mode

目标体验：

    git clone
        ↓
    cp .env.example .env
        ↓
    docker compose up -d
        ↓
    initialize
        ↓
    configure model
        ↓
    add language
        ↓
    start learning

Hosted / Self-hosted 共用核心业务代码。

部署差异集中于：

- `APP_MODE`；
- `AUTH_MODE`；
- Spring Profile；
- database configuration；
- Redis configuration；
- storage configuration；
- operational capabilities。

---

## 19. Infrastructure Direction / 基础设施方向

### V1 Architecture

    Vue / PWA
    Spring Boot
    PostgreSQL + pgvector
    Redis
    Object Storage
    Flyway
    Docker / Docker Compose
    Health Check
    Background Job
    OpenTelemetry
    External AI Providers

### Scale-driven Backlog

以下组件只有真实规模需要时再考虑：

    Kubernetes
    Kafka
    Redis Cluster
    Database Sharding
    Service Mesh
    complex microservices

原则：

**先完成真实 Learning + Agent Loop，再根据真实瓶颈演进基础设施。**

---

## 20. V1 Architecture Scope / V1 架构范围

当前 V1 Scope 已确认的主要核心区域包括：

### P0 / Core

- Language Profile；
- Java candidate / hard constraint + optional LLM enrichment 的 Planner；
- Vocabulary；
- Text Practice / Conversation minimum loop；
- deterministic assessment + validated semantic candidate 的 Evaluator；
- Learning Memory；
- Weakness / Skill State；
- Review；
- Today / Learning Hub；
- role-specific structured Context Assembly；
- Model / Provider / BYOK；
- Structured Output Validation；
- minimal Trace / Observability；
- Language Management；
- Security / Privacy / Permission；
- Deployment / Infrastructure。

### P1 / Supporting

包括部分：

- Initial Assessment；
- Reading / imported content；
- Content Library / Content Pipeline；
- RAG / Retrieval；
- Tool Gateway / Controlled Multi-role Agent Workflow；
- Listening；
- Language Fundamentals；
- Language Tools；
- Voice STT / TTS；
- Learning Vault；
- Product-facing Trace；
- Product-facing Eval。

### Evidence-driven Capability

- Context budget / selection / dedup：P0 按 Role 提供最小策略与 token trace；
- advanced Context ranking / compression：M3 使用 Retrieval 数据，通过 quality / token / latency /
  cost Eval 决定；
- automatic Model routing：V1 保持 fixed capability mapping，M6 先完成 provider comparison 与 fallback
  evidence，不预建复杂 router。

### Interface / Backlog

当前只需要保留适当架构扩展能力的包括：

- Optimization / Self-improvement；
- Pronunciation Assessment 的高级能力；
- advanced multimodal；
- advanced infrastructure。

### Finalized Cross-module Scope

当前 Scope 文档已完成以下模块裁剪：

- Progress：P1 / M2 read-only projection；
- Continuous Assessment：M2 core assessment + M4 Milestone；
- Practice Feedback：P1 / M2 lightweight feedback；
- Notifications / Learning Recall：V1 之后 Backlog。

具体 authority 以 `docs/product/V1_SCOPE.md` 为准。

---

## 21. Important V1 Simplifications / V1 有意简化

V1 明确优先真实闭环，避免提前实现终局复杂度。

例如：

    RAG
    V1:
    metadata filter + pgvector + Top-K

    Later:
    keyword + vector + rerank

---

    Review
    V1:
    simple time / mastery / failure / evidence rules

    Later:
    FSRS / advanced scheduler

---

    Voice
    V1:
    turn-based STT / TTS

    Later:
    streaming / realtime duplex / barge-in

---

    Background Job
    V1:
    TaskExecutor + DB Job Status

    Later if needed:
    Kafka / dedicated distributed job architecture

---

    Deployment
    V1:
    Docker Compose

    Later if scale requires:
    Kubernetes / Service Mesh

这些简化属于有意的 Scope Decision。

不要在实现过程中自动“补全”为复杂终局架构。

---

## 22. System-level Source of Truth / 系统级状态责任

系统应始终保持以下责任关系：

    LLM
    → interpretation / recommendation / evidence

    Java Domain Rules
    → validation / qualification / state transition

    PostgreSQL
    → structured long-term state

    pgvector
    → semantic retrieval index

    Redis
    → cache / temporary runtime support

    Object Storage
    → large binary/content objects

    IndexedDB
    → client-local cache / offline state

    RAG
    → retrieval context

    Trace
    → observability

    Eval
    → AI behavior quality measurement

任何模块开始承担其他模块的 Source-of-Truth 责任时：

**Architecture Review Required。**

---

## 23. Architecture Invariants / 核心不变量

无论后续代码如何演进，当前批准架构保持：

    One User
    → Multiple isolated Language Profiles

    Practice
    → Evidence
    → Aggregation
    → Long-term State

    LLM
    → Soft Decision

    Java
    → Hard Decision

    Structured State
    → DB / Service Query

    Semantic Context
    → RAG

    Agent
    → Controlled Tool Gateway

    Business Logic
    → Provider-agnostic Model Gateway

    Hosted + Self-hosted
    → Same Core Codebase

    BYOK
    → Credential not persisted by default

如果实现需要破坏上述关系：

进入 Architecture Change Proposal，而不是作为普通代码修改处理。

---

## 24. Document Boundaries / 文档职责

本文件只回答：

> 系统整体由什么组成？

其他问题分别进入：

### `AGENTS.md`

回答：

> Codex 开发这个项目时不能越过哪些边界？

### `MODULE_MAP.md`

回答：

> 每个模块具体在哪里？入口是什么？依赖谁？

### `DATA_FLOW.md`

回答：

> Profile、Task、Session、Evidence、Memory 等数据具体怎么流？

### `AGENT_FLOW.md`

回答：

> Planner / Evaluator / Conversation 如何调用 LLM、Context、RAG 和 Tool？

### `docs/adr/`

回答：

> 为什么选择这个架构方案？

### `docs/planning/ENGINEERING_EVIDENCE_PLAN.md`

回答：

> Agent / Java 面试能力必须通过哪些 Product Flow、Test、Eval、Trace 与 measurement 证明？

### `docs/ownership/OWNERSHIP_MATRIX.md`

回答：

> 当前用户对各模块真正掌握到什么程度？

---

## 25. Implementation Status Maintenance / 实现状态维护

本文件不应通过猜测维护 Implementation Status。

当某个模块真实开发完成后，应根据：

    Source Code
    +
    Test
    +
    Runtime Verification

确认后，再标记为：

`IMPLEMENTED`

如果 Architecture Doc 与实际代码发生差异：

标记：

`DRIFT`

并确认：

1. 代码违反架构；
2. 架构经过批准但文档未更新；
3. 原设计已经失效，需要 ADR。

不得静默让两者长期不一致。

---

## 26. Final Architecture Summary / 最终架构摘要

AI Language Tutor 的系统核心可以压缩为：

    User
      ↓
    Isolated Language Profile
      ↓
    Java Eligible Task Candidates
      ↓
    Optional LLM Plan Enrichment
      ↓
    Java Plan Validation
      ↓
    Real Practice
      ↓
    Deterministic Assessment
      +
    Optional Semantic Evaluation
      ↓
    Java-qualified Evidence
      ↓
    Java-controlled Learning Memory
      ↓
    Persistent Learner State
      ↓
    Better Re-planning

其 AI Engineering Runtime：

    Bounded AI Role
      ↓
    Role-specific Context
      ├─ Structured State
      ├─ RAG
      └─ Recent Context
      ↓
    Model Gateway
      ↓
    Provider

并按实际需求通过：

    Tool Gateway
    +
    Trace
    +
    Eval
    +
    Security Boundary

控制模型能力。

最终系统价值来自：

**Persistent Learner Model + Real Language Practice + Deterministic Workflow + Bounded AI。**
