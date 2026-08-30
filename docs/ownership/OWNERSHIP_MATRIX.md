# AI Language Tutor — Ownership Matrix

> 本文件记录用户对 AI Language Tutor 各核心模块的真实 Code Ownership（代码掌握程度）。
>
> 目标：
>
> * 防止项目变成“Codex 写完了，但自己不知道代码怎么工作”；
> * 让 Phase Closeout 能发现 Black Box Area（黑箱区域）；
> * 将代码 Review 转化为持续的项目理解；
> * 明确哪些模块已经可以用于面试讲解，哪些仍只能说“我知道它存在”；
> * 帮助决定 Human Touch、Debug、Refactor 和 Interview Practice 应优先放在哪里。
>
> 本文件记录的是：
>
> **真实掌握程度。**
>
> 不是：
>
> **功能完成程度。**
>
> 一个 Feature 可以：
>
> ```
> IMPLEMENTED
> ```
>
> 但 Ownership 仍然只有：
>
> ```
> L1 — Located
> ```

---

# 1. Ownership Levels / 掌握等级

## L0 — Unseen / 未真正接触

表现：

* 没有认真读过实现；
* 只知道模块名称；
* 主要理解来自 PRD / Architecture Docs；
* 无法根据真实代码说明入口和调用链。

可以回答：

> “这个模块在系统里负责什么。”

但不能可靠回答：

> “代码是怎么实现的？”

---

## L1 — Located / 能定位

表现：

* 知道代码在哪里；
* 知道主要入口；
* 能找到核心 Service / Domain / Test；
* 知道主要依赖模块。

可以回答：

> “Planner 的入口和主要代码在这里。”

但还不能完整跟踪执行过程。

---

## L2 — Traceable / 能跟调用链

表现：

* 能从入口跟到主要执行链；
* 知道数据从哪里来；
* 知道关键依赖；
* 知道主要状态在哪里读写；
* 能定位核心测试。

可以解释：

```
Request
→ Service
→ Domain
→ Model / Tool / Repository
→ Result
```

但设计 rationale 可能还不够清楚。

---

## L3 — Explainable / 能解释

表现：

* 能独立解释模块目的；
* 能解释核心调用链；
* 能解释关键状态变化；
* 能指出重要代码位置；
* 能解释为什么采用当前设计；
* 能说出至少一个 trade-off；
* 能解释主要失败路径。

这是 A 类模块 Phase Closeout 的最低推荐目标。

---

## L4 — Operated / 实际操作过

表现：

在 L3 基础上，至少做过一种真实操作：

* 修改核心逻辑；
* 写关键 Test；
* Debug 实际问题；
* 改关键条件；
* 做有意义 Refactor；
* 分析真实 Trace；
* 修复 Regression。

不是只阅读。

Human Touch 通常用于推动：

```
L3
→ L4
```

---

## L5 — Defendable / 可应对深挖

表现：

* 可以脱离文档解释设计；
* 可以画出核心架构和数据流；
* 可以解释为什么没有使用另一种方案；
* 可以回答 Failure / Scale / Security / Consistency 等追问；
* 可以指出当前实现的局限；
* 可以提出合理演进方案；
* 能把代码实现和产品业务价值联系起来。

目标不是背答案。

目标是：

> 面试官改变问题角度后，仍然能从真实实现推导回答。

---

# 2. Important Distinction / 重要区分

必须区分：

```
Architecture Familiarity
≠
Code Ownership
```

例如：

已经讨论过 Learning Memory：

```
Evidence
→ Aggregation
→ Weakness
```

说明你理解设计方向。

但如果还没有：

* 阅读实际 aggregator；
* 跟踪 Evidence 写入；
* 看 State Transition；
* 看 Tests；
* Debug 一次真实变化；

则不能因此标记：

`L4`

---

# 3. Ownership Assessment Rule / 评定规则

Ownership 不能只根据：

* Codex Summary；
* ChatGPT Summary；
* Architecture Document；
* Feature 已完成；
* Test 通过；

自动升级。

升级必须存在实际证据。

---

## L0 → L1

需要：

* 找到实际代码路径；
* 找到主要入口；
* 确认主要模块位置。

---

## L1 → L2

需要：

* 至少跟踪一次真实核心调用链；
* 知道主要输入和输出；
* 知道关键 State Read / Mutation。

---

## L2 → L3

需要通过 Explain Back。

至少能够回答：

1. Entry Point 在哪里？
2. 主要 Call Chain 是什么？
3. 哪些 State 被读取或修改？
4. 为什么采用当前设计？
5. Failure 时会发生什么？

并能够指出实际代码位置。

### Explain Back Question Scope / 反向解释的提问边界

Explain Back 必须以已实现的真实代码为边界。提问前先判断行为流程的完成度，
不得要求用户通过未实现的设计、未接入的 dependency 或未决定的后续阶段猜测当前调用链。

#### 1. End-to-End Behavior Complete

当已存在真实入口、核心处理、实际 dependency 调用以及调用方可观测的结局时，
可以询问：

* 从真实入口到最终结果的完整 Call Chain；
* 关键 State Read / Mutation；
* 成功与已实现的 Failure Path；
* 已落地的设计理由和 trade-off。

#### 2. Module-local Behavior Complete

当 Module 从自身入口到 `ModuleResult` 的内部行为已实现，但真实上游、下游或
concrete adapter 尚未接入时，只能询问当前 Module 内的代码事实：

* 给定具体入口、参数和前置条件，会进入哪个方法或分支；
* 最终得到哪个具体返回值、异常或 Module-local state change；
* 返回对象的关键字段是什么；
* 当前代码在哪个外部边界结束。

不得把该局部调用链表述为完整业务流程，也不得询问尚未实现的 Credential、
Provider invocation、persistence 或业务降级行为。

#### 3. Contract-only / Placeholder

当当前 slice 只包含 interface、record、enum、sealed result、validation 或未可执行的
placeholder 时，不询问运行时调用链。只能确认：

* Contract 当前表达的业务语义；
* 哪些字段组合合法，哪些 invariant 会在构造时被拒绝；
* 当前明确已实现和未实现的能力边界。

不得要求用户解释一个尚未存在的执行流程或业务结局。

#### Question Precision / 提问精度

每个 Explain Back 问题应明确给出：

1. 正在检查的已实现范围；
2. 具体入口或方法；
3. 必要的输入与前置条件；
4. 希望回答的层级：下一个方法、具体分支、对象字段、异常、State Mutation 或最终业务结果。

一次只检查一个明确行为分支。“如何返回”、“流程是什么”等可能对应多个代码层级的
问题，必须改写为可通过当前代码唯一定位的问题。

如果问题越过已实现边界、未指明回答层级，或依赖未决定的未来设计，本次回答不得作为
Ownership 降级或 `PARTIAL` / `NOT_UNDERSTOOD` 的证据。应废弃问题，明确范围后重新评估。

---

## L3 → L4

至少完成一次有效 Human Touch：

* Core Logic Change；
* Key Test；
* Real Debug；
* Meaningful Refactor；
* Critical Condition Change。

---

## L4 → L5

需要：

* 独立架构解释；
* Trade-off；
* Failure Reasoning；
* Interview Deep Dive；
* 能回应替代方案。

通常不在单个 Feature 完成后立即标记。

需要多次实际操作和复盘。

---

# 4. Evidence / Ownership 证据

每次等级变化应尽量记录 Evidence。

例如：

```
Reviewed:
PlannerService.generatePlan()

Traced:
TodayController
→ PlannerApplicationService
→ PlannerAgent
→ ContextManager
→ ModelGateway

Human Touch:
Added duration hard constraint validation

Debugged:
Invalid planner structured output caused
fallback failure

Explain Back:
PASS
```

不要只写：

```
I understand Planner.
```

---

# 5. Core Ownership Matrix / 核心掌握矩阵

> 初始状态不得根据设计讨论自动推断。
>
> 在没有真实 source review 时使用 `UNASSESSED`。

| Module                        | Engineering Class | Current Ownership | V1 Target | Interview Target | Evidence |
| ----------------------------- | ----------------- | ----------------- | --------- | ---------------- | -------- |
| Language Management           | A                 | UNASSESSED        | L3        | L3               |          |
| Language Profile              | A                 | UNASSESSED        | L3        | L4               |          |
| Planner                       | A                 | UNASSESSED        | L3        | L5               |          |
| Practice Runtime              | A                 | UNASSESSED        | L3        | L4               |          |
| Conversation                  | A                 | UNASSESSED        | L3        | L5               |          |
| Reading                       | A                 | UNASSESSED        | L3        | L4               |          |
| Vocabulary / Vocabulary State | A                 | UNASSESSED        | L3        | L4               |          |
| Evaluator                     | A                 | UNASSESSED        | L3        | L5               |          |
| Learning Memory               | A                 | UNASSESSED        | L4        | L5               |          |
| Weakness / Skill State        | A                 | UNASSESSED        | L4        | L5               |          |
| Review                        | A                 | UNASSESSED        | L3        | L4               |          |
| Grammar Repair                | B                 | UNASSESSED        | L2        | L3               |          |
| Listening                     | B                 | UNASSESSED        | L2        | L3               |          |
| Language Fundamentals         | B                 | UNASSESSED        | L2        | L2               |          |
| Learning Preferences          | B                 | UNASSESSED        | L2        | L2               |          |

---

# 6. AI Engineering Ownership Matrix

这些模块是项目作为 AI Agent Engineering 求职项目的重要展示区域。

| Module                            | Engineering Class | Current Ownership | V1 Target | Interview Target | Evidence |
| --------------------------------- | ----------------- | ----------------- | --------- | ---------------- | -------- |
| Model Gateway                     | A                 | L2                | L4        | L5               | M0-S6F integrated closeout 确认 route、typed contract、Executor / Adapter、timeout 与 safe failure translation 可追踪；`ModelResult` / `ModelFailure` 包装关系仍需练习，无 concrete Provider execution evidence |
| Structured Output                 | A                 | UNASSESSED        | L4        | L5               |          |
| Tool Gateway                      | A                 | UNASSESSED        | L4        | L5               |          |
| Context Manager                   | A                 | UNASSESSED        | L4        | L5               |          |
| RAG / Retrieval                   | A                 | UNASSESSED        | L4        | L5               |          |
| Prompt Architecture               | A                 | UNASSESSED        | L3        | L4               |          |
| Prompt / Rubric / Context Version | A                 | UNASSESSED        | L3        | L4               |          |
| Trace / Observability             | A                 | UNASSESSED        | L3        | L5               |          |
| Eval System                       | A                 | UNASSESSED        | L3        | L5               |          |
| BYOK / Provider Configuration     | A                 | UNASSESSED        | L3        | L4               |          |
| Agent Loop Guardrails             | A                 | UNASSESSED        | L3        | L5               |          |
| Tool Permission / Allowlist       | A                 | UNASSESSED        | L4        | L5               |          |
| Memory Retrieval                  | A                 | UNASSESSED        | L3        | L4               |          |
| Background AI Task                | B                 | UNASSESSED        | L2        | L3               |          |

---

# 7. Content Engineering Ownership Matrix

| Module             | Engineering Class | Current Ownership | V1 Target | Interview Target | Evidence |
| ------------------ | ----------------- | ----------------- | --------- | ---------------- | -------- |
| Content Library    | B                 | UNASSESSED        | L2        | L3               |          |
| User Import        | A                 | UNASSESSED        | L3        | L4               |          |
| Content Pipeline   | A                 | UNASSESSED        | L4        | L5               |          |
| Dedup              | B                 | UNASSESSED        | L2        | L3               |          |
| Parse / Normalize  | B                 | UNASSESSED        | L3        | L3               |          |
| Chunking           | A                 | UNASSESSED        | L3        | L4               |          |
| Embedding          | A                 | UNASSESSED        | L3        | L4               |          |
| Retrieval Metadata | A                 | UNASSESSED        | L3        | L4               |          |
| Object Lifecycle   | B                 | UNASSESSED        | L2        | L3               |          |

---

# 8. Backend & Infrastructure Ownership Matrix

| Module                               | Engineering Class | Current Ownership | V1 Target | Interview Target | Evidence |
| ------------------------------------ | ----------------- | ----------------- | --------- | ---------------- | -------- |
| Spring Boot Application Architecture | A                 | UNASSESSED        | L4        | L5               |          |
| PostgreSQL Data Model                | A                 | UNASSESSED        | L3        | L4               |          |
| pgvector                             | A                 | UNASSESSED        | L3        | L4               |          |
| Flyway                               | B                 | UNASSESSED        | L3        | L3               |          |
| Redis                                | B                 | UNASSESSED        | L3        | L4               |          |
| Object Storage / MinIO               | B                 | UNASSESSED        | L3        | L3               |          |
| Background Job                       | B                 | UNASSESSED        | L3        | L4               |          |
| Account / Auth                       | A                 | UNASSESSED        | L3        | L4               |          |
| UserContext                          | A                 | UNASSESSED        | L4        | L5               |          |
| Security / Permission                | A                 | UNASSESSED        | L3        | L5               |          |
| Multi-language Isolation             | A                 | UNASSESSED        | L4        | L5               |          |
| Docker Compose                       | B                 | L3                | L3        | L4               | M0-S2 source review and explain-back: service purpose, PostgreSQL 18 volume layout, loopback exposure, host/container port mapping |
| Hosted / Self-hosted Config          | A                 | UNASSESSED        | L3        | L4               |          |
| Health Check                         | C/B               | UNASSESSED        | L2        | L2               |          |

---

# 9. Frontend Ownership Matrix

前端不是当前项目最主要的面试展示区域。

不需要把每个 Vue Component 都提升到 L4/L5。

| Module                    | Engineering Class | Current Ownership | V1 Target | Interview Target | Evidence |
| ------------------------- | ----------------- | ----------------- | --------- | ---------------- | -------- |
| Vue Application Structure | B                 | UNASSESSED        | L2        | L2               |          |
| Today UI                  | B                 | UNASSESSED        | L2        | L2               |          |
| Conversation UI           | B                 | UNASSESSED        | L2        | L3               |          |
| Reading UI                | B                 | UNASSESSED        | L2        | L2               |          |
| Settings / BYOK UI        | B                 | UNASSESSED        | L2        | L3               |          |
| IndexedDB / Local Cache   | B                 | UNASSESSED        | L2        | L3               |          |
| PWA                       | B                 | UNASSESSED        | L2        | L3               |          |
| Offline State             | B                 | UNASSESSED        | L2        | L3               |          |

原则：

前端应达到：

> 能理解、能修改、能 Debug 当前主要交互。

不需要为了求职项目把大量精力投入纯 UI Boilerplate Ownership。

---

# 10. Interview-critical Modules / 面试核心模块

以下模块最终优先争取：

`L5 — Defendable`

## Tier 1

* Planner；
* Evaluator；
* Learning Memory；
* Weakness / Skill Aggregation；
* Tool Gateway；
* Context Manager；
* RAG；
* Model Gateway。

这些模块共同构成：

```
Planner
  ↓
Practice
  ↓
Evaluator
  ↓
Evidence
  ↓
Memory
  ↓
Re-planning
```

以及：

```
Agent
  ↓
Context
RAG
Tool
Model
  ↓
Controlled Runtime
```

---

## Tier 2

优先达到 L4：

* Conversation；
* Language Profile；
* Multi-language Isolation；
* Structured Output；
* Security / UserContext；
* Trace；
* Eval；
* Content Pipeline；
* PostgreSQL / pgvector。

---

## Tier 3

达到 L2–L3 即可：

-普通 Vue UI；

* simple CRUD；
* simple Mapper；
* static configuration；
* basic Language Fundamentals；
* ordinary DTO；
* CSS；
* trivial helpers。

---

# 11. Black Box Detection / 黑箱检测

Phase Closeout 时检查：

> 哪些代码已经进入项目核心流程，但用户仍然只有 L0–L1？

如果满足：

```
Module = A
AND
Implementation = IMPLEMENTED
AND
Ownership <= L1
```

则标记：

`BLACK_BOX_HIGH`

---

如果：

```
Module = A
AND
Ownership = L2
AND
Phase Closeout
```

标记：

`OWNERSHIP_GAP`

目标：

在进入依赖该模块的下一阶段前，尽可能提升到：

`L3`

---

# 12. Ownership Debt / 掌握债务

如果为了开发速度暂时允许：

```
Feature Complete
+
Ownership < Target
```

需要记录：

`Ownership Debt`

格式：

## OD-XXX

**Module:**

TBD

**Current Level:**

TBD

**Required Level:**

TBD

**Reason:**

TBD

**Missing Understanding:**

TBD

**Recovery Action:**

TBD

**Must Resolve Before:**

TBD

Ownership Debt 不等于 Technical Debt。

Technical Debt：

> 代码本身存在已知问题。

Ownership Debt：

> 代码可以正常工作，但用户还没有真正掌握。

---

# 13. Human Touch Tracking / 人工操作记录

A 类核心 Feature 应尽量包含 Human Touch。

有效行为包括：

* 实现小型核心函数；
* 修改关键条件；
* 写 Key Test；
* Debug 实际问题；
* Meaningful Refactor。

建议记录：

| Date | Module | Type                           | Description | Related Task |
| ---- | ------ | ------------------------------ | ----------- | ------------ |
| TBD  | TBD    | TEST / DEBUG / CODE / REFACTOR | TBD         | TBD          |

---

## Invalid Human Touch

以下不计算：

* rename；
* formatting；
* comment-only；
* 移动文件；
* 复制 Codex 已生成 Test；
* 修改无业务意义常量；
* 点击 Run。

Human Touch 的目的：

> 让用户实际操作核心代码。

---

# 14. Explain Back Record / 反向解释记录

完成 A 类 Feature Review 后可以记录：

| Module / Feature | Questions | Result  | Missing Area | Date |
| ---------------- | --------: | ------- | ------------ | ---- |
| M0-S2 Local Infrastructure | 4 | UNDERSTOOD | None for current slice | 2026-08-21 |
| M0-S6 Model Gateway | 4 | PARTIAL | `ModelResult.Failure` envelope 与 `ModelFailure` payload 的稳定区分；无 concrete Provider execution evidence | 2026-08-30 |

Result：

* `UNDERSTOOD`
* `PARTIAL`
* `NOT_UNDERSTOOD`

只有 `UNDERSTOOD` 且代码层证据充分时，才考虑提升到 L3。

---

# 15. Debug Ownership / Debug 掌握度

Debug 是提升 Ownership 的高价值方式。

如果用户实际完成：

```
Reproduce
→ Locate
→ Hypothesis
→ Verify
→ Root Cause
→ Fix
→ Regression Test
```

则可以作为：

`L3 → L4`

的重要 Evidence。

推荐记录：

| Module | Bug | Root Cause Understood | Fix Understood | Regression Test | Ownership Impact |
| ------ | --- | --------------------- | -------------- | --------------- | ---------------- |
| TBD    | TBD | YES/NO                | YES/NO         | YES/NO          | TBD              |

---

# 16. Architecture Ownership / 架构掌握

Code Ownership 与 Architecture Ownership 相关，但不完全相同。

对于核心模块 L5，应能够回答：

* 为什么模块边界这样划分？
* Source of Truth 在哪里？
* 为什么这个逻辑属于 Java 而不属于 LLM？
* 为什么这里使用 RAG？
* 为什么这里不使用 RAG？
* 为什么 Agent 没有某个 Tool？
* 为什么没有直接调用 Provider SDK？
* 当前 Failure Handling 是什么？
* 当前 Scale Limitation 是什么？
* 下一阶段应该怎样演进？

如果只能描述：

> “代码调用了 RAG。”

还不属于 L5。

---

# 17. AI Agent Ownership Questions / Agent 核心自检题

核心 Agent 模块达到 L3+ 时，应能够回答。

## Planner

* Planner 读取哪些状态？
* 哪些判断由 LLM 做？
* 哪些 Hard Constraint 由 Java 做？
* Planner 为什么不能修改 Memory？
* Structured Output 失败怎么办？

---

## Evaluator

* Evaluator 为什么不读取全部历史 Weakness？
* CandidateIssue 与 Evidence 有什么区别？
* Evaluation Failure 为什么不能更新长期状态？
* 如何避免历史 Weakness 让 Evaluator 产生 confirmation bias？

---

## Learning Memory

* ErrorEvent 如何变成 Weakness？
* Correct Evidence 为什么必须保存？
* Severity 与 Confidence 为什么分开？
* Repair Success 和 Transfer Success 有什么区别？
* 如何避免一次 LLM Judgment 改变长期 Level？

---

## RAG

* 什么数据走 RAG？
* 什么数据必须走 Structured Query？
* Metadata Filter 在什么时候执行？
* RAG Failure 怎么降级？
* Retrieval Result 为什么不是长期状态真相？

---

## Tool Gateway

* Tool Schema 的作用是什么？
* userId 为什么不能由 LLM 参数决定？
* Tool Retry 谁负责？
* 为什么 Agent 不能直接调用 `setWeakness()`？
* Idempotency 应用于哪些 Tool？

---

## Context Manager

* 为什么不同 Agent Context 不一样？
* 超 Token Budget 时怎么裁？
* 什么属于 CRITICAL Context？
* 如何防止跨语言 Context 污染？
* 为什么不能把完整长期 Memory 放进 Prompt？

---

## Model Gateway

* 为什么业务层不能直接依赖 OpenAI / DeepSeek SDK？
* Provider capability 怎么处理？
* BYOK credential 如何流转？
* Structured Output failure 在哪一层处理？
* V1 为什么先采用 Fixed Routing？

---

# 18. Update Workflow / 更新流程

Feature 开发：

```
Implementation
    ↓
Code Review
    ↓
Ownership Review
    ↓
Explain Back
    ↓
Human Touch if required
    ↓
Update Ownership Matrix
```

不要在 Feature 实现刚结束时直接更新 Ownership Level。

先经过 Review。

---

# 19. Phase Closeout Rule / 阶段收口规则

Phase Closeout 必须检查：

### A-class Modules

目标：

至少：

`L3 — Explainable`

如果核心 A 类模块仍然：

`L0 / L1`

Phase 通常不应直接 PASS。

---

### Interview-critical Modules

阶段性可以 L3/L4。

项目最终用于面试前：

核心展示模块目标：

`L4–L5`

---

### B-class Modules

通常：

`L2–L3`

足够。

---

### C-class Modules

无需制造额外学习负担。

保持：

`L1–L2`

通常已经足够。

---

# 20. Ownership and Project Status / 与 PROJECT_STATUS 的关系

`PROJECT_STATUS.md` 只记录主要 Ownership Gap。

例如：

```
Current Ownership Gaps

Learning Memory
L2 → target L3
```

完整细节放在：

`OWNERSHIP_MATRIX.md`

不要在两个文档里复制完整矩阵。

---

# 21. Ownership and Learning Log / 与 LEARNING_LOG 的关系

Ownership Matrix 回答：

> 我掌握到什么程度？

Learning Log 回答：

> 我具体学会了什么？

例如：

Ownership：

```
Tool Gateway
L3
```

Learning Log：

```
- Tool Retry 应由 Java Executor 控制，而不是让 LLM 无限重试。
- Authenticated UserContext 不能来自 LLM tool arguments。
- 写 Tool 需要考虑 idempotency。
```

两者互补。

---

# 22. Ownership and Interview Readiness / 与面试准备的关系

Ownership Level 与 Interview Readiness 不完全等价。

可以额外使用：

* `I0 — Cannot Describe`
* `I1 — Can Describe`
* `I2 — Can Explain`
* `I3 — Can Defend`

大致关系：

```
L0–L1
→ I0 / I1

L2
→ I1

L3
→ I2

L4
→ I2 / I3

L5
→ I3
```

项目核心展示模块最终目标：

`I3`

---

# 23. Interview Readiness Matrix / 面试准备矩阵

| Module                   | Ownership  | Interview Readiness | Main Gap |
| ------------------------ | ---------- | ------------------- | -------- |
| Planner                  | UNASSESSED | I0                  |          |
| Conversation             | UNASSESSED | I0                  |          |
| Evaluator                | UNASSESSED | I0                  |          |
| Learning Memory          | UNASSESSED | I0                  |          |
| RAG                      | UNASSESSED | I0                  |          |
| Context Manager          | UNASSESSED | I0                  |          |
| Tool Gateway             | UNASSESSED | I0                  |          |
| Model Gateway            | L2        | I0                  | 可追踪 Request → fixed route → Executor / Adapter → timeout 或 validated result；尚无 concrete Provider execution evidence |
| Trace                    | UNASSESSED | I0                  |          |
| Eval                     | UNASSESSED | I0                  |          |
| Content Pipeline         | UNASSESSED | I0                  |          |
| Multi-language Isolation | UNASSESSED | I0                  |          |

随着真实开发和 Review 更新。

---

# 24. Do Not Inflate Ownership / 禁止虚高

以下情况不能独立证明 Ownership：

```
Codex generated code
→ “我看过了”

ChatGPT explained code
→ “我懂了”

Unit Test passed
→ “我掌握了”

Feature demo works
→ “我能面试讲了”
```

真正 Ownership 需要：

```
Locate
+
Trace
+
Explain
+
Operate
+
Defend
```

逐步积累。

---

# 25. Maintenance Rule / 维护规则

在以下时机检查本文件：

* A 类 Feature Ownership Review；
* Human Touch 完成；
* Debug Learning 完成；
* Phase Closeout；
* 面试准备复盘；
* Architecture 大改；
* 某核心模块发生重大 Refactor。

不要：

* 每个 Commit 更新；
* 每天机械更新；
* 因小型 Boilerplate 修改更新。

---

# 26. Initial State / 初始状态

在正式 Phase 开发开始前：

Architecture Docs 已建立，

但大部分核心模块尚未经过真实代码 Ownership Review。

因此：

```
Current Ownership
= UNASSESSED
```

而不是因为已经完成系统设计就自动：

```
L3
```

随着真实实现逐步更新。

---

# 27. Final Principle / 最终原则

本项目可以允许：

**Codex 写大部分代码。**

但核心模块不能长期停留在：

```
IMPLEMENTED
+
L0 / L1 Ownership
```

最终目标是：

```
Codex accelerates implementation
        +
Human retains architecture authority
        +
Human understands critical code
        +
Human operates important paths
        +
Human can defend major decisions
```

对于求职展示模块：

**代码存在只是起点。**

**能够解释、修改、排查和 defend，才算真正拥有。**
