# AI Language Tutor — Engineering Evidence Plan

> Status: APPROVED
> Approved: 2026-08-29
> Scope: V1 Agent Engineering + Senior Java interview evidence track
> Product scope authority: `docs/product/V1_SCOPE.md`
> Delivery authority: `docs/planning/V1_PHASE_PLAN.md`

## 1. Purpose

本项目不仅交付可用的 Language Tutor，也必须通过同一真实学习闭环形成可运行、可验证、可解释的
Agent Engineering 与 Senior Java Engineering Evidence。

一个能力只有同时具备以下内容，才算形成面试证据：

```text
Real Product Scenario
    +
Architecture Decision / Boundary
    +
Production Implementation
    +
Failure-path Verification
    +
Eval / Trace / Measured Result
    +
Human Explainability
```

只在文档中出现关键词、只生成正常路径 Demo，或只展示多个 Agent 名称，都不算完成。

## 2. Required Agent Engineering Evidence

| Capability | Product Scenario | Required Evidence | Target Phase |
| --- | --- | --- | --- |
| Hallucination / Grounding Control | Evaluator diagnosis、Reading answer、Content lesson | source turn / source chunk provenance、unsupported-claim rejection、groundedness regression eval | M1 / M3 |
| Persistent Learning Memory | 多次 Practice 改变 Weakness / Skill 并影响下一次 Planner | immutable Raw Evidence、versioned aggregation、deterministic replay、reactivation / decay / conflict tests | M2 |
| Context & Token Efficiency | Planner、Evaluator、Conversation、Content roles 获取最小充分 Context | per-role token budget、priority / dedup / summary / Top-K、quality-token-cost comparison、actual token trace | M1–M3 / M6 |
| Controlled Multi-role Agent Workflow | imported content 生成可发布学习材料 | Retrieval Role → Lesson Design Role → Quality Review Role → bounded revision → Java publish | M3 |
| RAG Quality & Isolation | imported content / personal memory retrieval | relevance、provenance、languageProfileId filter、permission、prompt-injection regression | M3 |
| Tool Safety | Content Retrieval / Dictionary 等 model tools | schema、allowlist、permission、timeout、retry、idempotency、maximum calls、safe failure | M3 |
| Model / Provider Portability & Routing | Planning、Conversation、Evaluation capability | provider contract tests、fixed capability mapping baseline、structured-output compatibility、fallback、quality / latency / cost comparison 与 router trade-off | M1 / M6 |
| AI Observability | 任一 Planner / Tool / RAG / Evaluator failure | correlated spans、prompt / rubric / context version、selected source IDs、tokens、latency、retry、mutation lineage | M1–M6 |
| Agent Eval | Prompt、Model、Rubric、Context、Retrieval 变化 | versioned dataset、rule / reference grader、limited calibrated LLM judge、regression gate | M1–M6 |

## 3. Required Senior Java Engineering Evidence

| Capability | Required Project Evidence |
| --- | --- |
| Domain Modeling | LearningTask、PracticeSession、EvaluationRun、Evidence、Weakness / Skill lifecycle 的明确 invariant 与 state transition |
| Transaction & Consistency | Evidence qualification、aggregation 与 state mutation 的 transaction boundary；失败不产生 partial long-term state |
| Idempotency & Concurrency | duplicate completion / evaluation / aggregation、worker retry、optimistic conflict 与 lost-update verification |
| Durable Processing | 真实异步需求下的 recoverable job state；只有需要跨 boundary 可靠事件时才评估 outbox / queue |
| Persistence Design | PostgreSQL structured truth、pgvector retrieval context、Redis runtime/cache 的 authority 与 consistency boundary |
| Cache Consistency | Profile summary / retrieval metadata 等真实 cache use case 的 version、invalidation、stale-read、cache failure 与 source-of-truth verification |
| Resilience | timeout、bounded retry、backpressure、concurrency limit、provider failure isolation 与 recovery |
| Security | UserContext、languageProfileId isolation、BYOK secret boundary、Tool/RAG permission、trace redaction、prompt injection defense |
| Performance | query/index evidence、AI concurrency/capacity test、token/latency/cost budget、必要时使用 JFR / profiling evidence |
| Testing & Delivery | unit、integration、contract、concurrency、failure-injection、load、migration、CI regression 与 reproducible deployment |

不为了覆盖技术名词自动引入 Kafka、微服务、Kubernetes、AOP 或通用 workflow engine。需要这些组件时，
必须先给出真实 throughput、durability、dependency direction 或 operational requirement。

## 4. Approved M3 Controlled Multi-role Agent Workflow

M3 使用真实 Content / Reading 需求展示受控 Multi-role Agent Engineering：

```text
Imported / Curated Content
        ↓
Content Retrieval Role
        ↓
Grounded Source Chunks + Provenance
        ↓
Lesson Design Role
        ↓
Structured Lesson Candidate
        ↓
Quality Review Role
        ↓
Accept / One Bounded Revision / Reject
        ↓
Java Validation + Permission + Publish
```

Java 持有：

- workflow state 与 terminal status；
- `languageProfileId` / content ownership；
- maximum model turns / tool calls；
- timeout、retry、idempotency 与 recovery；
- source provenance、schema、semantic constraint 与 publish authority。

AI Role 持有：

- retrieval intent / query enrichment；
- lesson composition；
- language naturalness、difficulty 与 groundedness review；
- bounded revision candidate。

三个 Role 分别拥有独立 input/output schema、role-specific context budget、Tool Allowlist 与 Trace
span；handoff 只传递 typed artifact，不共享可变 Prompt State。每次 Role invocation 都是 bounded
Agent execution，但整个 Flow 由 Java workflow 控制，不定义为 autonomous Multi-agent System。

该 Flow 是 M3 正式 V1 deliverable。它不使 Planner、Evaluator 或 Learning Memory 自动获得通用 Tool
Loop，也不允许 Agent 直接修改长期学习状态。

## 5. Evidence Acceptance Contract

每个核心 capability 完成时，Feature Dossier 或对应 verification artifact 至少记录：

- interview question / engineering claim；
- production entry point 与 critical flow；
- state、transaction、permission 与 external boundary；
- happy-path test；
- failure / adversarial / concurrency test（按适用风险）；
- Eval dataset version、grader 与 result；
- sanitized Trace / metric sample；
- token、latency、cost 或 capacity result（适用时）；
- known limitation 与 rejected alternative；
- Human Review Focus / Explain Back evidence。

Evidence 必须区分：

```text
DESIGNED
IMPLEMENTED
VERIFIED
MEASURED
DEMONSTRATED
```

不得把 `DESIGNED` 表述为已经实现，也不得把单次成功 Demo 表述为可靠性或学习有效性已经成立。

## 6. Phase Evidence Gates

### M1

- Grounded Evaluator candidate 可以定位到具体 Practice turn / span；
- invalid structure、unsupported semantic claim 与 Model failure 均有回归测试；
- Planner / Evaluator 保存 context、token、latency、model 与 version metadata；
- 至少两个 Provider Adapter 或一个真实 Provider + deterministic stub 通过公共 contract。

### M2

- Raw Evidence → Aggregation → Long-term State 可 deterministic replay；
- aggregation policy、prompt / rubric / context / dataset 均有 version；
- duplicate、retry、concurrent update 与 cross-language contamination 有验证；
- 能解释一次长期状态 mutation 使用了哪些 Evidence、规则与版本。

### M3

- 完成 Approved Controlled Multi-role Agent Workflow；
- RAG 与 Tool Gateway 具备 provenance、permission、injection、timeout 与 loop-boundary verification；
- 对 Full Context baseline 与 budgeted Context strategy 进行质量、Token、延迟和成本比较；
- Content / Reading 输出具备 groundedness Eval。

### M6

- 核心 AI capability 有可重复 regression suite；
- Provider / Model 的质量、延迟、Token 与成本结果可比较；
- 关键 Java/AI path 完成 failure injection、load / capacity 与 recovery 验证；
- CI 执行 build、migration、test 与 required Eval gate；
- 形成一条可重复的 interview demo，展示 adaptation、fallback、trace、replay 与 language isolation。

## 7. Non-goals

- 为展示数量创建多个没有独立职责的 Agent；
- 让 LLM 决定 transaction、permission、identity 或长期状态 truth；
- 无上限 autonomous loop；
- 把 RAG result 当作 Learning Memory truth；
- 没有 Eval 与 failure evidence 的模型路由、自动优化或 self-improvement；
- 为技术栈列表制造没有产品或可靠性理由的分布式组件。
