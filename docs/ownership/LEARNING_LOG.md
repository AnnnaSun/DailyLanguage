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

### M0-S6A confirmed evidence

- `ModelPurpose` 表示业务为什么调用，`ModelOperation` 表示执行哪种技术操作；两者共同组成 route key；
- `ProviderId` / `ModelId` 是外部可配置 value object，不使用无法覆盖用户自定义名称的 enum；
- identifier 在边界拒绝外围空白，使错误配置 fail fast，不通过静默 trim 隐藏 identity mismatch；
- Operation enum 建立编译期受控 vocabulary，不代表对应 Typed Port、Adapter 或 runtime capability 已实现；
- focused tests、server compile、Diff Review 与 Explain Back 已通过。

当前 Ownership 为 `L1`：已定位并理解 route vocabulary；真实 invocation、failure、Credential 与
Provider Adapter path 尚未实现，不能把本次 evidence 解释为完整 Model Gateway Ownership。

### M0-S6B confirmed evidence

- sealed `ModelResult<T>` 只允许 Success 或 Failure，排除 nullable response + error 的 both / neither
  非法状态；
- `ModelFailureKind` 表示为什么失败，ProviderId + ModelId 表示在哪条 route 失败；route identity
  必须同时存在或同时缺失；
- partial route identity 是对象 invariant 被破坏，不是需要新增的一种 Provider failure；
- `retryAfter` 只保存 Rate Limit / Temporary Unavailable 的正 Duration metadata，不执行 scheduler、
  sleep、第二次 Provider call、retry counter 或 fallback；
- route vocabulary 与 invocation result contract 分别位于 `modelgateway.routing` 和
  `modelgateway.result`，避免后续 Typed Port 继续堆入宽泛 `domain` package；
- S6A + S6B focused regression、server compile、Diff Review 与 Explain Back 已通过。

Ownership 仍为 `L1`：S6B 增强了 contract understanding，但尚不存在可追踪的 invocation call chain，
不满足 L2 的真实 Entry → Call → Result evidence。

### M0-S6C confirmed evidence

- `TextGenerationRequest` 只描述 Purpose、ordered messages 与 output specification，不让业务调用方选择
  Provider / Model；实际 route selection 与 Provider execution 明确留给 S6D / S6E；
- `INSTRUCTION` / `USER` / `MODEL` 是单次 Model request 内的 message semantics，不是 Planner、
  Conversation、Evaluator 等 Agent role；Memory / RAG data 也不自动获得 instruction authority；
- sealed `TextOutputSpecification` 当前只有 Plain Text，但为已确认的 S8 typed Structured Output
  specification 保留携带 schema 的受控扩展点，不使用 enum + nullable fields 或 arbitrary Map；
- `TextGenerationResponse` 只汇总实际 Provider / Model、text、normalized finish reason 与 optional token
  usage；Provider raw response 与 raw finish reason 不进入业务 contract；
- 用户在 Ownership 讨论中明确指出当前只有 Port contract，完整 routing / Adapter / Provider invocation
  尚未出现；因此本次 Ownership Check 只覆盖 typed contract，不要求解释未来调用链；
- focused Model Gateway regression、server compile 与 Diff Review 已通过。

Ownership 保持 `L1`：已经能够定位并质疑 contract boundary，但没有真实 Port implementation、route
resolver、Adapter 或 external call evidence，不能提升为 L2。

### M0-S6D confirmed evidence

- `RoutedTextGenerationPort` 根据 request purpose 查询固定的 `Purpose + TEXT_GENERATION` route；route 缺失时
  返回不带 route identity 的 `CAPABILITY_UNAVAILABLE`，且不调用 Adapter；
- `TextGenerationRoute` 使用 Composition 将 `ProviderId`、`ModelId` 与 operation-specific
  `TextGenerationProviderAdapter` 绑定为可执行 route，不引入 Registry、dynamic router 或万能 Provider；
- route 存在时，Port 将选中的 Provider / Model identity 和原 request 只传给 Adapter 一次；Adapter 返回的
  success / failure identity 必须与所选 route 一致；
- identity 缺失或不一致表示 Adapter wiring / attribution invariant 被破坏，因此 fail fast，而不是伪装成
  `PROVIDER_FAILURE`；Provider 实际解析出的 raw model version 未来属于 Trace metadata，不替换业务 contract
  中的 selected route model；
- 用户能够区分正常 operational failure 与低概率 internal mismatch，并解释 mismatch 校验对自定义 Provider、
  fallback、费用和 Trace attribution 的保护作用；
- focused S6D tests、S6A-S6D Model Gateway regression、server compile、Diff Review 与 Explain Back 已通过，
  commit 为 `1e32ff7`。

Ownership 提升为 `L2`：已经能够追踪 Request → fixed route → selected Adapter → validated ModelResult 的
真实调用链。尚无 concrete Provider、timeout / exception translation 与真实外部调用 evidence，因此不提升为
L3。

### M0-S6E design learning — 为什么不使用 Kafka 回收迟到的 Model response

- Gateway timeout 需要区分两种语义：interactive wait timeout 只表示用户不再同步等待，后台调用仍可继续；
  model execution timeout 才是一次调用的最终 deadline，但即使本地取消也不能证明 Provider 没有执行或计费；
- 若要求迟到结果可以恢复，`modelCallAttemptId` / `jobId` 必须在调用 Provider **之前**创建。等到 timeout
  后再创建会产生结果已返回但尚无归属记录的竞态；正常快速完成时，内部 attempt 可以存在但不必向用户展示；
- Kafka 不会自动截获 Provider response。必须由仍在运行的 Worker 收到响应后，主动发布带 correlation ID
  的完成事件；Worker 在外部调用期间崩溃时，Kafka 无法找回 Provider 已生成但尚未发布的 response；
- Kafka message key 主要用于 partition、ordering 与 correlation，不是供 UI 按 key 随机读取结果的业务查询
  存储。即使未来使用 Kafka，用户可查询的 Job status、ownership、result、expiry 与 consume-once 状态仍应由
  PostgreSQL 等 durable application state 承担；
- Kafka 的 delivery / exactly-once 保证不覆盖 Kafka transaction 之外的 Provider 调用。Consumer retry 仍可能
  产生第二次模型执行、重复 token cost 与不同输出，需要 Provider idempotency、attempt policy 与明确 Trace 才能处理；
- BYOK Credential 禁止写入 Kafka、DB、Trace 或 Log。完全异步的 Kafka Consumer 若在原 HTTP request 结束后
  才调用 Provider，将无法自然取得 transient Credential；为此增加临时 secret distribution 会成为独立的
  Security Architecture Change；
- 对已确认的长任务，当前 V1 更合适的 baseline 是 `Spring TaskExecutor + DB Job State`：后台 Worker 完成后
  写入 durable result，Application Workflow 决定是否创建 `PendingAction`、是否仍适用以及用户确认后如何继续；
  Kafka 只有在真实出现跨实例吞吐、多消费者、replay、work queue / DLQ 或 ordering 需求时才重新评估；
- 当前决定是不把 Kafka、late-result persistence、用户通知或 Workflow resume 塞入 M0-S6E。S6E 继续只负责
  单次 Model call 的最终 deadline 与 safe failure translation；迟到结果回收已于 2026-08-30 作为独立
  `ModelCallJob` Feature 纳入 V1，并排入 S7 / S8 之后的 M0-S9 backend foundation；
- 已批准的 Job identity / versioning 保持语义分离：UUIDv7 `jobId` 表示稳定 identity，`workflowVersion`
  判断结果是否 stale，optimistic-lock `rowVersion` 防止 accept / reject / expire / internal consume 并发覆盖；
- execution status 与 consumption status 分离。用户可感知结果等待确认；Planner / Evaluator 等内部结果由
  owning Workflow 核对 step/version 后自动消费或标记 stale；站内 polling 不等于把 Push Notification /
  Learning Recall scheduler 拉回 V1。

本次讨论增加了对 timeout、external side effect、async job 与 message broker trade-off 的设计理解，但没有
新增对应 production code 或真实运行 evidence，因此 Model Gateway Ownership 仍为 `L2`。

### M0-S6E approved design learning — final deadline 与 safe exception boundary

- S6E 与 M0-S9 不是同一个 worker：S6E model-call ExecutorService 控制同步 Adapter 的最终 deadline；M0-S9
  TaskExecutor 负责 Job lifecycle、interactive waiting 和结果持久化。两者若共用 bounded fixed pool，Job
  worker 可能占满线程并等待同池 Provider task，产生 starvation / deadlock；
- `executionTimeout` 属于 selected route 的 execution policy，不属于用户侧 TextGenerationRequest，也不是
  TextGenerationResponse 的业务内容。它必须为 positive Duration，且 Gateway 外层 deadline 与 Adapter 的
  Provider client timeout 使用同一个值，避免两套无关配置产生难以解释的行为；
- 本地 timeout 与 `future.cancel(true)` 只是 best effort。它能停止 Backend 等待，却不能证明 Provider 未收到
  请求、未完成生成或未计费，因此 timeout 不自动触发 retry / cross-provider fallback；
- Adapter 只通过 checked `ModelProviderCallException` 暴露可归一化的 operational failure kind 与 optional
  retryAfter。raw Provider response、SDK exception / cause、Prompt、Credential 和 arbitrary message 不得穿过
  Gateway boundary；未知 RuntimeException、null result 与 route mismatch 保持 programming / wiring bug；
- S7 的 transient Credential 必须显式传递到实际 Provider call。普通 ThreadLocal 不会因为任务被提交到另一个
  ExecutorService 就自动可靠传播，不能把 Credential availability 建立在该隐式假设上；
- S6E 已按批准 Scope 实现并完成 focused tests、S6A-S6E regression、server compile 与 Diff Review。Explain
  Back 已确认用户能够区分 model-call Executor worker 与 caller wait，理解 `cancel(true)` 只是 best effort，
  能说明 route identity 来自 selected `TextGenerationRoute`，并能区分 programming bug 与明确分类的 Provider
  operational failure；
- 当前 evidence 仍来自 fake Adapter，没有 concrete Provider HTTP / SDK、真实 response classification 或
  Credential execution path，因此 Model Gateway Ownership 维持 `L2`。S6E 当前为 `READY_TO_COMMIT`。

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
