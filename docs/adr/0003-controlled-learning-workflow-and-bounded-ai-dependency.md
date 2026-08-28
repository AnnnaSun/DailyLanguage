# ADR-0003: Controlled Learning Workflow and Bounded AI Dependency

- Status: ACCEPTED
- Date: 2026-08-29
- Scope: M1–M2 minimum practice and persistent adaptation architecture

## Context

项目需要同时实现两类能力：

- 由 Java / PostgreSQL 控制的长期学习状态、语言隔离、恢复与确定性 transition；
- 由 LLM 提供的语义理解、自然度判断、场景组合与 soft planning。

如果 Planner、Evaluator 和 Learning Workflow 都把 LLM 作为唯一执行路径，Model failure 会使
系统无法产生合法计划、无法保留确定性学习结果，并让长期状态正确性依赖非确定性输出。

相反，如果为了减少 LLM 依赖而把语义判断全部改为固定规则，产品又会失去 AI Language Tutor
在真实表达、自然度与沟通质量上的主要价值。

因此需要明确：LLM 是受控的 semantic capability，不是 Learning Workflow、长期状态或
persistence integrity 的 authority。

## Decision

采用：

```text
Deterministic Learning Workflow
        ↓
Java Domain State and Policies
        ↓
Optional / Bounded AI Capabilities
```

核心原则：

```text
Java owns learning truth and workflow.
LLM supplies bounded semantic intelligence.
```

### Learning Workflow Authority

Spring Boot Application / Domain 负责：

- `UserContext` 与 `languageProfileId` ownership；
- LearningTask、PracticeSession、EvaluationRun 与 AdaptationRun lifecycle；
- transition guard、idempotency、timeout / retry policy 与 recovery boundary；
- Evidence qualification、aggregation、long-term state transition 与 persistence；
- Model unavailable 时的明确 fallback / degraded behavior。

LLM 不决定 Application 下一步骤，也不直接修改长期学习状态。

### Hybrid Planner

Planner 使用以下顺序：

```text
Structured Learner State
        ↓
Java Eligible Candidate Generation
        ↓
Java Hard Constraint Filtering
        ↓
Optional LLM Ranking / Scenario / Reason Enrichment
        ↓
Java Final Validation and Persistence
        ↓
LearningTask
```

Java 至少负责 Practice availability、duration、difficulty range、due review、disabled preference、
repetition constraints、target eligibility 与 fallback priority。

LLM 可以在合法候选中做 soft ranking、组合学习目标、选择自然场景并生成推荐理由。

Model unavailable、timeout 或最终输出无效时，Planner 应能返回一个合法但可能较弱的
deterministic fallback plan；不得持久化未通过 hard constraint 的 candidate。

### Hybrid Evaluation

一次 Practice 的评估拆为：

```text
Trusted Practice Events
        ↓
Deterministic Assessment
        ├─ completion
        ├─ duration
        ├─ attempts
        ├─ assistance usage
        └─ exact / rule-verifiable result

Practice Content
        ↓
Optional LLM Semantic Evaluation
        ├─ naturalness
        ├─ semantic issue
        ├─ communication quality
        └─ expression feedback
        ↓
Validated Semantic Candidate
```

两类结果分别经过与其 provenance 相符的 Java qualification 后，才可以形成 Evidence。

Model failure 只阻止 model-derived Evidence。它不得删除或否定已经由可信 Practice event
确定的 deterministic assessment / Evidence，也不得破坏已完成的 PracticeSession。

### Bounded AI Execution

Planner、Evaluator 等默认使用 bounded model task：

```text
one model request
→ structured response
→ bounded repair if allowed
→ validation
→ terminate
```

只有出现真实 model tool requirement 时，才引入 Tool Gateway 与 bounded tool loop。所有 loop
必须具备 maximum turns、maximum tool calls、timeout、terminal success 与 terminal failure。

RAG 继续按 V1 Scope 在 M3 进入 Content / Retrieval flow，不是 M1 Planner / Evaluator 的
默认前置条件。M1 优先使用 role-specific structured context assembly。

### Model Availability Invariant

LLM availability 可以影响：

- semantic evaluation quality；
- dynamic Conversation；
- plan richness、scenario 与 explanation quality。

LLM availability不得决定：

- persistence integrity；
- multi-language isolation；
- deterministic Evidence processing；
- long-term state replay；
- session recovery；
- Java fallback plan 的合法性。

## Alternatives

### LLM-first Planner / Evaluator

实现较快，但会让 Model failure 成为主业务流程 failure，并使长期状态过度依赖单次模型判断。

### Fully Deterministic Tutor

状态容易验证，但难以可靠判断自然度、语义与开放式沟通质量，会削弱产品定位。

### Generic Agent Harness / Graph Runtime

可以提供通用 checkpoint、tool loop 和 orchestration，但当前会引入第二套 workflow / state
abstraction，并遮蔽 Java transaction、language isolation 与 Evidence qualification boundary。
只有出现动态分支、长时间 interrupt、并行节点或跨进程 durable workflow 的真实需求时再评估。

## Consequences

- Planner 和 Evaluator 必须同时设计 deterministic 与 semantic responsibility；
- 测试必须覆盖 Model unavailable / invalid output 下的 fallback 与 Evidence isolation；
- `EvaluationResult` 需要区分 deterministic assessment 与 semantic candidate provenance；
- Learning Memory 可以在没有模型调用的情况下基于已有 Qualified Evidence 确定性重放；
- 第一版不以 Tool Gateway、RAG、workflow DSL 或 dynamic plugin 证明 Agent Engineering；
- 面试展示重点转向 state authority、bounded AI、failure isolation、replay 与可验证 adaptation。

