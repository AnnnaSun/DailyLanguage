# AI Language Tutor — Agent Flow

> 本文描述 AI Language Tutor 中 AI Agent、LLM、Context、RAG、Tool、Structured Output、Trace 与 Java Domain 之间的运行关系。
>
> 本文重点回答：
>
> - 什么在本项目中算一个 Agent？
> - Planner / Conversation / Evaluator 分别如何运行？
> - 每个 Agent 可以看到什么 Context？
> - Agent 如何调用 Tool？
> - Tool 和 RAG 分别解决什么问题？
> - Model Gateway 如何隔离具体 Provider？
> - LLM Output 如何进入 Java Domain？
> - 哪些行为允许 Agent 决策，哪些必须由 Java 控制？
> - 如何避免 Agent 无限循环、越权和污染长期学习状态？
>
> 本文描述 Logical Agent Runtime。
>
> 实际 Java class、framework、package、Agent SDK 或 orchestration implementation 必须根据真实 source code 更新，不得提前假设。

---

# 1. Agent Definition / Agent 定义

在本项目中，Agent 首先表示一个具有明确职责边界的 AI Runtime Role。

一个 Agent 通常包含：

    Role
    +
    Goal
    +
    Prompt
    +
    Context Policy
    +
    Tool Allowlist
    +
    Output Contract
    +
    Validation
    +
    Trace

例如：

    Planner Agent
    → 决定训练意图

    Conversation Agent
    → 推动真实语言互动

    Evaluator
    → 对一次 PracticeSession 做结构化诊断

Agent 不等于：

    一个可以任意访问系统、
    任意调用工具、
    任意修改数据库的 LLM。

---

## 1.1 Architecture Interpretation / 当前架构解释

当前产品设计定义的是多个：

**Bounded AI Roles / 有边界的 AI 角色。**

当前没有架构要求必须：

- 为每个 Agent 建立独立进程；
- 引入复杂 Multi-Agent Framework；
- 让 Agent 之间自由互相对话；
- 使用 Agent 自主决定完整业务流程。

因此 V1 优先：

    Spring Boot Application
        ↓
    Explicit Agent/Application Flow
        ↓
    Context + Model + Tool
        ↓
    Java-controlled Domain State

如果未来需要：

- autonomous orchestration；
- supervisor agent；
- agent-to-agent delegation；
- parallel multi-agent execution；

必须存在真实业务价值后再做 Architecture Decision。

---

# 2. Core Agent Roles / 核心 Agent 角色

当前核心 AI Roles：

| Agent / Role | 主要职责 | 主要输出 | Long-term Write Authority |
|---|---|---|---|
| Planner | 决定练什么、为什么、难度和约束 | LearningTask | No |
| Conversation | 推动场景互动和 Communication Practice | Assistant Response / Session Interaction | No |
| Evaluator | 评估单次 PracticeSession | EvaluationResult / Candidate Evidence | No |
| Content AI Task | Adapt / classify / generate selected content | Content Candidate | No |
| Memory Summary Task | 压缩历史为可检索 Summary | Memory Candidate | No direct state authority |

长期学习状态最终仍由：

    Java Domain Rules
    +
    Learning Memory

控制。

---

# 3. Bounded AI Execution / 受控 AI 执行

不同 AI Role 的业务职责不同。Planner、Evaluator 等默认是 bounded model task，不自动拥有
Tool 或多轮 Agent Loop。

默认调用链：

    Application Request
        ↓
    Resolve UserContext
        ↓
    Resolve languageProfileId
        ↓
    Agent-specific Input
        ↓
    Role-specific Context Assembly
        ├─ Structured State
        ├─ Session Context
        ├─ RAG Context (only when approved)
        └─ Static Instructions
        ↓
    Prompt Resolution
        ↓
    Model Gateway
        ↓
    Provider Adapter
        ↓
    LLM
        ↓
    Structured Output / Response
        ↓
    Java Validation
        ↓
    Application / Domain
        ↓
    Trace / Usage / Eval Hooks

只有某个 Role 获得已批准的真实 tool requirement 后，才扩展为。M3 Content / Reading Flow 已批准为
首个正式 use case：

    Bounded Model Task
        ↓
    Tool Request
        ↓
    Tool Gateway
        ↓
    Tool Result
        ↓
    Bounded Next Model Turn

Tool Gateway、Tool Allowlist 与 Agent Loop 不是所有 AI 调用的默认层。

不同 Agent 主要变化在：

- Context；
- Prompt；
- Tool Allowlist；
- Output Schema；
- Validation；
- Loop Policy。

---

# 4. Agent Request Envelope / Agent 请求上下文

一次 Agent 调用至少需要能够明确：

- current user；
- `languageProfileId`；
- agent type；
- task type；
- language；
- current task / session；
- model connection；
- prompt version；
- context strategy version；
- applicable rubric；
- trace context。

概念上：

    AgentRequest
    ├─ UserContext
    ├─ languageProfileId
    ├─ AgentType
    ├─ TaskType
    ├─ DomainInput
    ├─ ContextPolicy
    ├─ ToolPolicy
    ├─ ModelConfig
    └─ TraceContext

这些是逻辑概念。

不要求实现成一个巨大的 `AgentRequest` 类。

避免为了统一而制造 God Object。

---

# 5. Identity & Language Scope / 身份与语言作用域

进入任何学习 Agent 前：

    Authenticated Request
        ↓
    UserContext
        ↓
    Verify LanguageProfile Ownership
        ↓
    languageProfileId
        ↓
    Agent Runtime

Agent 不可信任：

    userId supplied by LLM

也不应只根据：

    language = "English"

查询学习状态。

核心 Scope：

    authenticated user
    +
    languageProfileId

必须贯穿：

- Context Query；
- RAG；
- Tools；
- Practice；
- Evidence；
- Trace Metadata。

---

# 6. Prompt Architecture / Prompt 架构

Prompt 不使用一个万能多语言模板承担全部任务。

逻辑维度：

    Language
       ×
    Task / Agent

例如：

    English
    ├─ Planner
    ├─ Conversation
    └─ Evaluator

    Japanese
    ├─ Planner
    ├─ Conversation
    └─ Evaluator

    Spanish
    ├─ Planner
    ├─ Conversation
    └─ Evaluator

共享：

- Agent runtime；
- schema；
- Tool Gateway；
- Model Gateway；
- Context infrastructure。

语言特定内容：

- teaching rules；
- evaluation taxonomy；
- prompt instructions；
- writing system；
- difficulty framework；
- pragmatic expectations。

---

## 6.1 Prompt Resolution Flow / Prompt 选择

    AgentType
    +
    Language
    +
    TaskType
    +
    PromptVersion
        ↓
    Prompt Resolver
        ↓
    Effective Prompt

Prompt 必须具备 Version。

重要 Prompt 改动应能够：

    Old Version
        ↓
    Eval

    New Version
        ↓
    Eval

进行 Regression Comparison。

---

# 7. Planner Agent Flow / Planner 流程

Planner 回答：

> 当前应该练什么？为什么？难度和约束是什么？

完整逻辑：

    Today / Replan Request
        ↓
    languageProfileId
        ↓
    Structured State
        ├─ LanguageProfile
        ├─ Active Weakness
        ├─ Skill State
        ├─ Due Review
        ├─ Vocabulary Summary
        ├─ Recent Practice
        ├─ Preferences
        ├─ Available Time
        └─ Evidence Sufficiency
        ↓
    Java Eligible Candidate Generation
        ↓
    Java Hard Constraint Filtering
        ↓
    Optional LLM Ranking /
    Scenario / Reason Enrichment
        ↓
    Java Final Validation
        ↓
    LearningTask
        ↓
    User Accept / Replace /
    Skip / Easier / Harder /
    Topic / Replan

---

## 7.1 Planner Soft Decisions

适合 LLM 判断：

- 哪种 Practice 当前更有价值；
- 哪个 Weakness 值得自然穿插；
- 如何避免重复场景；
- 哪种场景适合训练 Communication Goal；
- 推荐理由如何表达。

LLM 只能在 Java 已确认合法的候选与边界内做 soft decision。它不是合法候选集合、duration、
Practice availability 或 final persistence validity 的 authority。

---

## 7.2 Planner Hard Decisions

由 Java 控制：

- 当前用户是否拥有该 `languageProfileId`；
- Practice 是否可用；
- 用户是否设置 `DISABLED`；
- enum 是否有效；
- duration 是否有效；
- content 是否存在；
- Tool permission；
- final persisted task validity。

Java 还负责生成 deterministic fallback priority。Model unavailable、timeout 或最终 candidate 无效时，
Planner 必须能够返回合法但可能较弱的 fallback LearningTask。

---

## 7.3 Planner Forbidden Flow

禁止：

    Planner
        ↓
    setWeakness()

禁止：

    Planner
        ↓
    changeLevel()

禁止：

    Planner
        ↓
    LearningMemory.update()

禁止：

    Planner
        ↓
    直接完成 Conversation

Planner 的 Domain Output 是：

`LearningTask`

而不是：

`Learner State Mutation`

---

# 8. Planner Tool Policy / Planner Tool 权限

Planner 是：

**Read-heavy Agent。**

适合提供：

- Learner State read；
- Weakness read；
- Review read；
- Vocabulary summary read；
- Recent Practice read；
- Content search；
- Learning Material search。

不适合提供：

- setLevel；
- setWeakness；
- arbitrary vocabulary mastery update；
- delete history；
- arbitrary DB write。

Planner 是否需要调用 Tool，应取决于 Context 是否已经提供必要数据。

不要出现：

    Context already contains Profile
        ↓
    Agent still calls getProfile
        ↓
    unnecessary tool call

Tool Eval 后续应检查这种冗余行为。

---

# 9. Conversation Agent Flow / Conversation 流程

Conversation 是持续多轮 Agent Runtime。

启动：

    LearningTask
        ↓
    Create PracticeSession
        ↓
    Resolve Scenario
        ↓
    Communication Goals
        ↓
    Target Vocabulary /
    Limited Target Weakness
        ↓
    Conversation Context
        ↓
    Conversation Agent

每轮：

    User Message
        ↓
    Append Session State
        ↓
    Context Manager
        ├─ Scenario State
        ├─ Recent Turns
        ├─ Session Summary
        ├─ Hidden Goals
        └─ Limited Learning Signals
        ↓
    Conversation Prompt
        ↓
    Model Gateway
        ↓
    LLM
        ↓
    Optional Tool Call
        ↓
    Assistant Response
        ↓
    Append PracticeSession
        ↓
    Continue / End

Session 结束：

    PracticeSession
        ↓
    Evaluator

Conversation Agent 本身不负责最终 Evaluation。

---

# 10. Conversation Runtime State / 对话运行状态

Conversation 需要区分：

### Domain State

Java 维护：

- session ID；
- task；
- scenario；
- goal progress where deterministic；
- assistance count；
- interaction history；
- session status。

### Conversational Context

供模型使用：

- recent turns；
- structured summary；
- relevant scenario facts；
- hidden communication targets；
- limited learner signals。

关键事实不能只存在 LLM 自己的“记忆”里。

---

# 11. Conversation Context Window / 对话 Context

概念策略：

    Recent Turns
        +
    Structured Session Summary
        +
    Current Scenario
        +
    Relevant Learning Goals

旧对话：

    Older Turns
        ↓
    Structured Summary

Summary 可以包含：

- topic；
- facts；
- intentions；
- scenario progress；
- goal progress；
- important expressions；
- unresolved items。

长期 Learner Memory 不应整包放入 Conversation Prompt。

---

# 12. Conversation Assistance Flow / 对话辅助流

用户卡住时，可以请求：

    IDEA
      ↓
    KEYWORDS
      ↓
    PATTERN
      ↓
    HOW_TO_SAY

其他可能辅助：

- SIMPLIFY；
- REPEAT；
- EXPLAIN；
- THREE_DIRECTIONS。

调用结果首先属于：

    Session Assistance Data

它可以影响：

    Independence Evidence

但一次帮助请求不能直接形成：

    Weakness

---

# 13. Conversation Correction Policy / 对话纠错

支持：

- `END_ONLY`
- `IMPORTANT_ONLY`
- `REAL_TIME`

默认倾向：

`END_ONLY`

Agent 的首要责任是维持真实互动。

不要形成：

    User Message
        ↓
    Grammar Correction
        ↓
    User Message
        ↓
    Grammar Correction

这种逐句批改循环。

---

# 14. Conversation Tool Policy / Conversation 工具权限

Conversation Tool 必须限制在当前 Session 的真实需要。

可能包括：

- dictionary lookup；
- contextual expression lookup；
- limited content lookup；
- session-local assistance；
- controlled vocabulary action。

高风险长期状态修改不属于 Conversation Tool。

禁止：

- setLevel；
- activateWeakness；
- delete learning data；
- arbitrary profile mutation。

如果用户在对话中表达：

> 删除我的全部学习记录

Conversation Agent 也不能直接获得这种危险 Tool 权限。

应转入受控产品操作流程。

---

# 15. Evaluator Flow / Evaluator 流程

Evaluator 在 Practice 完成后运行。

输入：

    PracticeSession
        +
    LearningTask
        +
    Compact LanguageProfile
        +
    Task-specific Rubric

先从 trusted Practice event 形成：

    Deterministic Assessment
        ├─ task completion
        ├─ duration
        ├─ attempts
        ├─ assistance usage
        └─ exact / rule-verifiable result

需要语义判断时再运行：

    Context Manager
        ↓
    Evaluator Context
        ↓
    Language × Evaluator Prompt
        ↓
    Model Gateway
        ↓
    LLM
        ↓
    Structured Evaluation
        ↓
    Schema Validation
        ↓
    Enum Validation
        ↓
    Deduplication
        ↓
    Semantic Qualification
        ↓
    Validated Semantic Candidate

最终 Session-level Evaluation 由：

    Deterministic Assessment
        +
    Validated Semantic Candidate (if available)

组成。两类结果分别保留 provenance，并经过相应 Java qualification 后才形成 Evidence。

---

# 16. Evaluator Context Policy / Evaluator 上下文策略

Evaluator 主要需要知道：

- 本次任务目标；
- 用户实际表现；
- 使用了多少 Assistance；
- Task-specific Rubric；
- 最小必要 Profile。

Evaluator 默认不读取：

- 所有历史 Weakness；
- 所有历史 Conversation；
- 全部 Personal Memory；
- 全部 Error History。

原因：

- 降低 Token；
- 降低 Historical Bias；
- 避免“因为知道用户以前错过，所以更容易判他又错”。

Evaluator 判断应优先根据：

**Current Session Evidence。**

---

# 17. Evaluator Output / Evaluator 输出

Deterministic Assessment 典型输出：

- taskCompletion；
- duration / attempts；
- assistance usage；
- exact / rule-verifiable results。

Semantic Evaluation Candidate 典型输出：

- strengths；
- detectedIssues；
- vocabularyResults；
- communicationResults；
- independenceLevel；
- overallPerformance；
- confidence。

Issue 可包括：

- LANGUAGE_ERROR；
- NATURALNESS_ISSUE；
- TASK_COMPLETION；
- COMMUNICATION_ISSUE；
- COMPREHENSION_ISSUE。

Pronunciation Issue 后续随 Pronunciation Assessment 扩展。

---

# 18. Evaluator State Boundary / Evaluator 状态边界

允许：

    Evaluator
        ↓
    "QUESTION_WORD_ORDER occurred in this session"

禁止：

    Evaluator
        ↓
    "QUESTION_WORD_ORDER is now ACTIVE weakness"

正确流程：

    Evaluator
        ↓
    CandidateIssue
        ↓
    Java Validation
        ↓
    Qualified Evidence
        ↓
    Learning Memory
        ↓
    Aggregation
        ↓
    WeaknessState

---

# 19. Evaluator Failure / Evaluator 失败

如果：

- Model timeout；
- provider failure；
- invalid schema；
- unrecoverable structured output；
- semantic validation failure；

执行：

    SemanticEvaluationStatus = FAILED

并保持：

    PracticeSession
    → preserved

同时：

    No model-derived Qualified Evidence
    → No model-derived State Mutation

已经由 trusted Practice event 确定的 deterministic assessment / Evidence 保留并可继续按 Java
规则处理。Model failure 不得将其删除、降级为模型失败或重复生成。

禁止“尽量保存半个模型结果”进入长期 Memory，也禁止因 semantic evaluation 失败而抹掉独立
成立的 deterministic result。

---

# 20. RAG in Agent Runtime / Agent 中的 RAG

RAG 负责：

**semantic retrieval。**

三类逻辑 Retrieval：

- Personal Memory RAG；
- Content RAG；
- User Import RAG。

流程：

    Agent Need
        ↓
    Retrieval Intent
        ↓
    Authenticated Context
        ↓
    Scope
        ↓
    Metadata Filter
        ↓
    Retrieval
        ↓
    Ranked Results
        ↓
    Context Manager
        ↓
    Selected Context
        ↓
    Agent

---

## 20.1 Structured State vs Retrieval

以下不应该靠 RAG 查：

- Current Level；
- Active Weakness；
- Due Vocabulary；
- Language Profile；
- exact Review State。

应：

    Application Service
        ↓
    Structured Query

RAG 适合：

- similar historical episode；
- previous expression；
- relevant imported section；
- relevant learning material；
- semantic memory。

---

# 21. RAG Scope / RAG 范围

User Import 默认：

    CURRENT_DOCUMENT

只有明确需要时扩展：

    MY_IMPORTS

进一步扩展：

    CONTENT_LIBRARY

Agent 不应随意扩大 Retrieval Scope。

所有检索必须保持：

    user isolation
    +
    language isolation
    +
    content permission

---

## 21.1 M3 Controlled Multi-role Agent Workflow

M3 正式实现一条由 Java 控制的 Multi-role Agent handoff：

    Content Retrieval Role
        ↓
    Source Chunks + Provenance
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

这条 Flow 用于展示真实 Agent Engineering，而不是让 Agent 自治管理系统。Java 控制：

- workflow state；
- maximum model turns / tool calls；
- `UserContext`、language / content permission；
- timeout、retry、idempotency 与 terminal failure；
- provenance / schema / semantic validation；
- final publish authority。

Quality Review 至少检查 groundedness、difficulty、language quality 与 unsafe / unsupported claim。
Agent 输出必须引用 source / chunk ID；缺少合法 provenance 的 material candidate 不得发布。
三个 Role 具有独立 contract、Context Budget、Tool Allowlist 与 Trace span，只通过 typed artifact
handoff，不共享可变 Prompt State。每次 Role invocation 是 bounded Agent execution，但整体不是
autonomous Multi-agent System。

---

# 22. Context Manager Flow / Context Manager

Context Manager 负责：

> 为当前 Agent 选择必要且足够的 Context。

输入来源：

    Static Context
    Learner State
    Session Context
    Retrieved Context

形成：

    Context Package

---

## 22.1 Context Priority

Context Item 可以具有：

- CRITICAL；
- HIGH；
- MEDIUM；
- LOW。

如果超过预算：

    Remove LOW
        ↓
    Deduplicate
        ↓
    Reduce Retrieval Top-K
        ↓
    Summarize Old Messages
        ↓
    Optional LLM Compression

不得首先裁掉业务必需 Hard Constraint。

每个 Role 需要定义：

- input token budget；
- reserved output budget；
- truncation / summary order；
- selected context IDs；
- actual token、latency 与 cost metadata。

M3 必须比较 Full Context baseline 与 budgeted strategy，不能只凭 token 减少声明优化成功；质量、
groundedness 与任务完成率不得出现未经接受的 regression。

---

## 22.2 Agent-specific Context

Planner：

    Profile
    Active Weakness
    Review
    Preferences
    Recent Practice
    Available Time

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

原则：

**Context is role-specific。**

不要建立：

    GlobalFullLearnerContext
        ↓
    Inject into every Agent

---

# 23. Context Provenance / Context 来源信息

重要 Context 应尽可能知道：

- source；
- freshness；
- language；
- user scope；
- document / evidence ID。

来自：

- PDF；
- Markdown；
- imported URL；
- other external content；

应标记：

`UNTRUSTED_CONTENT`

其内容不能覆盖：

- System Instruction；
- Tool Policy；
- Permission Policy。

---

# 24. Tool Calling Runtime / Tool Calling 运行流

Agent 需要应用能力时：

    LLM
        ↓
    Tool Selection
        ↓
    Tool Arguments
        ↓
    Tool Gateway

Tool Gateway：

    Tool Schema Validation
        ↓
    AuthenticatedContext
        ↓
    Tool Allowlist
        ↓
    Permission
        ↓
    Argument Validation
        ↓
    Idempotency
        ↓
    Execute
        ↓
    Timeout
        ↓
    Bounded Retry
        ↓
    Tool Result
        ↓
    Trace
        ↓
    Agent

---

# 25. Tool Result Contract / Tool 返回协议

统一 Result Status 可以包括：

- SUCCESS；
- VALIDATION_ERROR；
- PERMISSION_DENIED；
- NOT_FOUND；
- TEMPORARY_UNAVAILABLE；
- TIMEOUT；
- INTERNAL_ERROR。

Agent 应根据明确状态做下一步判断。

不要让模型通过分析异常堆栈猜：

> 这个 Tool 到底成功没有？

---

# 26. Tool Retry Ownership / Tool 重试责任

Tool Execution Retry 由：

**Tool Executor / Java Runtime**

控制。

不允许：

    LLM:
    tool failed
        ↓
    call again
        ↓
    call again
        ↓
    call again forever

Agent 可以在允许范围内决定替代动作。

但 infrastructure retry：

- max attempts；
- timeout；
- backoff；
- retryable error；

由 deterministic runtime 控制。

---

# 27. Tool Write Boundary / 写 Tool 权限

普通 Agent 写能力优先表达为：

    intent / evidence submission

例如：

    submitEvidence()

而不是：

    setLevel()
    setWeakness()
    setVocabularyMastery()

长期状态写入：

    Tool / Application
        ↓
    Validation
        ↓
    Learning Memory
        ↓
    Java State Rule

---

# 28. Model Gateway Flow / 模型网关

所有通用模型调用：

    Agent
        ↓
    Typed Model Operation Port
        ↓
    Purpose + Operation Route
        ↓
    Capability Check
        ↓
    Provider Adapter
        ↓
    External Model

可支持：

- DeepSeek；
- OpenAI；
- Gemini；
- Ollama；
- OpenAI-compatible provider。

Domain Agent 不应依赖：

    DeepSeekClient
    OpenAIClient
    GeminiClient

Text Generation、Vision Understanding、Speech Transcription、Speech Synthesis、Image Generation 与
Embedding 的 Request / Response shape 不同。Model Gateway 是统一 logical module boundary，不使用一个
万能 Request 或巨大 Provider interface 承载所有 Operation。

---

## 28.1 V1 Model Routing

V1 可以使用：

**Fixed Mapping。**

Route key 同时包含业务 Purpose 与技术 Operation，例如逻辑上：

    PLANNING + TEXT_GENERATION
    → configured model

    CONVERSATION + TEXT_GENERATION
    → configured model

    CONVERSATION + SPEECH_TRANSCRIPTION
    → separately configured model

    EVALUATION + TEXT_GENERATION
    → configured model

后续再考虑：

- cost aware；
- latency aware；
- language aware；
- quality aware；
- automatic fallback。

不要在 V1 提前实现复杂自动 Model Router。

Gateway 每次只执行一个 Operation。多个 Operation 的顺序、required / optional、partial success 与
degradation 属于具体 Application Workflow。S6 默认不自动 retry，也不静默 cross-provider fallback；
后者需要用户配置、Credential、capability compatibility 与明确 policy 共同授权。

详细 Contract：[`MODEL_GATEWAY.md`](../features/MODEL_GATEWAY.md)。

## 28.2 M0-S6–S7A 已实现边界

当前已实现的是 Text Generation 在 Model Gateway Module 内的 route、transient Credential propagation 与
execution chain：

    TextGenerationPort.generateText(request, credential)
        ↓
    RoutedTextGenerationPort.generateText(request, credential)
        ↓
    FixedTextGenerationRoutes.findRoute(request.purpose())
        ↓
    no route → ModelResult.Failure(CAPABILITY_UNAVAILABLE)
        或
    credential.providerId != route.providerId
        → ModelResult.Failure(CREDENTIAL_UNAVAILABLE, selected route identity)
        或
    ExecutorService.submit(Adapter task capturing credential)
        ↓
    worker: TextGenerationProviderAdapter.generateText(
                providerId, modelId, request, credential, executionTimeout)
        ↓
    caller: Future.get(executionTimeout)
        ↓
    timeout / typed Provider failure translation 或 route identity validation
        ↓
    ModelResult<TextGenerationResponse>

该调用链在 operation-specific Adapter boundary 结束。M0-S7A 已实现 Credential 在 module-local
Port → route → Executor → Adapter 间的显式传播，但尚无 Browser / HTTPS Credential ingress、concrete
Provider HTTP / SDK、Application Workflow、Structured Output validation 或 Trace persistence，因此不得
把上述 Module-local flow 解释为已完成的 Agent → External Model End-to-End behavior。

真实调用链与验证证据见
[`text-generation-credential-propagation.md`](../flow/text-generation-credential-propagation.md)。

---

# 29. BYOK Runtime / BYOK 运行链

Hosted：

    Browser Credential
        ↓
    HTTPS
        ↓
    Backend transient access
        ↓
    Model Gateway
        ↓
    Provider

Credential 不进入：

- Prompt；
- RAG；
- Tool Arguments；
- Trace；
- Database；
- Redis；
- log。

Self-hosted 可由：

- browser；
- local Secret；
- `.env`；

提供 Credential。

---

# 30. Structured Output Flow / 结构化输出

Planner、Evaluator 等业务 Agent 不能依赖随意自然语言解析。

流程：

    LLM
        ↓
    Structured Output
        ↓
    Parse
        ↓
    Schema Validation
        ↓
    Enum Validation
        ↓
    Semantic Validation
        ↓
    Domain Qualification
        ↓
    Application

如果模型返回：

    valid JSON
    but invalid business semantics

仍然属于失败。

因此：

**JSON Valid ≠ Domain Valid。**

---

# 31. Structured Output Repair / 输出修复

允许有限：

    Invalid Structured Output
        ↓
    Repair / Retry
        ↓
    Validate Again

如果最终失败：

    STOP

不能：

    invalid output
        ↓
    "大概看得懂"
        ↓
    persist state

Repair 次数应受 Runtime Policy 控制。

---

# 32. Agent Loop Termination / Agent 循环终止

只有存在真实 Tool Calling 的 AI Role 才进入 Agent Loop。Planner、Evaluator 默认是单次 bounded
model task，不应为了展示 Agent 而创建循环。

任何实际存在的 Tool Calling Agent Loop 必须具备显式终止策略。

逻辑状态：

    MODEL_CALL
        ↓
    TOOL_REQUEST?
       ├─ Yes
       │    ↓
       │  TOOL_EXECUTION
       │    ↓
       │  MODEL_CALL
       │
       └─ No
            ↓
         FINAL_OUTPUT

必须存在：

- maximum tool calls；
- maximum model turns；
- request timeout；
- tool timeout；
- terminal failure；
- successful output condition。

不允许无限 Agent Loop。

具体数值在实现与 Eval 中确定。

---

# 33. Failure Handling / Agent 失败处理

## Model Failure

    Provider Error
        ↓
    Runtime Policy
        ↓
    Retry if allowed
        ↓
    Final Failure

不得污染 Domain State。Planner 应使用 Java fallback；Evaluator 只隔离 model-derived candidate；
Practice、deterministic assessment、state replay 与 recovery 继续保持有效。

---

## Tool Failure

    Tool Result != SUCCESS
        ↓
    Agent sees explicit status
        ↓
    retry / alternative / terminate
        ↓
    bounded by runtime

---

## RAG Failure

    RAG unavailable
        ↓
    degrade where possible
        ↓
    Structured Context only

RAG 临时失败不应默认让整个产品不可使用。

---

## Context Failure

缺少 CRITICAL Context：

    STOP / explicit error

不要让 LLM 在关键学习状态缺失时随意猜测。

---

## Validation Failure

    Model Output
        ↓
    invalid
        ↓
    bounded repair
        ↓
    fail safely

---

# 34. Trace Flow / Agent Trace

每次 Agent Runtime 应能够关联：

    traceId
        ↓
    Agent Span
        ├─ Context Span
        ├─ RAG Span
        ├─ Tool Span
        └─ LLM Span

适合记录：

- agentType；
- taskType；
- provider；
- model；
- promptVersion；
- rubricVersion；
- contextStrategyVersion；
- token count；
- latency；
- retry count；
- tool name；
- RAG scope；
- selected context IDs；
- status。

---

## 34.1 Trace Content Policy

生产默认：

`METADATA_ONLY`

避免无条件保存：

- complete conversation；
- complete prompt；
- private imported content；
- API Key。

需要调试 Content 时应受明确配置和隐私策略控制。

---

# 35. Agent Eval / Agent 评测

Trace 回答：

> 发生了什么？

Eval 回答：

> Agent 行为是否正确？

---

## Planner Eval

检查：

- constraint satisfaction；
- weakness coverage；
- time fit；
- preference fit；
- variety；
- reason quality。

---

## Evaluator Eval

检查：

- issue classification；
- false positive；
- confidence；
- schema correctness；
- historical bias；
- source turn / span grounding；
- unsupported claim rate；
- false-positive long-term issue risk。

---

## Conversation Eval

检查：

- task completion；
- natural interaction；
- communication goal support；
- unnecessary correction；
- scenario consistency。

---

## Tool Eval

检查：

- correct tool；
- correct arguments；
- unnecessary tool；
- permission handling；
- failure handling；
- loop behavior。

---

## RAG / Context Eval

检查：

- retrieval relevance；
- language isolation；
- permission isolation；
- useful context；
- token cost；
- latency。

---

# 36. Versioned AI Behavior / AI 行为版本化

重要 AI Behavior 应尽可能能够定位：

    Model Version
    +
    Prompt Version
    +
    Rubric Version
    +
    Context Strategy Version
    +
    Dataset Version

这样当行为改变时，可以判断：

> 是 Model 变了？

> Prompt 变了？

> Context 变了？

> Rubric 变了？

而不是只看到：

> AI 最近感觉不太一样。

---

# 37. Multi-language Agent Isolation / 多语言 Agent 隔离

所有 Agent 调用必须运行在：

    one languageProfileId

下。

禁止：

    English Planner
        ↓
    Japanese Weakness Context

禁止：

    RAG
        ↓
    all language memories
        ↓
    let LLM filter

语言隔离必须在：

- query；
- context；
- retrieval；
- tool；
- prompt resolution；

进入模型前完成。

---

# 38. Voice Conversation Extension / Voice Agent 扩展

V1 Voice 方向为 Turn-based。

流程：

    User Audio
        ↓
    STT Provider
        ↓
    Transcript
        ↓
    Conversation Agent
        ↓
    Text Response
        ↓
    TTS Provider
        ↓
    Audio Response

需要分别记录：

- sttLatency；
- agentLatency；
- ttsLatency；
- totalTurnLatency。

STT 只回答：

> 用户说了什么？

它不能直接回答：

> 用户发音是否正确？

Pronunciation Assessment 是独立能力。

---

# 39. Non-Agent AI Tasks / 非 Agent 型 AI Task

并非所有 LLM 调用都应该包装成 Agent。

例如：

- translation；
- difficulty classification；
- content adaptation；
- memory summarization；
- simple explanation；

如果任务满足：

    fixed input
    +
    fixed model call
    +
    fixed output

则普通 AI Service / Model Task 已经足够。

不要为了项目“Agent 含量”把每个模型调用变成：

    Agent
    +
    Tools
    +
    Memory
    +
    Loop

Agent Architecture 应由任务需要驱动。

---

# 40. Agent vs Workflow / Agent 与确定性流程

业务流程整体仍由 Application 控制。

例如：

    Complete Practice
        ↓
    Deterministic Assessment
        ↓
    Optional Semantic Evaluator
        ↓
    Validate by Provenance
        ↓
    Create Evidence
        ↓
    Aggregate Memory

这一段适合 deterministic workflow。

Evaluator 内部：

    理解语言表现
    判断 issue
    判断 naturalness
    判断 communication quality

适合 LLM soft reasoning。

因此项目采用：

    Deterministic Workflow
        +
    Bounded Agent Decisions

而不是：

    LLM decides entire application workflow

Learning Workflow、Practice lifecycle 和 Evidence aggregation 不属于 Agent Runtime；它们即使在
Model unavailable 时也必须保持正确、可恢复和可重放。

---

# 41. Agent Authority Matrix / Agent 权限矩阵

| Capability | Planner | Conversation | Evaluator | Java Domain |
|---|---:|---:|---:|---:|
| Read Compact Profile | Yes | Limited | Limited | Yes |
| Read Active Weakness | Yes | Limited targets | Usually minimal | Yes |
| Read Review | Yes | No / limited | No | Yes |
| Read Recent Conversation | No | Yes | Current Session | Yes |
| Semantic RAG | If useful | If useful | Minimal / task-specific | Yes |
| Read Tool | Yes, allowlist | Yes, allowlist | Very limited | Yes |
| Write Session | No | Controlled | No | Yes |
| Produce LearningTask | Yes | No | No | Validate/Persist |
| Produce AI Response | No | Yes | No | No |
| Produce Evaluation Candidate | No | No | Yes | Validate |
| Produce Evidence Candidate | No | Session interaction only | Yes | Qualify |
| Activate Weakness | No | No | No | Yes |
| Change Level | No | No | No | Yes |
| Persist Mastery | No | No | No | Yes |

---

# 42. Architecture Red Flags / Agent 架构警报

出现以下情况需要 Architecture Review。

### Universal Agent

    LearningAgent
    ├─ Plan
    ├─ Talk
    ├─ Evaluate
    ├─ Update Memory
    ├─ Change Level
    └─ Search everything

职责过宽。

---

### Direct Provider Dependency

    Evaluator
        ↓
    OpenAI SDK

绕过 Model Gateway。

---

### Full Context Injection

    Complete Learner History
        ↓
    Every Agent

Context 边界失效。

---

### LLM-owned Business Rule

    Prompt:
    "If error appears 3 times, activate weakness"

长期 State Rule 被藏入 Prompt。

---

### Agent-owned Identity

    toolCall.userId
        ↓
    Database Query

身份边界错误。

---

### Unbounded Tool Loop

    Tool Error
        ↓
    LLM Retry
        ↓
    Tool Error
        ↓
    LLM Retry
        ↓
    ...

没有 Runtime Guardrail。

---

### RAG as Memory Authority

    Retrieved historical text
        ↓
    State Update

绕过 Evidence Aggregation。

---

### Multi-Agent for Appearance

为一个简单固定调用：

    Supervisor
        ↓
    Worker Agent
        ↓
    Reviewer Agent

但不存在真实决策或隔离需求。

属于不必要复杂度。

---

# 43. V1 Agent Runtime Scope / V1 范围

V1 应重点展示：

- Java candidate / constraint + optional LLM enrichment 的 hybrid Planner；
- Conversation Agent；
- deterministic assessment + optional semantic candidate 的 hybrid Evaluator；
- role-specific structured Context；
- Structured Output；
- Model Gateway；
- Trace；
- Prompt / Rubric / Context Version；
- Basic Eval。

V1 planned later-phase capability：

- Tool Allowlist / Tool Gateway：M3 Controlled Multi-role Agent Workflow；
- RAG：M3 Content / Retrieval grounding；
- bounded Agent handoff / review loop：M3；
- advanced Context ranking / compression：由 M3 quality / token / latency / cost Eval 驱动。

V1 暂不需要为了完整性引入：

- autonomous Supervisor Agent；
- complex agent delegation；
- agent swarm；
- self-modifying Prompt Agent；
- unrestricted write tools；
- advanced automatic Model Routing；
- fully autonomous Optimization Agent。

Optimization / Self-improvement 当前保持受控：

    Trace
        ↓
    Eval
        ↓
    Human Analysis / Candidate
        ↓
    Human Approval
        ↓
    Release

---

# 44. Physical Agent Mapping / 实际代码映射

> 已有真实实现的 Module 记录实际映射；未实现部分保持 `TBD`，不根据 Architecture Design 猜测 class name。

| Agent / Runtime | Source Path | Main Entry | Prompt | Context Policy | Tools | Output Schema | Tests |
|---|---|---|---|---|---|---|---|
| Planner | TBD | TBD | TBD | TBD | TBD | TBD | TBD |
| Conversation | TBD | TBD | TBD | TBD | TBD | TBD | TBD |
| Evaluator | TBD | TBD | TBD | TBD | TBD | TBD | TBD |
| Context Manager | TBD | TBD | N/A | TBD | N/A | TBD | TBD |
| Tool Gateway | TBD | TBD | N/A | N/A | TBD | TBD | TBD |
| Model Gateway | `server/src/main/java/com/dailylanguage/modelgateway` | `TextGenerationPort`, `RoutedTextGenerationPort` | N/A | Request 不携带 Domain identity 或 Credential | N/A | `ModelResult<TextGenerationResponse>` | `server/src/test/java/com/dailylanguage/modelgateway` |
| RAG | TBD | TBD | N/A | N/A | N/A | TBD | TBD |

不得根据 Architecture Design 猜测 class name。

---

# 45. Agent Review Checklist / Agent Feature Review

开发 Agent-related Feature 时检查：

### Responsibility

- 这个职责真的属于该 Agent 吗？
- 是否侵入 Planner / Evaluator / Memory 边界？

### Context

- Agent 真正需要哪些 Context？
- 是否读取过多？
- 是否保持 language isolation？
- 是否定义 token budget、裁剪顺序与质量对照？

### Tools

- 是否真的需要 Tool？
- Tool Allowlist 是否最小？
- 是否暴露长期状态直接写权限？

### Model

- 是否通过 Model Gateway？
- 是否依赖具体 Provider？

### Output

- 是否有明确 Contract？
- Structured Output 是否验证？
- semantic claim 是否引用可验证 source turn / chunk？

### State

- LLM 是否试图直接决定长期状态？

### Failure

- Model / Tool / RAG 失败时会发生什么？
- 是否存在无限 Retry？

### Trace

- 是否能够定位 Agent / Prompt / Tool / RAG / Model？

### Eval

- 这个 Agent 行为以后如何验证没有回归？
- 是否同时检查 groundedness、quality、token、latency、cost 与适用的 failure path？

---

# 46. Maintenance Rule / 维护规则

以下变化需要检查本文：

- 新 Agent Role；
- Agent Responsibility 改变；
- Tool Allowlist 改变；
- Tool Write Authority 改变；
- Context Policy 改变；
- Prompt Architecture 改变；
- RAG Scope 改变；
- Agent Loop 改变；
- Model Routing 改变；
- Structured Output Contract 改变；
- Retry / Termination Policy 改变；
- Agent 与 Learning Memory 权限关系改变。

普通 Prompt wording 微调：

如果没有改变架构职责，

不需要修改本文。

但仍应更新对应 Prompt Version。

---

# 47. Final Agent Runtime Summary / 最终摘要

Language Tutor 的核心 Learning / AI relationship 可以压缩为：

    Java Application
        ↓
    Deterministic Workflow / Candidate Boundary
        ↓
    Bounded Agent Role
        ↓
    Context Manager
        ├─ Structured State
        ├─ Session Context
        └─ RAG Context
        ↓
    Language × Task Prompt
        ↓
    Model Gateway
        ↓
    Provider
        ↓
    Structured Response
        ↓
    Java Validation
        ↓
    Domain

如果 Agent 需要系统能力：

    Agent
        ↓
    Tool Call
        ↓
    Tool Gateway
        ↓
    Auth / Permission /
    Validation / Execution
        ↓
    Tool Result
        ↓
    Agent

最终长期学习状态：

    Practice
        ↓
    Deterministic Assessment
        +
    Optional Semantic Evaluation
        ↓
    Candidate Evidence
        ↓
    Java Validation
        ↓
    Learning Memory
        ↓
    Long-term State

核心原则：

**Agent decides soft behavior.**

**Java controls hard rules.**

**Model failure does not erase deterministic learning evidence.**

**Context is task-specific.**

**Tools are permissioned.**

**RAG provides context, not truth.**

**Model providers stay behind Model Gateway.**

**Every important AI behavior is traceable and evaluable.**
