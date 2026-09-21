# Adaptive Scenario Conversation — 有支架的真实沟通

> Updated: 2026-09-10
> Product / Architecture Scope: APPROVED — 2026-09-10
> V1 delivery gate: `M2C — Minimum Adaptive Text Conversation`，位于 M2 后、M3 前
> Implementation: NOT_STARTED
> Current Slice Contract: NOT_APPROVED

## 1. Goal

DailyLanguage 不以“每天和 AI 聊若干分钟”作为核心学习结果。Adaptive Scenario Conversation 需要回答：

> 用户在什么辅助条件下完成了哪些沟通行为，下一次如何减少辅助，并能否在变化后的场景中独立完成。

V1 只交付一个受控的 text conversation vertical slice。它复用现有 `LanguageProfile`、Planner、Practice、
Evaluator、Learning Memory、Review、Context Manager 与 Model Gateway，不新增第二套 Session、课程进度或
长期能力 authority。

长期产品仍面向多语言成年学习者；V1 首批验证聚焦已有一定阅读或词汇基础、但在日常沟通中依赖中文、
示例或简短回避性回答的 `en + zh-CN` 学习者。这是 validation wedge，不改变长期 Product Audience。

## 2. Product terminology

以下三种能力必须明确区分：

| 名称 | 含义 | 当前状态 |
|---|---|---|
| Guided Scenario Practice | 示范、理解检查、句框辅助与受控迁移 | M1-S8T backend baseline COMPLETE；最小 UX 尚未交付 |
| Adaptive Scenario Conversation | 围绕 communication goal 的受控多轮 text interaction | V1 Scope APPROVED；implementation NOT_STARTED |
| Turn-based Voice | STT / Conversation / TTS 的非实时语音通道 | M5 Scope；implementation NOT_STARTED |

`Text Practice / Conversation` 不得再被用来暗示上述三种能力已经同时交付。Voice transport、Listening、
Pronunciation Assessment 与 Communication capability 也不能互相替代 Evidence。

## 3. V1 feature contract

### Goal

围绕一个有限、版本化的 communication scenario，让用户完成基本回应，并在任务确实要求展开时，通过自然
追问或逐级辅助继续表达；系统记录真实辅助条件，在后续变化场景中验证更少辅助的使用。

### Trigger

- 用户随时可以显式请求“需要思路”“给我关键词”“这个怎么说”；
- Text UI 可以在配置的 inactivity interval 后只显示非打断式“需要思路吗？”入口；
- inactivity offer 不能自动展开提示，不能被解释为用户不会，也不直接形成 Learning Evidence；
- Voice 中复杂的 hesitation detection、semantic endpointing 与个体化阈值不属于 V1。

### Happy path

```text
LearningTask + Scenario + Communication Goal
  → Adaptive Text Conversation
  → task-appropriate learner response
  → natural follow-up when the goal requires clarification / elaboration
  → optional assistance request
  → TOPIC_DIRECTIONS → KEYWORDS → RESPONSE_FRAME → HOW_TO_SAY
  → learner re-expresses in target language
  → Session-level Evaluation
  → qualified assistance / communication Evidence
  → later changed-context transfer
  → re-planning
```

### Non-goals

- 不以回答长度直接判断能力；简短但自然、完成任务的回答可以是成功；
- 不默认逐句 Grammar correction；
- 不把中文输入当作独立目标语言表现；
- 不由 Conversation Agent 直接修改 Weakness、Level、Mastery 或 Review State；
- 不在 V1 建立无限动态场景、完整口语课程或大规模 topic graph；
- 不把 STT success/failure 当作 pronunciation Evidence。

## 4. Assistance ladder

V1 使用最小、可解释的辅助层级：

1. `TOPIC_DIRECTIONS`：提供可以从哪些内容角度回答，不给完整目标句；
2. `KEYWORDS`：提供有限关键词或搭配；
3. `RESPONSE_FRAME`：提供可填充的表达框架；
4. `HOW_TO_SAY`：根据用户中文意图提供目标语言表达候选。

辅助应按用户请求逐级暴露，不因迟疑自动跳到完整答案。使用 `HOW_TO_SAY` 后，用户需要重新用目标语言完成
当前表达；该结果仍是 assisted production。后续只有在变化场景中不打开答案性辅助的表现，才可以成为
independent transfer candidate。

Content 应提供明确 communication goal，以及适用于该目标的有限 topic directions 或 response dimensions；
LLM 可以在受控 Context 中选择自然追问和措辞，但不能自由改变任务目标、伪造 assistance state 或决定长期状态。

## 5. Cafe example

场景目标不仅是回答味道如何，还包括在自然追问后从至少一个感官或偏好维度展开。

```text
Staff: How does it taste?
Learner: It tastes great!
Staff: Glad you like it. What stands out most—the aroma,
       the sweetness, or the texture?
```

`It tastes great!` 是合法、自然的基本回应，不能因为简短而判错。只有 communication goal 明确要求
`ELABORATE` 时，系统才继续追问。用户打开话题提示后可以看到：

- flavor / sweetness / bitterness；
- aroma；
- texture；
- temperature；
- comparison or personal preference。

若用户进一步请求句型，才展示类似 `It tastes ___, and it has a ___ aroma.`。用户随后完成的表达记录为
对应辅助条件下的成功，而不是无辅助成功。

## 6. Evidence semantics

| 观察事实 | V1 解释 | 禁止推断 |
|---|---|---|
| 系统显示“需要思路吗？”入口 | UI offer / optional telemetry | 用户迟疑、不会或存在 Weakness |
| 用户打开 topic directions | 使用非答案型内容提示 | 独立展开成功 |
| 用户打开 keywords | 使用 lexical support | 已掌握这些表达 |
| 用户打开 response frame | 使用答案性句型辅助 | independent production |
| 用户用中文请求表达 | `HOW_TO_SAY` assistance request | 目标语言能力成功或失败 |
| 用户随后用目标语言表达 | assisted production | 稳定 Mastery |
| 变化场景中无答案性辅助完成 | independent transfer candidate | 绕过 qualification / aggregation 直接改变长期状态 |

`offered`、`provided`、`opened` 和实际 learner response 必须可区分。offer 本身不进入长期能力判断；一次 assistance
request 也不能直接形成 Weakness。多轮 Conversation 可能在同一 turn 出现多次辅助与重新表达，因此不得在未设计
清楚 cardinality、replay identity 和 overwrite rule 前强行复用 M1 一次性 response snapshot。

## 7. Module and authority boundaries

| Module | V1 responsibility | Must not |
|---|---|---|
| Content | versioned scenario、communication goal、有限 topic directions / response dimensions | 发布未经验证的无限课程 |
| Planner | 根据长期状态选择合法 task、难度与支架起点 | 生成完整对话或修改长期状态 |
| Conversation Runtime | 维护 scenario progress、recent turns、assistance usage 与受控多轮互动 | 把关键状态只留在 LLM Context |
| UI | 显式 assistance entry、非打断式 offer、真实 opened / requested interaction | 用 inactivity 自动宣告不会 |
| Evaluator | 结合整个 interaction Context 评价 task completion、clarification、elaboration 等表现 | 对 support / assistant text 形成 learner claim |
| Evidence / Memory | qualification、assisted / independent 区分、聚合与 replay | 单次模型判断直接改变长期状态 |
| Review / Planner | 在变化场景中安排更少辅助的后续任务 | 维护第二份 mastery truth |

Evaluator 可以使用 trusted scenario state 与 assistant turns 解释上下文，但需要归因给学习者的 semantic issue 仍必须
ground 到真实 learner turn / span。Conversation Model failure 时保留已接受的 Practice interaction；不得伪造评价、
辅助条件或长期状态。Provider-free guided practice 与 Model-backed adaptive conversation 不得在同一 Session 中静默切换。

## 8. V1 delivery allocation

| Gate | Approved scope | Explicit non-scope |
|---|---|---|
| M1-S11 | 让现有 guided scenario 可使用，并可信记录静态 scaffold 的真实暴露 | Adaptive multi-turn Conversation |
| M2 | qualification、assistance / independence aggregation、Profile projection 与 re-planning foundation | Conversation Runtime |
| M2C | 一个 `en + zh-CN` adaptive text scenario、显式求助、渐进辅助、Session evaluation 与变化场景 transfer proof | Voice、pronunciation scoring、无限场景 |
| M3 | 将经 dogfooding 验证的 scenario / directions 接入 versioned Content production boundary | 用 RAG 结果直接决定 learner state |
| M4 | 完成“先教我 / 直接尝试”、辅助回退、Review 与完整 transfer UX | advanced assessment |
| M5 | 在 turn-based Voice 中提供显式 assistance entry，并保持 Listening / Communication / Pronunciation Evidence 分离 | realtime voice、复杂 hesitation detection |
| M6 | 对 unnecessary prompting、assistance semantics、transfer、Model failure 与 interaction quality 建立 Eval | 用轮数、时长或字数替代 Learning Outcome |

`M2C` 位于 M2 exit criteria 通过后、M3 implementation 前，不重编号 M3–M6。具体 schema、API、event contract、
Context strategy、Prompt、model route、scenario artifact、tests 与 slice breakdown 必须在独立 Design / Current Slice
Contract 中批准。此处不授权 implementation。

## 9. Backlog boundary

以下能力不进入 V1：realtime full-duplex voice、barge-in、semantic endpointing、个体化 hesitation threshold、
从声音推断情绪或信心、phoneme / prosody pronunciation scoring、逐句实时纠错、无限动态场景、大规模 topic graph、
完整口语课程、Avatar 与复杂游戏化。统一记录见 `docs/planning/BACKLOG.md` 的 Advanced Adaptive Spoken
Conversation Coaching 条目。

## 10. Stop point

```text
Product / Architecture Scope: APPROVED
V1 Scope allocation: APPROVED
Implementation Slice: NOT_APPROVED
Production Code Change: NONE
Next allowed action: finish current M1 work; after M2 scope completion, design M2C slices
```
