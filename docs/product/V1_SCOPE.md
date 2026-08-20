# AI Language Tutor — V1 Scope

> Status: APPROVED  
> Version: 1.0  
> Approved: 2026-08-20  
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

### 4.3 BYOK Credential Boundary

Hosted Mode 的 Credential 路径固定为：

```text
Browser local/session storage
  → HTTPS transient transfer
  → Backend transient use
  → Model Provider
```

API Key 不得持久化到 PostgreSQL、Redis、Trace 或 Log。

### 4.4 Documentation Drift Resolution

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
- Planner 的最小训练意图与约束输出；
- Text Practice / Conversation 的最小闭环；
- Evaluator 的结构化 Session-level Evidence；
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
