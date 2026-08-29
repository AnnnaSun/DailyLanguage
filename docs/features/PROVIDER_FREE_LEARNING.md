# Provider-free Learning Baseline

> Status: APPROVED DESIGN  
> Approved: 2026-08-29  
> Implementation scope: NOT_APPROVED  
> V1 phases: M1 / M2 / M3 / M5

本文定义用户未提供 Model Provider 时仍可执行的最小学习路径。它补充
`ADR-0003` 已批准的 deterministic fallback 与 bounded AI dependency，但不建立第二套 offline
Tutor、Planner、Evaluator、Learning Memory 或长期状态 authority。

首个 Built-in Content Pack 已批准使用：

```text
targetLanguage = en
supportLanguage = zh-CN
```

`targetLanguage` 是用户正在学习、并由 `languageProfileId` 隔离学习状态的语言；
`supportLanguage` 只用于翻译、解释、提示和其他学习支架，不产生独立学习状态，也不改变
`Language Profile` 的归属。首个 Content Pack 的起始能力范围和内容数量留到 M1 Scope Decision，
不得把建议值提前写成已批准范围。

## 1. Product goal

用户没有提供 Text、Speech 或 Image Provider 时，仍可以：

- 进入目标语言 workspace；
- 获得合法的 Built-in Practice；
- 完成不依赖 Model 的学习交互；
- 获得与可信来源相符的 deterministic feedback；
- 产生可解释的 deterministic assessment / Evidence；
- 在 M2 之后进入同一 Learning Memory、Review 与 re-planning 链路。

产品对用户区分：

```text
Built-in Practice
AI-enhanced Practice
```

Provider-free 不是需要向用户暴露的异常或失败模式。它也不等于 full offline：V1 的 Built-in
Practice 可以依赖 Backend 获取任务并持久化状态；PWA cache、pending event 与跨设备同步继续遵守
现有 offline Scope。

## 2. Capability boundary

### 2.1 Available without Provider

- 短 Reading；
- 固定 Dialogue；
- Vocabulary / Expression recognition 与 recall；
- 选择、排序、匹配和受控填空；
- 有限分支的 Scenario Roleplay；
- 翻译、提示等 assistance；
- objective comprehension；
- deterministic feedback；
- M2 之后的 Review、Progress 与 re-planning。

### 2.2 Requires a later Model or Phase

- 自由生成的 AI Conversation；
- 开放文本的 semantic correction 与 naturalness 判断；
- 任意内容生成、Image Understanding 或 Image Generation；
- STT、任意文本 TTS 与 pronunciation scoring；
- M3 imported content adaptation、RAG 与 Controlled Multi-role Agent Workflow；
- M5 Listening / turn-based Voice。

固定、经过验证并随 Content Pack 发布的音频可以在 M5 作为 Built-in Listening material；浏览器
或设备 TTS 只能作为经过 capability check 的可选 UX，不得成为 V1 audio authenticity、评分或
Evidence authority。

## 3. Trigger and user-visible behavior

Provider-free path 可在以下条件触发：

- 用户未提供当前 operation 所需 Credential；
- 当前请求不存在可用 Model capability；
- 用户主动选择 Built-in Practice；
- Model-required task 失败后，用户显式选择 Replace with Built-in Practice。

Planner 在创建任务前根据当前语言和可用能力过滤合法 candidate。当前语言没有 Built-in Content
时，系统必须明确显示该语言暂时没有可用 Built-in Practice；不得借用其他目标语言内容，也不得把
不支持的任务伪装成可启动状态。

已经启动的 Model-required Session 发生 Provider failure 时：

```text
Preserve current PracticeSession
    ↓
Record explicit Model / Evaluation failure status
    ↓
Do not silently change task semantics
    ↓
User may explicitly create a replacement Built-in task
```

不得在同一 `PracticeSession` 内把自由 AI Conversation 静默切换为 scripted dialogue。

## 4. Target flow

```text
UserContext + LanguageProfile
        ↓
Available Practice Modes
        ├─ Built-in Content availability for targetLanguage
        ├─ request-scoped Model capability
        └─ optional client / device capability
        ↓
Java eligible candidate generation
        ↓
Java hard constraint filtering
        ↓
Built-in Learning Material selection
        ↓
LearningTask
        ├─ practice mode
        ├─ materialId
        ├─ materialVersion
        └─ required / optional capability semantics
        ↓
PracticeSession
        ↓
Trusted Practice Events
        ↓
DeterministicAssessment
        ↓
Qualified Evidence
        ↓
M2 Learning Memory / Review / Re-planning
```

`Available Practice Modes` 是业务概念，不批准通用 capability engine、dynamic registry、Boolean
flag 组合或 provider-specific Domain type。M1 Current Slice Design 必须选择最小、typed 且可追踪的
表达方式。

## 5. Built-in Content authority

M1 baseline 使用 Backend-owned、versioned Built-in Content artifact。推荐使用 classpath JSON
resource，并在 build / test 或 startup boundary 验证结构与业务语义；M1 不为此新增 Content database
schema。

Client 可以获得和缓存执行当前 Practice 所需的内容，但不能成为 content validation、deterministic
answer 或长期学习状态的唯一 authority。

每个 Built-in Learning Material 至少需要表达：

- stable `materialId`；
- `version`；
- `targetLanguage`；
- `supportLanguage`；
- difficulty / level band；
- Practice type；
- scenario；
- learning objectives；
- typed interaction steps；
- deterministic answer / rubric；
- available assistance；
- provenance / license。

`PracticeSession` 与后续 Evidence 引用 `materialId + version`。Content update 必须形成可追踪的新版本，
不得通过覆盖旧内容改变历史 Session / Evidence 的解释依据。

Built-in Content 是共享的已发布学习材料，不复制为每个用户的一套内容。所有用户交互、Assessment、
Evidence 和长期状态仍必须归属于当前 `languageProfileId`。

## 6. Assessment and Evidence boundary

Provider-free Practice 可以形成的 deterministic assessment 包括：

- exact / rule-verifiable answer；
- correct / incorrect attempt；
- assistance usage；
- assisted / independent completion；
- objective comprehension；
- constrained recognition / recall；
- Practice completion。

以下信息默认只属于 Session Data 或 qualification input，不能单独形成长期能力事实：

- 用时；
- 点击次数；
- 选择某个 scripted dialogue branch；
- 主观难度；
- 单次使用翻译或提示；
- 未经过 semantic evaluation 的自由文本。

M1 Provider-free baseline 只选择核心学习结果可进行 deterministic assessment 的任务。它不引入
长期等待未来 Model 的自由写作队列，不增加 retroactive semantic evaluation、重复 Evidence 或新的
`EvaluationRun` lifecycle。后续若真实 Product Scope 需要，必须单独设计。

一次正确、一次错误或一次 assistance usage 不得直接决定 Weakness、Mastery 或 Level；M2 继续通过
Evidence qualification 与 aggregation 控制长期状态。

## 7. Content extensibility

已批准 roadmap 存在两个真实内容来源：

```text
M1 Built-in Content
M3 Curated / Imported / Published Content
```

因此 Content storage / lifecycle 是已知 Change Axis。实现应使用最窄的 Learning Material read
boundary，使 Planner / Practice 不依赖 classpath 或未来 database storage，并通过 Composition 接入
具体来源。

不得提前引入：

- dynamic Content Registry；
- generic Factory；
- Base Content Class；
- 大量 nullable field 的万能 Content DTO；
- arbitrary option Map；
- M3 publish workflow、RAG 或 Agent abstraction。

准确的 Java Port 和 Content Type 只在 M1 Current Slice Scope 中批准。

## 8. Multi-language and support-language rules

- Built-in candidate selection 必须按 `targetLanguage` 过滤；
- PracticeSession、Assessment、Evidence 与长期状态继续按 `languageProfileId` 隔离；
- `supportLanguage` 不建立另一份 Language Profile 或 mastery truth；
- support text 不得被 Evaluator 误判为 target-language learner output；
- 首个 `en + zh-CN` Content Pack 不代表系统只支持这一语言组合；
- 其他语言组合只有存在经过验证的 Content Pack 时才可声明 Built-in Practice available。

## 9. Explicit non-goals

本 Design 不批准：

- 完整零基础课程或完整 CEFR curriculum；
- 全语言 Built-in Content；
- 大规模通用词库；
- System-managed Provider；
- bundled local LLM / STT model；
- M1 Image / Audio / STT / TTS；
- full offline sync；
- 第二套 Planner、Evaluator、Learning Memory 或 mastery truth；
- M1 RAG、Tool Gateway 或 Content Agent；
- AI Session failure 后的静默模式切换。

## 10. Phase delivery

### PF-D1 — Documentation Contract

固化本 Feature Contract，并窄范围同步 `V1_SCOPE.md` 与 `V1_PHASE_PLAN.md`。不修改 Production
Code、schema、API、Security 或当前 Phase implementation status。

### M1-PF1 — Built-in Text Material Boundary

加载并验证最小 versioned Built-in Text Material，使 Java candidate generation 可以按目标语言、
Practice type 与 hard constraint 读取合法材料。明确不进入 Practice API、Evidence、database、audio
或 AI 调用。

### M1-PF2 — Provider-free Practice Walking Skeleton

在无 Provider 情况下完成：

```text
Language Profile
→ deterministic LearningTask
→ PracticeSession
→ objective interaction
→ DeterministicAssessment
```

验证 Model Gateway 未被调用、损坏或不匹配内容 fail closed、Session 与 deterministic result 被保留。

### M2-PF3 — Persistent Adaptation

让 Built-in Practice 的正确、错误与 assistance Evidence 进入统一 Learning Memory、Review 与
re-planning，覆盖 replay、duplicate event、assisted / independent 语义与 cross-language isolation。

### M3-PF4 — Content Productionization

在真实 dogfooding evidence 下确定有限场景包的数量与覆盖，接入正式 Content Library、provenance、
publish lifecycle 和 imported / curated content boundary。不得把 Content 直接写成长期学习状态。

### M5-PF5 — Built-in Audio

将经过验证的固定音频接入 Listening；音频缺失、损坏或播放失败不得产生虚假 comprehension、
speech recognition 或 pronunciation Evidence。

## 11. Architecture impact

```text
Architecture-sensitive Change: NO
Architecture Impact: in-boundary extension
ADR Required: NO
V1 Scope Decision: APPROVED
```

本方案保持 Java workflow authority、BYOK Credential boundary、provider-agnostic Model Gateway、
统一 Practice / Evidence / Memory 和 Hosted / Self-hosted core business path。未来若引入
System-managed Credential、bundled local Model、第二套 content authority 或新的长期状态模型，必须
重新进入 Architecture Decision。
