# AI Language Tutor — Learning Log

> 本文件记录 AI Language Tutor 开发过程中真正获得的 Engineering Learning（工程学习成果）。
>
> 本文件不是：
>
> * 每日开发日记；
> * Commit History；
> * TODO List；
> * Architecture 文档副本；
> * Codex 自动生成内容摘要；
> * “今天做了什么”的流水账。
>
> 本文件重点记录：
>
> **通过真实实现、Code Review、Debug、测试和架构决策后，用户真正理解并能够复用的工程知识。**

---

# 1. Purpose / 目的

Learning Log 主要回答：

> 这次开发让我真正学会了什么？

> 这个知识以后还能用在哪里？

> 如果面试官问到，我能从项目中怎么解释？

它服务三个目标：

```
Development
→ 理解当前系统

Engineering Learning
→ 形成可迁移知识

Interview
→ 转化为真实项目表达
```

---

# 2. Learning Log ≠ Ownership Matrix

两个文件职责不同。

`OWNERSHIP_MATRIX.md` 回答：

> 我对某个模块掌握到什么程度？

例如：

```
Tool Gateway
Ownership: L3
```

`LEARNING_LOG.md` 回答：

> 我具体掌握了哪些工程知识？

例如：

```
Tool Retry 应由 Java Executor 控制，
而不是让 LLM 自己无限重试。
```

因此：

```
Ownership Matrix
→ Level

Learning Log
→ Knowledge
```

---

# 3. What Should Be Recorded / 什么值得记录

优先记录以下内容。

## Architecture Learning

例如：

* Module Boundary；
* State Ownership；
* Dependency Direction；
* Source of Truth；
* Architecture Trade-off。

---

## AI Agent Engineering

例如：

* Agent vs Workflow；
* Planner Boundary；
* Evaluator Boundary；
* Tool Calling；
* Structured Output；
* Agent Loop；
* Model Gateway；
* Prompt Architecture。

---

## Memory / Learning State

例如：

* Raw Evidence vs Long-term State；
* Weakness Aggregation；
* State Transition；
* Correct Evidence；
* Confidence / Severity；
* Transfer Evidence。

---

## RAG / Context Engineering

例如：

* Structured Query vs RAG；
* Metadata Filter；
* Retrieval Scope；
* Context Budget；
* Context Priority；
* Summary；
* Provenance。

---

## Java Backend

例如：

* Transaction Boundary；
* Idempotency；
* Retry；
* Validation；
* State Machine；
* Repository Boundary；
* Async Job；
* Cache Consistency。

---

## Reliability / Security

例如：

* Permission；
* UserContext；
* Multi-language Isolation；
* Secret Handling；
* Failure Degradation；
* Trace；
* Eval。

---

## Debug Learning

例如：

* Root Cause；
* 错误定位方法；
* 为什么原假设错误；
* 如何避免 Regression。

---

# 4. What Should NOT Be Recorded / 什么不值得记录

一般不要记录：

* “今天创建了三个 DTO”；
* “新增一个 Controller”；
* “修复格式问题”；
* “Codex 帮我写了代码”；
* “测试通过了”；
* “新增一个页面”；
* “把变量名改了”。

除非这些行为背后产生了可迁移的工程知识。

例如：

```
❌ 今天给 Planner 增加了 DTO。
```

比不上：

```
✅ Planner Structured Output DTO
   不能直接作为 Domain Entity 使用，
   因为模型返回只属于 untrusted candidate，
   还需要 Java Validation 和 Qualification。
```

---

# 5. Learning Source / 学习来源

每条 Learning 应尽量标记来源。

可用：

* `IMPLEMENTATION`
* `CODE_REVIEW`
* `DEBUG`
* `HUMAN_TOUCH`
* `TEST`
* `ARCHITECTURE_DECISION`
* `EVAL`
* `PERFORMANCE_ANALYSIS`
* `SECURITY_REVIEW`

不要把：

`CHAT_DISCUSSION`

单独作为高置信 Engineering Learning 的充分证据。

讨论可以形成：

`HYPOTHESIS`

但最好通过代码或验证确认。

---

# 6. Confidence / 学习置信度

每个 Entry 可以记录：

* `PROVISIONAL`
* `CONFIRMED`
* `OPERATED`

## PROVISIONAL

已经理解概念，但主要来自：

* 设计；
* 阅读；
  -讨论。

尚未通过真实操作确认。

---

## CONFIRMED

已经结合：

* source code；
* test；
* actual flow；

确认理解。

---

## OPERATED

已经实际：

* 修改；
* Debug；
* 测试；
* Refactor；
* 排查；

过该机制。

对于面试核心知识，尽量逐步达到：

`OPERATED`

---

# 7. Learning Categories / 分类

推荐使用以下 Category：

* `AGENT`
* `PLANNER`
* `EVALUATOR`
* `MEMORY`
* `RAG`
* `CONTEXT`
* `TOOL_CALLING`
* `MODEL_GATEWAY`
* `PROMPT`
* `STRUCTURED_OUTPUT`
* `EVAL`
* `OBSERVABILITY`
* `SECURITY`
* `JAVA`
* `DATABASE`
* `REDIS`
* `ASYNC`
* `CONTENT_PIPELINE`
* `INFRA`
* `FRONTEND`
* `DEBUG`
* `ARCHITECTURE`

每条选择最主要的一个。

不要大量打标签。

---

# 8. Learning Entry Template / 学习条目模板

使用：

## LEARN-XXX — Title

**Date:**

YYYY-MM-DD

**Category:**

TBD

**Source:**

IMPLEMENTATION / CODE_REVIEW / DEBUG / HUMAN_TOUCH / TEST / ARCHITECTURE_DECISION / EVAL

**Related Module:**

TBD

**Related Task / Feature:**

TBD

**Confidence:**

PROVISIONAL / CONFIRMED / OPERATED

---

### What I Learned / 学到了什么

用自己的话说明核心结论。

尽量控制在 2–5 句话。

---

### Why It Matters / 为什么重要

说明如果不这样做，会导致什么真实问题。

例如：

* state pollution；
* permission bypass；
* context explosion；
* duplicated state；
* inconsistent behavior；
* infinite retry；
* architecture coupling。

---

### Code / Runtime Evidence / 实际证据

记录真实来源，例如：

```
File:
...

Class / Method:
...

Test:
...

Trace:
...
```

如果当前只是 Architecture Learning，可以写：

```
Architecture Decision:
...
```

但不能伪造代码位置。

---

### Before / After Understanding / 认知变化

**Before:**

之前怎么理解。

**After:**

现在怎么理解。

这一项非常适合记录自己原来错误或模糊的认知。

---

### Trade-off / 取舍

当前方案解决了什么问题？

代价是什么？

如果没有明显 Trade-off，可以写：

`N/A`

不要为了格式硬编。

---

### Reusable Rule / 可迁移规则

把本次知识压缩成以后其他项目也能使用的一条规则。

例如：

> LLM 生成的结构化结果即使 JSON 合法，也不能默认等于 Domain Valid。

---

### Interview Angle / 面试角度

如果面试官问：

> TBD

可以从项目回答：

> TBD

只记录真实实现能够支撑的内容。

不要写脱离项目事实的“标准答案”。

---

### Follow-up Gap / 仍需补充

当前还有什么没真正掌握？

例如：

* 还没有真实 Debug；
* 没看 retry implementation；
* 没验证 concurrency；
* 没做 Eval；
* 只理解 happy path。

如果没有：

`None`

---

# 9. Compact Entry / 简化条目

不是所有知识都值得写完整 Entry。

较小但值得保留的 Engineering Learning 可以使用：

## LEARN-XXX — Title

**Category:** TBD
**Source:** TBD
**Confidence:** TBD

**Learning:**

TBD

**Evidence:**

TBD

**Reusable Rule:**

TBD

完整模板留给：

* A-class modules；
* Architecture decisions；
* Debug；
* Interview-critical learning。

---

# 10. Current Learning Log / 当前学习记录

> 当前暂不根据 Architecture Discussion 自动填充大量“已学会”内容。
>
> 正式开发开始后逐步追加。

## LEARN-001 — Docker volume mount 必须覆盖应用的真实数据目录

**Category:** Infrastructure / PostgreSQL
**Source:** IMPLEMENTATION + DEBUG + CODE_REVIEW
**Confidence:** CONFIRMED

**Learning:**

Docker volume 只覆盖指定 mount target，不会自动改变应用的 `PGDATA`。PostgreSQL 18 使用 `/var/lib/postgresql/18/docker`，因此应挂载共同父目录 `/var/lib/postgresql`；继续挂载旧路径 `/var/lib/postgresql/data` 会让真实数据目录落到 volume 外，官方 entrypoint 因此 fail fast。

**Evidence:**

`compose.yaml` 的 PostgreSQL volume 配置；首次 runtime startup 的 PostgreSQL 18 entrypoint error；修正后 PostgreSQL、pgvector availability 与 backend database health verification 均通过。

**Reusable Rule:**

配置 container persistence 时，必须核对当前 image 的真实 data directory；不能只复制旧版本常见的 volume path。

---

# 11. Interview-critical Learning Areas / 面试重点知识区

以下知识属于项目最终应重点沉淀的领域。

---

## 11.1 Agent vs Workflow

最终应理解：

* 什么步骤适合 deterministic workflow；
* 什么步骤适合 LLM soft decision；
* 为什么不能让 LLM 接管整个业务流程；
* 什么情况下才真正需要 Agent Loop。

Related Modules:

* Planner；
* Evaluator；
* Tool Gateway；
* Agent Runtime。

Target:

`OPERATED`

---

## 11.2 Planner Architecture

最终应理解：

* Structured State 如何进入 Planner；
* Hard Constraint vs Soft Decision；
* LearningTask Contract；
* Planner Tool Allowlist；
* Planner Structured Output；
* Planner failure handling。

Target:

`OPERATED`

---

## 11.3 Evaluator Architecture

最终应理解：

* Session-level Evaluation；
* CandidateIssue；
* Evidence Qualification；
* Historical Bias；
* Schema / Semantic Validation；
* Evaluation Failure Safety。

Target:

`OPERATED`

---

## 11.4 Persistent Learning Memory

最终应理解：

```
Practice
→ Evidence
→ Aggregation
→ Long-term State
```

包括：

* Error Evidence；
* Correct Evidence；
* Confidence；
* Severity；
* Recency；
* Cross-scenario；
* Independence；
* Transfer Success。

Target:

`OPERATED`

这是整个项目最重要的学习区域之一。

---

## 11.5 Tool Calling

最终应理解：

* Tool Schema；
* Tool Allowlist；
* Authenticated UserContext；
* Validation；
* Permission；
* Retry；
* Timeout；
* Idempotency；
* Tool Result Contract；
* Infinite Loop Prevention。

Target:

`OPERATED`

---

## 11.6 RAG

最终应理解：

* 为什么不是所有 Memory 都走 Vector Search；
* Structured Query vs Semantic Retrieval；
* pgvector；
* Metadata Filter；
* Top-K；
* Retrieval Scope；
* Permission Isolation；
* RAG Failure Degradation；
* RAG Eval。

Target:

`OPERATED`

---

## 11.7 Context Engineering

最终应理解：

* Agent-specific Context；
* Context Priority；
* Token Budget；
* Recent Turns；
* Structured Summary；
* Retrieved Context；
* Provenance；
* Context Compression；
* Context Eval。

Target:

`OPERATED`

---

## 11.8 Model Gateway

最终应理解：

* Provider abstraction；
* BYOK；
* Capability Check；
* Structured Output support；
* Retry；
* Usage；
* Trace；
* Fixed Routing；
* future routing trade-off。

Target:

`OPERATED`

---

## 11.9 Trace & Eval

最终应理解：

```
Trace
→ What happened?

Eval
→ Was it correct?
```

以及：

* promptVersion；
* rubricVersion；
* contextStrategyVersion；
* Regression Dataset；
* Rule-based Eval；
* LLM Judge；
* production failure case。

Target:

`OPERATED`

---

## 11.10 Java Domain Authority

最终应理解：

为什么：

```
LLM
→ soft decision / evidence
```

而：

```
Java
→ validation / state transition / permission
```

以及两者边界如何落实到代码。

Target:

`OPERATED`

---

# 12. Debug Learning Template / Debug 学习模板

实际 Bug 修复后可以记录：

## LEARN-XXX — Debug: <Bug>

**Category:** DEBUG

**Source:** DEBUG

**Confidence:** OPERATED

### Symptom / 现象

...

### Initial Hypothesis / 初始假设

...

### Evidence / 证据

...

### Root Cause / 根因

...

### Why My Initial Thinking Was Right/Wrong / 原判断为什么对或错

...

### Fix / 修复

...

### Regression Protection / 如何防回归

...

### Reusable Debug Rule / 可迁移 Debug 规则

...

### Interview Angle

...

Debug Entry 是高价值 Learning，不要只记录：

> Fixed XXX bug.

---

# 13. Architecture Decision Learning / 架构决策学习

重要 ADR 完成后，可以追加对应 Learning。

例如：

```
ADR
→ 为什么选 PostgreSQL + pgvector
→ 为什么 V1 不引入独立 Vector DB
```

Learning Log 不复制整个 ADR。

只记录：

* 自己理解的核心原因；
* trade-off；
* 什么条件下未来应该重新评估。

---

# 14. Human Touch Learning / Human Touch 学习

完成 Human Touch 后检查：

> 这次亲手操作让我真正理解了什么？

如果只是：

* 改名字；
* 改格式；
* 抄测试；

一般没有 Learning Entry。

如果是：

* 写 aggregation rule；
* 修改 timeout condition；
* Debug tool retry；
* 写 Evaluator schema regression test；

则应考虑记录。

---

# 15. Learning Gap / 学习缺口

如果开发中发现：

> 代码已经依赖某个知识，但我完全不理解。

不要硬写 Learning。

记录到：

`OWNERSHIP_MATRIX.md → Ownership Debt`

例如：

```
RAG
Current Ownership: L1

Missing:
pgvector similarity query
metadata filter
indexing strategy
```

等真正掌握后，再写 Learning Entry。

---

# 16. Learning Quality Check / 学习条目质量检查

一个高质量 Learning Entry 应至少满足其中三项：

* 来自真实代码；
* 来自真实 Bug；
* 来自真实设计决策；
* 能解释 Why；
* 能解释 Trade-off；
* 可以迁移到其他项目；
* 可以形成面试回答；
* 修正了之前错误理解。

如果只是：

> 我知道了 Spring Boot 有 Controller。

没有必要记录。

---

# 17. Phase Closeout Learning / 阶段收口

每次 Phase Closeout：

建议提炼：

`3–7 个`

真正重要的 Engineering Learning。

不要把 Phase 中所有修改都转成 Learning Entry。

优先选择：

1. Architecture；
2. A-class module；
3. Debug；
4. State / Consistency；
5. Agent Engineering；
6. Security；
7. Performance；
8. 重要 Trade-off。

如果本 Phase 实际没有 3 个值得记录的知识点：

可以少于 3 个。

不要凑数。

---

# 18. Learning Review / 学习回顾

项目开发到一定阶段后，可以按 Category 回顾。

例如：

## Agent

* LEARN-001
* LEARN-007
* LEARN-015

## RAG

* LEARN-010
* LEARN-021

## Memory

* LEARN-005
* LEARN-006
* LEARN-023

这样可以用于后续面试专题复习。

不需要建立第二套知识文档。

---

# 19. Interview Conversion / 转化成面试素材

当一个 Learning 已达到：

`CONFIRMED / OPERATED`

并且相关模块 Ownership 达到：

`L3+`

可以考虑转成 Interview Material。

例如：

```
Learning:
Evaluator 不能直接修改 Weakness
```

转成：

```
面试问题：
你们怎么避免 LLM 一次误判污染长期用户画像？
```

回答应结合：

```
Evaluator
→ CandidateIssue
→ Java Validation
→ Evidence
→ Learning Memory
→ Weakness Aggregation
```

而不是背抽象 AI Safety 理论。

---

# 20. Knowledge Honesty / 知识真实性

记录知识时区分：

### FACT

已经从实际代码、Test、Trace 或正式 Decision 验证。

### INFERENCE

根据当前实现推断，但没有完全验证。

### UNKNOWN

当前还不知道。

不要为了让 Learning Log 看起来丰富，把：

`INFERENCE`

写成：

`FACT`

---

# 21. Do Not Turn This Into Documentation Debt / 不制造文档负担

Learning Log 的目标是帮助理解。

如果维护成本开始明显超过学习价值：

减少记录。

优先保留：

* A-class；
* Debug；
* Architecture；
* Trade-off；
* Interview-critical Learning。

普通开发无需记录。

---

# 22. Relation to Other Harness Files / 与其他文件关系

```
AGENTS.md
→ 我们开发时必须遵守什么

SYSTEM_OVERVIEW.md
→ 系统整体是什么

MODULE_MAP.md
→ 模块在哪里、负责什么

DATA_FLOW.md
→ 数据怎么流

AGENT_FLOW.md
→ AI Runtime 怎么运行

PROJECT_STATUS.md
→ 现在做到哪里

BACKLOG.md
→ 以后可能做什么

OWNERSHIP_MATRIX.md
→ 我掌握到什么程度

LEARNING_LOG.md
→ 我真正学会了什么
```

这些文件职责不要互相复制。

---

# 23. Initial State / 初始状态

目前 Architecture Harness 已经建立。

但是在真实 Feature Implementation、Code Review 和 Debug 开始前：

不要预先填写大量：

`CONFIRMED`

或：

`OPERATED`

Learning。

当前允许存在：

Architecture Understanding。

但后续需要通过真实代码逐步验证。

---

# 24. Final Principle / 最终原则

Codex 可以帮助快速获得：

```
More Code
```

Harness 应确保开发过程同时获得：

```
More Understanding
```

Learning Log 最终应该证明：

> 我不仅完成了这个项目，
> 还通过这个项目真正理解了 Agent、RAG、Context、Memory、Tool Calling、Eval、Observability 和 Java Backend Architecture。

记录重点始终是：

**What changed in my engineering understanding?**

而不是：

**What files changed today?**
