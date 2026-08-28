# AI Language Tutor — V1 Scope

> Status: APPROVED  
> Version: 1.3
> Approved: 2026-08-21
> Authority: Product Scope Baseline

## 1. Purpose

本文件记录 V1 已批准的范围和边界，是后续 Phase Planning、Feature Design 与实现判断的正式依据。

早期讨论材料继续保留在 `docs/product/drafts/2026-08-20/`，用于追溯设计背景；它们不是当前实现范围的直接授权。

## 2. Source of Truth Priority

发生范围或架构判断时，按以下顺序检查：

1. 当前已批准的 Phase / Feature decision；
2. 本文件；
3. `Language_Tutor_Detailed_Function_Design_v0.1.docx`；
4. `AI Language Tutor — Core User Flow v0.1.md`；
5. `AI Language Tutor — PRD v0.1.md`。

若文档之间存在真实冲突，停止实现并提交人工决策，不由 Coding Agent 静默选择。

## 3. V1 Product Outcome

### 3.1 Product Audience

本产品面向希望将目标语言用于日常生活、工作或持续学习的成年非应试型学习者。

用户可以从零基础开始，也可以已经具备一定词汇、语法、阅读、听力或表达基础。基础水平不是不同产品人群的分界，而是每个 `Language Profile` 的初始学习状态。系统通过用户自评、Initial Assessment 与后续 Practice Evidence 建立并持续修正该状态。

不同基础水平共用同一核心学习闭环，但 Planner 应根据当前能力选择不同的任务类型、难度与支架强度：

- 零基础或接近零基础用户从 Language Fundamentals、recognition、imitation 与受控的 micro communication 开始；
- 已有基础但难以听辨自然表达或开口沟通的用户，从适当难度的 Listening、Conversation 与 Communication Practice 开始；
- 用户在不同能力维度上可以处于不同阶段，例如 Reading 已能独立使用，但 Listening 仍需要较强支架；
- 所有路径最终都以在真实场景中更独立地理解、回应并持续沟通为目标。

本节定义长期一致的 Product Audience 与学习模型原则，不自动授权 V1 完整实现零基础课程体系。V1 支持的具体起始能力范围、Bootstrap 深度以及 Listening 进入哪个 Phase，仍需通过后续 Delivery Scope Decision 明确。

### 3.2 Product North Star and Success Model

产品的长期 North Star 是：

> 帮助用户把已经接触或正在学习的语言知识，逐步转化为在新的日常生活或工作场景中听懂并更独立地完成沟通任务的能力。

内部可以将这一变化概括为：

```text
需要高度辅助
  → 使用更少辅助
  → 在新场景中独立沟通
```

产品成功分为三个相互关联、但不能互相替代的层次：

1. **Learning Outcome**：用户在新的相似场景中，能以更少的翻译、答案提示、重复播放或其他支架理解关键信息，并更独立地回应、追问、澄清、展开和完成沟通任务；
2. **Product Value Signal**：用户认为系统识别的问题和后续 Practice 具有针对性，能够理解 Planner 为什么推荐当前任务，并感受到系统比一次性 AI Chat 更持续地理解自己的学习状态；
3. **System Quality**：Evidence、language isolation、structured output validation、deterministic state transition、failure isolation、credential boundary 与 trace metadata 等工程约束正确运行。

三者关系为：

```text
System Quality
  → Product Adaptation Value
  → Learning Outcome
```

System Quality 是必要条件，但不能单独证明产品成功。任务数量、学习时长、打卡天数、单次语言正确率或单次 Practice 完成，不作为核心 Learning Outcome。

V1 不要求证明长期流利度或完整等级提升，但至少需要形成以下 Product Proof：

1. Practice 产生可解释的 Communication Evidence；
2. 系统据此形成具有明确 confidence 与 evidence sufficiency 的当前能力假设；
3. 下一次 Practice 因该学习状态产生有意义的调整；
4. 用户在相关任务中表现出辅助依赖下降；
5. 至少在一个未直接重复训练脚本的新场景中观察到初步 Transfer signal。

具体 Metric、实验设计与数值阈值由后续 Product Validation Decision 单独确定，不由本节提前假设。

### 3.3 Project Goal Priority

项目采用 Product-first、Engineering Evidence secondary 的双轨目标。

**Primary Product Goal**

构建并验证一个能够根据持续 Practice Evidence，帮助用户减少辅助依赖并提升真实沟通独立性的语言学习产品。项目作者作为首位长期 dogfooding 用户，通过真实学习过程发现问题、提供 Product Evidence，并验证系统是否产生具有实际价值的适应性训练。

个人 dogfooding 可以证明产品服务了至少一个真实用户，并为 Learner Model、Planner、Evaluator 与 Practice 设计提供持续反馈；但 `N=1` 的个人改善不能被表述为产品已经具备普遍学习有效性。面向更广泛用户的结论仍需独立 Product Validation。

**Secondary Engineering Goal**

通过同一真实产品闭环，形成可验证的 Engineering Evidence，展示 Persistent Learner Model、controlled Agent Runtime、Structured Output、Model Gateway、Evaluation、Observability、hallucination risk control、strategy reliability 与 AI Reliability 等工程能力。

Engineering Evidence 应优先来自真实 Product Problem、failure path、Test、Eval、Trace、Architecture Decision 与可解释 trade-off，而不是来自功能数量或技术关键词堆叠。

**Conflict Rule**

当 Product Value 与 Engineering Showcase 发生 Scope 冲突时：

1. 优先选择能够验证用户学习价值的最小实现；
2. 纯展示性技术不自动进入 V1 Product Scope；
3. 与当前 Product Loop 无直接关系、但值得保留的 Engineering Experiment 进入 Backlog，等待独立 Scope Decision；
4. 不得仅为了展示 Agent、RAG、Tool Calling、Voice、Model Routing 或其他技术而制造不必要的 abstraction、workflow 或 product capability；
5. 面试展示以核心调用链的可运行性、可靠性、失败隔离、验证证据和 Human Ownership 为主要标准。

### 3.4 Minimum Learning Loop

V1 必须形成一个可验证的最小学习闭环：

```text
Language Profile
  → Planner
  → Practice
  → Evaluator
  → Evidence / Learning Memory
  → Deterministic Profile Update
  → Re-planning
```

V1 的成功不以功能数量为中心，而以以下能力为中心：

- 用户能在独立的目标语言 workspace 中完成真实练习；
- 系统能保留错误与正确 Evidence；
- 长期状态由聚合规则决定，而不是由单次 LLM 输出直接决定；
- 下一次训练会使用已经形成的学习状态；
- Hosted 与 Self-hosted 共用同一套核心业务逻辑；
- Model 调用保持 provider-agnostic 与 BYOK-first。

## 4. Approved Architecture Baseline

### 4.1 Long-term State Authority

- Hosted Mode 的长期学习状态由 Spring Boot 与 PostgreSQL 管理；
- IndexedDB 只承担 local cache、offline UX 与 pending event；
- 浏览器数据不得成为 Hosted Mode 的唯一长期状态 authority；
- full offline sync 与 device conflict merge 不进入 V1。

### 4.2 Agent Mutation Boundary

- Agent 可以提交结构化 Evidence 或请求受控 action；
- Agent 不得直接执行 `setLevel`、`setWeakness` 或 mastery mutation；
- Java 负责 validation、qualification、aggregation、state transition 与 persistence decision。

### 4.3 Bounded AI Dependency

Learning Workflow 与长期学习状态正确性不能把 LLM availability 作为前提：

- Planner 先由 Java 产生并过滤合法 candidate，再由可选 LLM 完成 soft ranking、scenario 与
  reason enrichment；Model 不可用时必须存在合法的 deterministic fallback；
- Practice evaluation 区分 trusted event 产生的 deterministic assessment，与 LLM 产生并需经过
  validation 的 semantic candidate；
- Model failure 只阻止 model-derived Evidence，不删除已确认的 deterministic assessment / Evidence；
- persistence integrity、language isolation、Evidence processing、state replay 与 session recovery
  不依赖 LLM availability。

详细决策见 `ADR-0003`。

### 4.4 BYOK Credential Boundary

Hosted Mode 的 Credential 路径固定为：

```text
Browser local/session storage
  → HTTPS transient transfer
  → Backend transient use
  → Model Provider
```

API Key 不得持久化到 PostgreSQL、Redis、Trace 或 Log。

### 4.5 Documentation Drift Resolution

早期 PRD / User Flow 中与当前边界不一致的描述，由以下当前基线约束：

- repository `AGENTS.md`；
- `docs/architecture/` 下的 Architecture Docs；
- Detailed Function Design；
- 本 V1 Scope；
- 当前已批准的 Phase / Feature decision。

本项只解决实现解释权，不删除或改写原始 draft。

## 5. Final Decisions for Previously Pending Modules

| Module | V1 decision | Target Phase | Boundary |
| --- | --- | --- | --- |
| 16 Progress | P1 | M2 | 只做长期学习状态的 read-only projection，不建立第二套 mastery truth |
| 17 Continuous Assessment | Split | M2 / M4 | P0 core assessment 在 M2；Milestone Check 在 M4；advanced mechanisms 进入 Backlog |
| 18 Practice Feedback | P1 | M2 | 只做 lightweight feedback；advanced dispute / appeal 机制延期 |
| 34 Notifications / Learning Recall | Backlog | V1 之后 | V1 不实现 push notification 或 scheduler |

## 6. V1 Included Capability Groups

### P0 — Core Loop

- User 与 Language Profile 基础能力；
- 多目标语言学习状态硬隔离；
- provider-agnostic Model Gateway；
- BYOK transient credential handling；
- Java candidate / hard constraint + optional LLM enrichment 的最小 Planner 输出；
- Text Practice / Conversation 的最小闭环；
- deterministic assessment + validated semantic candidate 的 Session-level Evaluation；
- Raw Evidence、Aggregated Memory 与 Long-term State 的最小链路；
- Weakness / Skill State 的确定性 qualification 与 lifecycle；
- 基础 Review State 与 re-planning；
- Structured Output validation；
- 关键 AI 路径的最小 Trace / Observability；
- Hosted 与 Self-hosted 的可运行交付基线。

### P1 — V1 Completeness

- Progress read-only projection；
- lightweight Practice Feedback；
- Reading / imported content 的最小训练路径；
- 基础 Content retrieval / RAG，且 Retrieval Result 只作为 Context；
- Milestone Check；
- Listening 与 turn-based Voice 的受控最小能力；
- 必要的 PWA / offline UX，但不包含完整跨设备同步。

## 7. Explicitly Outside V1

- push notification 与学习召回 scheduler；
- full offline sync 与 device conflict merge；
- advanced assessment mechanisms；
- advanced feedback dispute / appeal；
- realtime full-duplex voice；
- 由 LLM 直接修改长期 Level、Weakness 或 Mastery；
- 业务模块直接依赖具体 Model Provider SDK；
- 为 Hosted 与 Self-hosted 分叉两套核心业务代码；
- 与 Persistent Learner Model 闭环无关的扩展功能。

延期能力保留在 Backlog 或 draft 中，不因未进入 V1 而视为永久删除。

## 8. Delivery Phases

| Phase | Name | Primary outcome |
| --- | --- | --- |
| M0 | Engineering Foundation & Language Workspace | 建立可运行工程、状态 authority、语言隔离与 Model/BYOK 边界 |
| M1 | Minimum Text Practice Loop | 跑通一次 text practice 与 session-level evaluation |
| M2 | Persistent Adaptation Loop | Evidence 进入长期状态并影响下一次 Planner 决策 |
| M3 | Reading / Content / RAG | 内容驱动练习和受控 retrieval 进入统一 Evidence 链路 |
| M4 | Learning Completeness | 补齐 Milestone Check、Review 与 V1 学习完整性 |
| M5 | Listening / Turn-based Voice | 增加非实时的听说训练闭环 |
| M6 | V1 Hardening & Delivery | 完成 security、reliability、eval 与部署验收 |

具体 Gate、Done Criteria 与当前 Phase slices 见 `docs/planning/V1_PHASE_PLAN.md`。

## 9. Scope Change Rule

新增或改变 V1 能力时必须先分类：

- 普通 Feature：形成明确 Scope Decision；
- Architecture-sensitive Change：先提交方案、替代方案、trade-off 与影响范围，等待人工批准；
- 当前 Scope 外想法：记录到 `docs/planning/BACKLOG.md`，默认保持 `INBOX / UNASSESSED / UNDECIDED`。

未经批准，不得仅因 draft 中出现某项能力而自动实现。
