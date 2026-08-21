# AI Language Tutor — V1 Phase Plan

> Status: APPROVED  
> Version: 1.0  
> Approved: 2026-08-20  
> Scope baseline: `docs/product/V1_SCOPE.md`

## 1. Delivery Strategy

V1 按 M0–M6 顺序推进。每个 Phase 只在前一 Phase 的 exit criteria 通过后进入实现。

```text
M0 Foundation
  → M1 Minimum Practice
  → M2 Persistent Adaptation
  → M3 Content / RAG
  → M4 Learning Completeness
  → M5 Listening / Voice
  → M6 Hardening / Delivery
```

每个 implementation slice 均遵循：

```text
Scope
  → Implement
  → Verification
  → Diff Review
  → Human Ownership Check
  → Ready to Commit
```

完成一个 slice 后停止，不自动开始下一个 slice。

## 2. Phase Overview

### M0 — Engineering Foundation & Language Workspace

**Goal**

建立后续核心闭环所依赖的可运行工程边界，不实现完整学习体验。

**Done Criteria**

- frontend、backend 与 local infrastructure 可以按文档启动；
- User / Language Profile 的身份和语言隔离边界可验证；
- PostgreSQL 是 Hosted long-term state authority；
- Model 调用只经过 Model Gateway；
- BYOK Credential 不进入 DB、Redis、Trace 或 Log；
- Structured Output 的失败路径不会写入长期状态；
- 至少一条 walking skeleton 能验证请求、模型边界、validation 与 trace metadata。

### M1 — Minimum Text Practice Loop

**Goal**

让用户在一个 Language Profile 下完成一次最小 text practice，并得到 Session-level evaluation。

**Done Criteria**

- Planner 生成最小 LearningTask；
- 用户完成 text conversation / writing practice；
- Evaluator 生成经过 validation 的结构化诊断；
- Practice 与 Evaluation failure 被正确保存或隔离；
- Evaluator 不直接改变 Weakness、Level 或 Mastery。

### M2 — Persistent Adaptation Loop

**Goal**

让多次 Practice Evidence 形成长期状态，并影响下一次训练计划。

**Done Criteria**

- 正确与错误 Evidence 都被记录；
- Aggregated Memory 综合 recency、frequency、confidence、scenario 与 independence；
- Weakness / Skill State 由 Java 规则执行确定性 transition；
- Planner 使用 compact Profile、active state、due review 与 recent practice；
- Progress 是现有长期状态的 read-only projection；
- 支持 core continuous assessment 与 lightweight Practice Feedback；
- 不同 `languageProfileId` 的状态不可串用。

### M3 — Reading / Content / RAG

**Goal**

加入内容驱动的练习与检索，同时维持 structured state authority。

**Done Criteria**

- Reading / imported content 可以生成 LearningTask 与 Evidence；
- Retrieval 具备 language isolation、provenance 与基础 relevance metadata；
- RAG Result 只作为 Context，不直接成为长期状态事实；
- Content Practice 进入统一 Evaluation / Memory 链路。

### M4 — Learning Completeness

**Goal**

补齐 V1 的阶段性评估、Review 与学习路径控制能力。

**Done Criteria**

- Milestone Check 使用明确 rubric 和 evidence sufficiency；
- Review System 与 Planner 职责分离；
- 用户可以 Skip、Replace、Easier、Harder、Change Topic 或 Replan；
- Grammar Repair 回到真实使用场景验证 transfer；
- advanced assessment mechanisms 仍留在 Backlog。

### M5 — Listening / Turn-based Voice

**Goal**

在现有闭环上增加可控、可评估的听说练习。

**Done Criteria**

- Listening / turn-based Voice 复用 Language Profile、Planner、Evaluator 与 Memory 边界；
- 音频失败、超时与重试不污染长期状态；
- Voice 不绕过 Tool / Model Gateway；
- realtime full-duplex voice 不进入 V1。

### M6 — V1 Hardening & Delivery

**Goal**

完成 V1 的安全、可靠性、Eval、部署和 ownership 验收。

**Done Criteria**

- 关键路径具备 targeted regression eval；
- secret leakage、language isolation 与 state mutation boundary 有自动化验证；
- timeout、retry、idempotency 与 failure recovery 按实际 Tool 风险覆盖；
- Hosted / Self-hosted 使用同一核心业务代码并可重复部署；
- Architecture Docs 与真实实现一致；
- 核心调用链完成人工 Ownership Check。

## 3. Current Phase: M0 Implementation Slices

M0 先拆为以下认知边界。每个 slice 开始前仍需确认具体 file scope 和 architecture-sensitive decision。

| Slice | Scope | Verification focus |
| --- | --- | --- |
| M0-S1 | Build 与 application skeleton | backend/frontend 能启动；无 Domain 行为 |
| M0-S2 | Local infrastructure baseline | PostgreSQL、pgvector、Redis 与配置边界可启动 |
| M0-S3 | User / Language Profile persistence identity | migration、repository boundary、`languageProfileId` 归属 |
| M0-S4 | Authentication / UserContext boundary | request identity 不可越权访问其他 profile |
| M0-S5 | Language workspace minimum use case | create/list/switch profile；语言状态硬隔离 |
| M0-S6 | Model Gateway contract | 业务代码不依赖 concrete provider；timeout/error contract 明确 |
| M0-S7 | BYOK transient credential path | Credential 不持久化、不进入 logs/traces/exceptions |
| M0-S8 | Structured Output 与 minimal Trace walking skeleton | invalid output 不落长期状态；metadata 可追踪 |

### M0 Slice Control

- 一个 slice 默认控制在主要 production files `≤ 5`、production changed LOC `≤ 250`；
- 如果实现无法在认知预算内完成，继续拆分，不自动扩大范围；
- 新 dependency、public contract、schema 或核心 abstraction 仍需先经过 Architecture Decision；
- M0-S1 application skeleton、Java 25 原生环境验证、Diff Review 与 Human Ownership Check 已完成。

## 4. Later-phase Planning Rule

M1–M6 当前只批准 Goal、顺序与 exit criteria，不预先生成详细 implementation tasks。

当某个 Phase 即将开始时，只根据已经存在的真实代码和前一 Phase 结果拆分 slices，避免 speculative abstraction 与过早计划漂移。

## 5. Gate Status

```text
Design: APPROVED
M0-S1 Scope: APPROVED
M0-S1: COMPLETE
M0-S2 Scope: APPROVED
M0-S2 Implementation: COMPLETE
M0-S2 Review: COMPLETE
M0-S2 Ownership Check: COMPLETE
M0-S2: COMPLETE
M0-S3 Scope: APPROVED
M0-S3 Implementation: COMPLETE
M0-S3 Verification: COMPLETE
M0-S3 Review: COMPLETE
M0-S3 Ownership Check: COMPLETE
M0-S3: COMPLETE
```

`M0-S3` 已完成 implementation、focused verification、Diff Review 与 Human Ownership Check。本次 commit 后停在 `M0-S3`；只有经过新的 Scope Review 才进入 `M0-S4`。
