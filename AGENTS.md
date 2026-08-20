# AI Language Tutor — Repository AGENTS

> 本文件定义 AI Language Tutor repository 的项目级 Architecture Contract（架构合同）与产品边界。
>
> 通用开发流程、Small Diff、Review Gate、Human Ownership、Git Safety、Context Efficiency 等规则继承用户级 Codex Harness。
>
> 本文件只保留 Language Tutor 特有、长期必须遵守的约束。

---

## 1. Project Identity / 项目定位

AI Language Tutor 是一个：

**provider-agnostic、stateful AI Language Tutor Agent。**

核心目标是帮助用户真正使用语言完成：

- 日常交流；
- 阅读理解；
- 自然表达；
- 真实场景沟通。

项目不能逐渐退化为：

- 普通背单词 App；
- 单纯题库；
- Grammar Drill App；
- ChatGPT 套壳聊天框；
- 只有 Prompt、没有长期学习状态的 Demo。

核心差异化来自：

    Persistent Learner Model
            ↓
         Planner
            ↓
         Practice
            ↓
        Evaluator
            ↓
      Learning Memory
            ↓
      Profile Update
            ↓
       Re-planning

任何核心 Feature 设计都应判断：

> 它是否真正参与、增强或支撑这个闭环？

---

## 2. Product North Star / 产品北极星

长期学习目标关注：

    以前不会表达
    → 现在能表达

    以前只能回答
    → 现在能主动追问

    以前听不懂自然表达
    → 现在能参与真实互动

    以前知道语法规则
    → 现在能自然使用

产品优先：

- Communication；
- Reading Comprehension；
- Natural Expression；
- Fluency；
- Real-world Language Use。

不要以以下指标作为核心学习模型的主要驱动力：

- 题目数量；
- 打卡天数；
- 词汇数量；
- 考试分数。

---

## 3. Grammar Boundary / Grammar 边界

Grammar 主要作为：

**Grammar Repair**

存在。

典型流程：

    真实使用
    ↓
    重复、高置信结构问题
    ↓
    短解释
    ↓
    Micro Practice
    ↓
    回到真实场景 Transfer

一次 Grammar Error 不应直接创建长期 Weakness。

不要把 Grammar 扩张成主学习路径，除非产品设计经过人工明确修改。

---

## 4. Multi-language Hard Isolation / 多语言硬隔离

这是核心数据正确性约束。

每门目标语言拥有独立：

- Language Profile；
- Level；
- Skill State；
- Weakness；
- Vocabulary Mastery；
- Communication Skill；
- Practice History；
- Review State；
- Learning Memory；
- Evidence；
- Planner Context。

不同学习语言之间禁止共享上述状态。

例如：

    English ARTICLE_USAGE weakness

不得影响：

    Japanese Planner

### 4.1 `languageProfileId`

学习数据优先通过：

`languageProfileId`

建立归属。

不要仅依赖：

`userId + languageCode`

在各模块临时推断学习 workspace。

核心学习实体设计时必须考虑：

    User
      ↓
    LanguageProfile
      ↓
    Language-specific Learning State

### 4.2 Allowed Shared State / 可共享状态

以下内容可以在用户级共享：

- UI language；
- timezone；
- default study time；
- notification preference；
- model connection configuration；
- global infrastructure configuration。

语言专属 preference 是否共享，应根据字段语义判断。

不要因为属于同一个 `user` 就默认共享学习状态。

---

## 5. Persistent Learner Model / 持续学习者模型

Persistent Learner Model 是项目核心 Domain。

长期状态不能来自一次模型判断。

基本链路：

    Practice
    ↓
    Raw Evidence
    ↓
    Aggregated Memory
    ↓
    Long-term State
    ↓
    Language Profile

长期状态可能包括：

- Weakness；
- SkillMastery；
- CommunicationSkill；
- VocabularyMastery；
- Level。

需要综合考虑：

- multiple evidence；
- recency；
- frequency；
- scenario；
- confidence；
- independence；
- correct evidence。

不要把：

`LLM output`

直接持久化成：

- ACTIVE Weakness；
- MASTERED；
- Level Upgrade。

---

## 6. AI vs Java Authority / AI 与 Java 权限边界

项目核心原则：

### LLM 负责

- soft decision；
- diagnosis；
- evidence；
- candidate；
- natural-language reasoning；
- semantic evaluation。

### Java 负责

- hard constraint；
- validation；
- permission；
- aggregation；
- deterministic state transition；
- persistence authority。

LLM 可以：

- 推荐；
- 诊断；
- 分类候选；
- 提供 Evidence；
- 判断自然度；
- 生成结构化候选输出。

Java 应负责：

- Schema validation；
- Enum validation；
- hard constraints；
- permission；
- state transition；
- aggregation rules；
- long-term state qualification；
- persistence decision。

如果准备把 deterministic business rule 移入 Prompt：

这是 Architecture-sensitive Change。

必须先说明原因、替代方案和 trade-off。

---

## 7. Language Profile Boundary / Language Profile 边界

`Language Profile` 是：

**当前长期能力状态的 compact summary。**

它不是：

**完整学习历史数据库。**

Profile 可以包含：

- overall level；
- skill level；
- goals；
- main weaknesses；
- vocabulary summary；
- communication summary；
- recent state；
- preferences；
- evidence sufficiency。

不要把以下内容大量塞入 Profile：

- 完整 Conversation；
- Raw Error History；
- 完整 Practice History；
- 全部 Evidence；
- 完整 Memory Episode。

这些内容应属于：

- Learning Memory；
- Practice History；
- RAG / Retrieval；
- 其他历史数据模块。

---

## 8. Planner Boundary / Planner 边界

Planner 负责回答：

> 今天练什么？为什么？难度是多少？有什么约束？

主要输入包括：

- Profile；
- Active Weakness；
- Due Review；
- Recent Practice；
- Repeated Errors；
- Goals；
- Available Time；
- User Feedback；
- Evidence Sufficiency。

主要输出包括：

- LearningTask；
- Learning Intent；
- Difficulty；
- Constraints；
- Reason。

Planner 可以进行个性化 soft decision。

硬约束继续由 Java 控制。

### Planner MUST NOT

Planner 不负责：

- 生成完整学习材料；
- 执行完整 Conversation；
- 评分；
- 直接写 Weakness；
- 直接修改 Level；
- 直接修改 Learning Memory。

用户应能够：

- Skip；
- Replace；
- Easier；
- Harder；
- Change Topic；
- Replan。

Planner 推荐训练意图。

具体 topic / material 应保留合理用户选择权。

---

## 9. Practice Boundary / Practice 边界

Practice 是产生真实学习行为和 Evidence 的地方。

可能包括：

- Conversation；
- Reading；
- Vocabulary；
- Review；
- Grammar Repair；
- Listening；
- Writing；
- Language Fundamentals。

不同 Practice 应尽可能产生统一、可聚合的学习 Evidence。

不要为了某个 Practice 单独建立一套完全无法进入 Learning Memory 的长期状态体系。

---

## 10. Conversation Boundary / Conversation 边界

Conversation 重点训练：

- grammar；
- naturalness；
- task completion；
- communication；
- fluency。

尤其关注：

- follow-up；
- clarification；
- topic initiation；
- topic shift；
- elaboration；
- help request；
- register；
- compensation strategy。

Conversation 不应退化成：

    用户说一句
    ↓
    AI 批改一句 Grammar
    ↓
    再说一句

默认减少实时打断。

Evaluation 通常在适当节点或 Session 后完成。

---

## 11. Evaluator Boundary / Evaluator 边界

Evaluator 负责：

> 对一次 `PracticeSession` 产生结构化诊断 Evidence。

典型输入：

- PracticeSession；
- LearningTask；
- compact Profile；
- task-specific Rubric。

Evaluator 不应默认读取全部长期 Memory。

### 11.1 Evaluator Output

可以产生：

- taskCompletion；
- strengths；
- detectedIssues；
- vocabularyResults；
- communicationResults；
- independenceLevel；
- overallPerformance；
- confidence；
- 对应 Evidence。

### 11.2 Evaluator MUST NOT

Evaluator 可以说：

`本次出现 QUESTION_WORD_ORDER`

但不能直接决定：

`用户长期 Weakness = QUESTION_WORD_ORDER`

Evaluator 不直接：

- activate Weakness；
- change Level；
- mark long-term Mastery。

低置信结果应保留为 Candidate 或 Session-level information。

Evaluation 失败时：

    保留 Practice
    +
    不污染长期状态

---

## 12. Learning Memory Boundary / Learning Memory 边界

Learning Memory 是 Persistent Learner Model 的核心。

采用概念上的三层结构。

### A. Raw Evidence

例如：

- ErrorEvent；
- PracticeResult；
- UserFeedbackEvent；
- Success Evidence；
- Communication Evidence。

### B. Aggregated Memory

例如：

- frequency；
- recent frequency；
- trend；
- cross-scenario evidence；
- success/failure balance；
- independence information。

### C. Long-term State

例如：

- Weakness；
- SkillMastery；
- CommunicationSkill；
- VocabularyMastery。

整体关系：

    Raw Evidence
         ↓
    Aggregated Memory
         ↓
    Long-term State

系统必须同时记录：

    错误 Evidence
    +
    正确 Evidence

不能只记录用户失败。

---

## 13. Weakness Rules / Weakness 规则

Weakness 不应由简单固定规则决定，例如：

`错 3 次 = Weakness`

判断应综合考虑：

- frequency；
- recency；
- cross-scenario；
- confidence；
- correct evidence；
- independence；
- practice type。

Weakness lifecycle：

    CANDIDATE
       ↓
     ACTIVE
       ↓
    IMPROVING
       ↓
    INACTIVE

再次出现时可以：

    INACTIVE
       ↓
    REACTIVATED

Severity 与 Confidence 是两个独立概念。

不要混成一个 score。

---

## 14. Transfer Matters / 真实迁移优先

专项练习成功：

`Repair Success`

不等于长期能力已经掌握。

真实场景中的：

`Transfer Success`

通常应具有更高 Evidence 权重。

例如：

Grammar micro exercise 做对，

不能自动关闭 Weakness。

后续 Conversation / Writing 中自然正确使用更有长期价值。

---

## 15. Review vs Planner / Review 与 Planner

职责保持分离：

    Learning State
    → 掌握得怎样？

    Review System
    → 什么值得再次出现？

    Planner
    → 今天是否练，以及用什么方式练？

不要让 Review Scheduler 同时承担完整 Planner 职责。

不要让 Planner 自己维护独立 mastery truth。

---

## 16. RAG Boundary / RAG 边界

RAG 用于：

- semantic retrieval；
- relevant historical episodes；
- content retrieval；
- imported material retrieval；
- personal memory retrieval。

RAG 不取代结构化数据库状态查询。

例如：

- Current Weakness Status；
- Current Level；
- Vocabulary Mastery；
- Permission；
- Language Profile。

这些优先通过确定性的 structured state / service 获取。

### RAG Result Is Context

Retrieval Result 是：

`Context`

不是长期状态真相。

禁止：

    RAG retrieved:
    "user struggled with article"
            ↓
    直接设置
    ARTICLE_USAGE = ACTIVE

必须经过 Evidence / Memory aggregation。

---

## 17. Context Manager Boundary / Context Manager 边界

不同 Agent / Task 只获得完成当前任务需要的 Context。

默认目标：

    Recent Context
    +
    Structured Summary
    +
    Relevant Retrieved Context
    +
    Task-specific State

避免：

`把完整历史全部塞入 Prompt`

Context Strategy 应考虑：

- relevance；
- token budget；
- freshness；
- provenance；
- privacy；
- language isolation。

---

## 18. Tool Gateway Boundary / Tool Gateway 边界

Agent 不应直接获得任意 Application / Database 写权限。

Tool 调用统一通过明确 Tool Contract。

Tool Gateway 根据具体 Tool 风险承担适用的：

- schema validation；
- authentication；
- authorization；
- permission；
- timeout；
- retry；
- idempotency；
- trace。

并非所有 Tool 都必须机械实现全部策略。

### 18.1 State Mutation

Agent 应优先：

- submit evidence；
- request action；
- invoke controlled tool。

避免：

- 直接修改 Level；
- 直接修改 Weakness；
- 直接写任意 DB state。

新增高风险 Tool 属于 A 类 / Architecture-sensitive Change。

---

## 19. Model Gateway Boundary / Model Gateway 边界

项目保持：

**provider-agnostic。**

业务模块不得直接绑定：

- OpenAI-specific SDK/client；
- DeepSeek-specific SDK/client；
- Gemini-specific SDK/client；
- 其他 concrete provider implementation。

如果已经存在 `ModelGateway` / Provider Adapter boundary：

所有通用模型调用继续经过该边界。

目标可以支持：

- DeepSeek；
- OpenAI；
- Gemini；
- Ollama；
- OpenAI-compatible providers。

业务代码应面向：

`task capability / model abstraction`

而不是具体供应商。

---

## 20. Structured Output / 结构化输出

用于业务状态的 LLM Output 必须经过适当：

- Schema Validation；
- Enum Validation；
- Semantic Validation；
- Qualification。

Structured Output 最终验证失败：

不得污染 Persistent Learning State。

不要因为模型通常能返回正确 JSON 就绕过校验。

---

## 21. BYOK Boundary / BYOK 边界

项目采用：

**BYOK-first。**

Hosted Mode 默认安全边界：

    API Key
      ↓
    浏览器本地
      ↓
    HTTPS 临时传给 Backend
      ↓
    Provider

Backend 在执行模型调用时可能瞬时接触 Credential。

但默认禁止持久化到：

- PostgreSQL；
- Redis；
- Trace；
- Log。

Self-hosted Mode 可以支持：

- browser local；
- local Secret；
- `.env`。

改变 Credential persistence model 属于 Security Architecture Change。

---

## 22. Secrets & Trace / Secret 与 Trace

禁止在以下位置记录 API Key 或其他 Secret：

- logs；
- trace；
- metrics；
- exception message；
- database；
- debug dump。

Trace 默认优先保存：

- metadata；
- IDs；
- versions；
- token count；
- latency；
- status。

生产环境不应无条件保存完整私密 Prompt / Conversation。

---

## 23. Trace / Observability

AI 关键路径应逐步具备可追踪性。

典型链路：

    Request
      ↓
    Planner
      ↓
    Context
      ↓
    RAG
      ↓
    LLM
      ↓
    Tool
      ↓
    Evaluator
      ↓
    Learning State Mutation

适合记录：

- traceId；
- agentType；
- taskType；
- provider/model；
- promptVersion；
- contextStrategyVersion；
- rubricVersion；
- tokens；
- latency；
- status；
- retryCount；
- selected context IDs；
- tool status；
- RAG retrieval metadata。

Trace 负责观察。

Trace 不负责修改学习状态。

---

## 24. Eval Boundary / Eval 边界

AI 行为变化不能只通过：

`看起来效果不错`

判断。

重要模块应逐步建立：

- Planner Eval；
- Evaluator Eval；
- Conversation Eval；
- RAG Eval；
- Tool Eval；
- Context Eval；
- Difficulty Eval。

确定性约束优先 Rule-based Eval。

开放性的自然度问题才考虑 LLM-based evaluation。

Prompt / Model / Rubric / Context Strategy 发生重要变化时，应考虑 Regression Eval。

---

## 25. Language-specific AI Design / 语言特定设计

不要建立大量散落的：

    if (language == JAPANESE) ...
    else if (language == SPANISH) ...

语言特定差异优先通过类似以下结构集中管理：

- LanguageConfig；
- promptSet；
- evaluationTaxonomy；
- writingSystem；
- pronunciationConfig；
- difficultyFramework；
- fundamentalsConfig。

共享：

- architecture；
- workflow；
- contracts。

语言差异放入：

`language-specific configuration / prompt / taxonomy`

---

## 26. Hosted + Self-hosted / 双部署模式

项目使用同一套核心代码支持：

- Hosted Mode；
- Self-hosted Mode。

核心架构方向：

    Vue / PWA
        ↓
    Spring Boot
        ↓
    PostgreSQL + pgvector
    Redis
    Object Storage
        ↓
    User-configured AI Providers

不要为 Hosted 和 Self-hosted 建立两套分叉业务逻辑。

环境差异优先放入：

- configuration；
- deployment；
- adapter；
- infrastructure boundary。

---

## 27. Frontend / Backend Authority

Vue / PWA 可以负责：

- UI state；
- local cache；
- offline UX；
- browser-local credential；
- recent/detail session cache。

Spring Boot 负责核心：

- domain rules；
- authorization；
- long-term learning state；
- aggregation；
- persistence coordination；
- AI orchestration boundary。

不要让前端成为长期学习状态的唯一 authority。

Hosted Mode 不能假设所有核心数据只存在浏览器。

---

## 28. V1 Scope Discipline / V1 范围纪律

Detailed Function Design 描述长期完整设计。

它不代表所有功能当前都需要实现。

判断：

`当前 Phase / V1 是否实现？`

必须优先读取：

1. Current approved Feature / Phase decision；
2. V1 Scope；
3. Full Backlog Baseline；
4. Detailed Function Design。

不要因为某功能存在于 Detailed Design 或 Backlog：

`→ 自动实现`

### 28.1 Backlog Preservation

未进入当前 V1 / Phase 的能力：

可以延期。

但不要：

- 静默删除设计；
- 静默宣布“不需要”；
- 因为当前没做就从长期设计中移除。

需要改变长期产品方向时：

明确提出 Product / Architecture Decision。

---

## 29. Current High-value Engineering Modules / 核心工程模块

以下模块默认具有较高 Architecture / Interview Value：

- Planner；
- Conversation Runtime；
- Evaluator；
- Learning Memory；
- Weakness / Skill Aggregation；
- Content Pipeline；
- RAG / Retrieval；
- Tool Gateway；
- Context Manager；
- Model Gateway / BYOK；
- Structured Output；
- Trace / Observability；
- Eval；
- Language Isolation。

涉及这些模块的非机械修改：

默认优先按：

`A — Critical`

处理。

简单 DTO、Mapper、样板 Test 等仍可单独归为 B/C。

---

## 30. Architecture Drift Red Flags / 架构漂移警报

发现以下设计时暂停并检查：

- Planner 直接修改 Memory；
- Evaluator 直接 activate Weakness；
- LLM 直接决定长期 Level；
- Agent 直接写数据库；
- 业务 Service 直接依赖具体 Model Provider；
- RAG Result 被当作长期状态事实；
- 不同语言共享 Weakness / Evidence；
- API Key 写入 DB / Redis / Trace / Log；
- Conversation 退化成逐句 Grammar correction；
- Grammar 逐渐成为主要学习路径；
- 完整长期历史无差别塞入每次 Prompt；
- 为简单 Feature 新增多个 Agent；
- Hosted / Self-hosted 分叉两套核心业务代码。

这些通常属于 Architecture-sensitive Issue。

不要静默实现。

---

## 31. Documentation Source of Truth / 文档优先级

当前任务需要判断产品范围或架构时，优先检查：

1. Current approved Phase / Feature decision；
2. V1 Scope & Full Backlog；
3. Detailed Function Design；
4. Core User Flow；
5. PRD。

这不是自动覆盖规则。

如果文档之间出现真实冲突：

    STOP
    ↓
    指出冲突
    ↓
    给出具体出处
    ↓
    请求或提出人工决策

禁止 Codex 自己偷偷选一个版本。

---

## 32. Documentation Update Rule / 文档更新规则

代码发生已批准的重大变化时，检查是否影响：

- `SYSTEM_OVERVIEW.md`；
- `MODULE_MAP.md`；
- `DATA_FLOW.md`；
- `AGENT_FLOW.md`；
- ADR；
- Feature Dossier。

普通小修改不需要更新所有文档。

文档应描述：

**真实当前 Architecture。**

不能长期保留已经不存在的理想设计。

---

## 33. Development Backlog Rule / 开发过程待办规则

开发过程中，如果发现与当前 Task 无关、但值得后续保留的内容，例如：

* Product Idea；
* UX Improvement；
* AI Engineering Enhancement；
* Backend / Frontend Improvement；
* Refactor Opportunity；
* Technical Debt；
* Performance Idea；
* Security Improvement；
* Future Architecture Option；
* Documentation Improvement；

不得因为“顺手可以做”自动扩大当前 Scope。

默认处理流程：

```
Detect
  ↓
Report
  ↓
Record in docs/planning/BACKLOG.md
  ↓
Continue Current Approved Task
```

### 33.1 Default Backlog State / 默认状态

新发现的 Backlog Item 默认：

```
Status: INBOX
Priority: UNASSESSED
Target: UNDECIDED
```

写入 `BACKLOG.md` 仅代表：

> 该想法值得保留并等待后续决策。

它不代表：

* 已进入 V1；
* 已进入当前 Phase；
* 已批准实现；
* 已批准 Architecture Change；
* Codex 可以自动开始开发。

---

### 33.2 Scope Promotion / 进入正式 Scope

Backlog Item 只有经过明确 Scope Decision 后，才能进入：

```
INBOX
  ↓
TRIAGED
  ↓
PLANNED
```

如果正式进入某个 Phase / Feature，应同步相应开发计划。

如果决定影响正式产品范围，还应检查是否需要同步：

* V1 Scope；
* Detailed Function Design；
* Phase Plan。

如果决定改变 Architecture Contract，还应检查是否需要：

* Architecture Change Proposal；
* ADR；
* `SYSTEM_OVERVIEW.md`；
* `MODULE_MAP.md`；
* `DATA_FLOW.md`；
* `AGENT_FLOW.md`。

不要为了一个 Backlog Item 自动更新所有文档，只同步真实受影响的内容。

---

### 33.3 Backlog Capture Command / 快速记录行为

当用户表达类似：

> 记一个 backlog：……

> 这个以后可以做。

> 先记下来，当前不实现。

> 这个点后面优化。

默认执行：

1. 读取 `docs/planning/BACKLOG.md`；
2. 获取下一个可用 `IDEA-XXX`；
3. 根据用户提供的信息填写条目；
4. 不确定的信息标记为 `UNKNOWN` / `UNASSESSED` / `UNDECIDED`；
5. 追加条目；
6. 不修改 Production Code；
7. 不自动扩大 Current Task；
8. 不自动改变 V1 Scope。

如果用户只是提出一个想法，但是否希望保存并不明确：

先报告该想法属于 Current Scope 外内容，再询问是否记录到 Backlog。

---

### 33.4 Technical Debt / 技术债

开发过程中发现明确存在、但当前决定暂不处理的工程问题，可以记录为：

```
Type: TECH_DEBT
```

Technical Debt 与 Future Idea 必须区分。

Technical Debt 表示：

> 当前实现已经存在已知不足或 trade-off。

Future Idea 表示：

> 当前实现不一定存在问题，只是未来可能增强。

不得把所有未来增强都描述成 Technical Debt。

---

### 33.5 Backlog Is Not a Second PRD / Backlog 不替代正式设计

`BACKLOG.md` 是开发过程中的动态捕获层。

它不替代：

* PRD；
* Detailed Function Design；
* V1 Scope；
* Architecture Docs。

当一个 Backlog Item 成为正式 Product / Architecture Decision 后，应把最终决定同步到对应正式文档。

Backlog 条目保留决策轨迹，但不成为长期唯一 Source of Truth。

---

## 34. Repository Navigation / 项目导航

如果以下文件存在：

- `docs/architecture/SYSTEM_OVERVIEW.md`
- `docs/architecture/MODULE_MAP.md`
- `docs/architecture/DATA_FLOW.md`
- `docs/architecture/AGENT_FLOW.md`
- `docs/adr/`
- `docs/features/`
- `docs/ownership/`

探索代码时优先用它们定位。

随后通过实际 Source Code 验证。

不要为了一个局部 Task 重新读取全部产品设计文档。

---

## 35. Architecture Change / 架构修改

以下项目级核心原则不得作为普通 implementation detail 修改：

- Persistent Learner Model Loop；
- Multi-language Isolation；
- AI vs Java Authority；
- Planner Responsibility；
- Evaluator Responsibility；
- Learning Memory Ownership；
- Tool Permission Boundary；
- Model Gateway Boundary；
- Credential Persistence Model；
- Hosted + Self-hosted Architecture。

若确实需要改变：

    Architecture Change Proposal
    ↓
    Alternatives
    ↓
    Trade-offs
    ↓
    Impact
    ↓
    Human Approval

随后根据需要记录 ADR。

---

## 36. Final Project Rule / 最终项目规则

每次核心功能开发都应避免两个方向。

### Product Drift

项目逐渐变成：

`普通语言学习 App`

### Agent Drift

项目逐渐变成：

`所有事情都让 LLM 决定`

本项目的核心价值来自：

    真实语言使用
    +
    Persistent Learner State
    +
    Evidence-driven Adaptation
    +
    Controlled Agent Runtime
    +
    可验证的 AI Engineering

如果实现方案削弱这些核心边界：

**STOP AND REVIEW。**