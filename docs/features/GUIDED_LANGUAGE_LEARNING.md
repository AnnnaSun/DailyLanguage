# Guided Language Learning — 输入、理解与独立使用

> Updated: 2026-09-10
> Direction: APPROVED — 用户同意补充教学流程并复用现有模块
> Product design / V1 phase allocation: APPROVED — 2026-09-07
> Current Slice Contract: APPROVED — S8T-A / S8T-B / S8T-C
> Implementation: S8T-A COMPLETE (`71751f5`)；S8T-B COMPLETE (`f2eefa6`)；S8T-C implementation / Review /
> external verification PASS，documentation COMPLETE，整体 Ownership PASS，`READY_TO_COMMIT`，未 commit

## 1. Goal and current evidence

系统需要帮助用户接触并理解新语言内容，再逐步转为独立使用。输入包括可理解的对话、文章、
例句和音频，也包括必要的词义、表达用途和简短规则说明；不能把输入仅等同于知识卡片或语法课。

当前已发布 `en-builtin-cafe-request/v2`：`TargetPracticeCore` 通过 `learningPurpose` 区分理解检查、辅助使用与
独立迁移，`SupportScaffold.guidedSteps` 为每个 guided step 提供中文 instruction，并只在
`SCAFFOLDED_USE` 暴露答案性 `responseFrame`。Practice start 下发安全教学投影；`practice_response` 持久化
四类 support-condition snapshot。当前 HTTP submit 无法确认真实暴露条件，因此显式保存 `UNKNOWN`，不伪造
无辅助成功。Reading / Listening / Language Fundamentals 的逻辑设计仍不等于已交付。

本设计补充 Practice 内的教学行为，不新增顶层 Learning module、独立 Learner Model 或第二套 Session。
已批准更新 V1 范围及 Phase exit criteria；M1-S8T 复用 Content、Planner、Practice Runtime 与 Evaluator input，
没有新增顶层 Learning module、课程状态或长期学习 authority。M2 qualification / aggregation、S11 UI 暴露追踪、
后续 Review 调度与完整课程体系仍未实现。

## 2. User learning flow

以下区分 M1 当前实现与后续目标；环节名称不是新的 Session 状态或课程进度 authority。

| 环节 | M1 当前实现 | 后续边界 |
|---|---|---|
| 接触新内容 | v2 `targetLanguageText` 与中文 explanation 提供咖啡店示范 | S11 决定具体呈现和用户选择入口 |
| 理解意思与用法 | start projection 下发 instruction / explanation / hint | 是否打开、何时打开由 UI/runtime 后续记录 |
| 理解检查 | `comprehension-check`：`COMPREHENSION_CHECK + EXACT` | 当前只形成本次 deterministic result |
| 辅助使用 | `order-with-frame`：`SCAFFOLDED_USE + EXACT`，下发 `responseFrame` | M2 才解释为有条件的学习 Evidence |
| 独立迁移 | `order-water-freely`：`INDEPENDENT_TRANSFER + SEMANTIC_ONLY`，无 `responseFrame` | 当前不伪造 semantic correctness；由既有 Evaluator 产生候选诊断 |
| 辅助条件 | response 首次写入时原子保存四类 exposure；当前 HTTP 路径均为 `UNKNOWN` | S11/runtime 才能提供可信的 `PROVIDED / OPENED / NOT_PROVIDED` |
| 后续复习 | 尚未实现 | Review 与调度规则留给后续 Phase |

这不是每次必走的线性课程。已有基础用户可以直接尝试；遇到困难可以选择“先教我”、
查看解释、重试、降低难度或跳过。不能因为跳过教学就推断已掌握。
Reading / Listening 可以以理解为当前任务目标，不要求每次都产出语言。
新手可保留更多辅助；证据不足时采用保守支架，不把“尚未学过”解释成长期 Weakness。

Planner 根据目标、已有能力证据和可用材料选择当前学习需要；教学顺序和内容由明确材料设计承载，
不要求运行时 LLM 自由生成课程。独立环节仍可允许合理辅助，但必须如实解释其表现条件。

## 3. Coffee-shop example

目标：理解示范中的咖啡点单、在句型辅助下提出请求，再对新商品进行无句型迁移。当前通过新的 v2 发布；
v1 内容保持 immutable，并转为 `HISTORICAL_ONLY`，仍可按 exact identity 解析。

1. 示范：咖啡师询问 `What can I get for you?`，顾客回答
   `Could I have a medium coffee, please?`。
2. 简短解释：`Could I have …, please?` 是常见且安全的礼貌点单表达。
3. 理解检查：`comprehension-check` 询问顾客点了什么；它是 `EXACT`，不代表独立表达。
4. 辅助使用：`order-with-frame` 展示 `Could I have ___, please?`，要求点一杯 medium coffee。
5. 迁移尝试：`order-water-freely` 改为购买一瓶水，不下发 `responseFrame`；该步为 `SEMANTIC_ONLY`。
6. 后续复习：尚未实现；时间间隔和调度规则留给 Review 的后续实现设计。

文本示例不证明听力能力。未来提供音频必须按已批准的 Listening / Voice Scope 交付。
一次隐藏示范后的成功也不证明稳定掌握；刚接触过的表达与隔日、跨场景使用需要区分。

## 4. Evidence and learning-state boundary

| 观察事实 | 可支持的判断 | 不能据此声称 |
|---|---|---|
| 展示或打开材料 | 系统呈现过内容，或用户打开过内容 | 用户认真阅读、理解或掌握 |
| 理解问题答对 | 对指定问题、材料与辅助条件下的理解表现 | 全面理解、独立表达或整体 Level 提升 |
| 参考示范或句型完成 | 有相应辅助的使用成功 | 无辅助使用成功 |
| 新条件下无答案性辅助完成 | 本次相应任务的独立使用证据 | 稳定 Mastery 或长期 Weakness 关闭 |
| 多次间隔、跨场景表现 | 可供既有 qualification / aggregation 判断的证据 | 绕过聚合规则直接改变长期状态 |

当前已保留可追溯的 material identity/version、任务目标、实际回答、评价来源，以及与 response 同行首次写入的
`ResponseSupportCondition`。四个独立维度为 demonstration、explanation、hint 与 responseFrame；每个维度取值
`UNKNOWN / NOT_PROVIDED / PROVIDED / OPENED`。migration 前历史与当前 HTTP submit 均使用 `UNKNOWN`，不能把
缺失或未知记录默认为未使用辅助，也不能把点击或停留时间当作认知事实。

教学示范与评分答案的可见性应明确区分：可以展示为教学准备的例句，不因此暴露内部完整
accepted answers / rubric。历史结果按当时材料版本和辅助条件解释，不重新标注旧 Session 为独立成功。

所有个人记录继续按 `languageProfileId` 隔离；`supportLanguage` 只控制辅助表达。
Evaluator 产生 Session-level diagnosis / candidate，Java 负责 validation / qualification，
Learning Memory 负责聚合后的长期状态。不存在独立的“课程完成即掌握”路径。

## 5. Module responsibilities and alternatives

| 现有模块 | 目标职责增量 | 保留的边界 |
|---|---|---|
| Content | 已承载 typed learning purpose、逐 step scaffold 与 cafe v2 | versioned、可追溯；Public Reference 仍只是参考 |
| Planner | 已选择唯一 `PLANNABLE` cafe v2，并保留 exact identity | 不生成完整课程，不直接写能力状态 |
| Practice Runtime | 已投影 guided scaffold，并持久化四类辅助条件 snapshot | 当前 HTTP 只写 `UNKNOWN`；不自行决定掌握 |
| Evaluator | trusted input 已携带 durable response/support condition | 不把模仿当独立表现，不直接修改长期状态 |
| Learning Memory | 按证据类型与条件聚合 | 不以看过内容或课程进度替代能力判断 |
| Review | 安排值得再次出现的内容 | 不承担完整 Planner 或维护另一份 mastery truth |

首选复用现有模块：职责与既有闭环一致，代价是后续需要设计材料结构、交互与证据条件。
仅增加解释文本成本最低，但无法完整表达过程和表现条件。新增独立 Learning module 会引入任务、
Session 与状态归属重叠，当前没有独立生命周期或 dependency boundary 证据支持它。

Grammar Repair 继续用于真实使用后重复、高置信的问题。首次接触表达时主动提供必要的简短
语法或用法说明属于教学支架，无需等待反复犯错；它不创建 Weakness、不触发 Repair lifecycle，
也不将 Grammar 变成主要学习路径。Language Fundamentals 继续承担文字与基础发音门槛。

## 6. Failure and verification criteria

- 材料或必要教学内容不可用时，明确说明不可用，允许返回或选择其他可用任务；不伪造完成或理解证据。
- 用户不会、跳过或中途退出时保留真实交互事实，不据此直接创建长期 Weakness。
- 评价失败时保留学习过程，能力结果为未确认；不把完成教学当作评价成功。
- 没有 Provider 时，已发布的 Built-in 路径仍通过本地已验证内容执行；AI enrichment 不成为硬依赖。
- 设计验收：咖啡店示例可走通；新手与直接尝试入口清晰；理解任务可独立结束；辅助成功与独立成功可区分。
- M1 工程验收：guided material validation、legacy default、exact-version planning/start、safe projection、
  support-condition migration/round-trip、owner/profile isolation 与 Evaluator input regression 已由 unit/integration
  tests 覆盖。
- 当前能力限制：HTTP submit 只保存 `UNKNOWN`，因此 M1 不能宣称某次回答真实使用或未使用辅助；S11/runtime
  暴露追踪、M2 qualification/aggregation 与后续 Review 尚未实现。
- 学习有效性 UNKNOWN：未来通过减少辅助后的迁移与后续表现验证；工程测试和一次 dogfooding 不证明普遍有效。

## 7. Delivery gate

最小教学闭环已纳入 V1 P0。用户围绕一个沟通目标，在系统内接触新表达、理解用途、借助提示练习，
再尝试减少辅助后的使用；系统能够区分这些表现并支持后续复习。完整零基础课程体系仍不在范围内。

| Phase | 已批准交付 | 验收重点 |
|---|---|---|
| M1 | S8 结束后插入 S8T 最小文本教学；S11 提供相应最小 UX | 至少一个 en + zh-CN 场景具备示范、解释、理解检查、辅助使用与必要辅助条件记录 |
| M2 | 教学表现接入既有 qualification、aggregation 与下一次规划 | 看过、理解、辅助使用与独立使用不混同；UNKNOWN 不当作独立成功 |
| M3 | 教学材料结构进入内容生产与发布 | 按任务提供适用教学内容并验证，检索结果不直接成为教材 |
| M4 | 完成先教我 / 直接尝试、减少辅助、迁移与后续复习体验 | 至少一个目标完成端到端教学验收 |
| M5 | 扩展音频输入、听力理解与听说任务 | 保留相应辅助条件，不跨能力维度推断成功 |
| M6 | 教学回归与实际使用验证 | 核对证据语义，记录迁移表现及样本局限 |

`M1-S8T` 独立于 S8A–E，在 S9 前交付，保留既有 S9–S12 编号。实现按三个已批准 slice 完成：

- S8T-A `Guided Material Contract`：COMPLETE (`71751f5`)；
- S8T-B `Response Support Condition Persistence`：COMPLETE (`f2eefa6`)；
- S8T-C `Guided Cafe Material Delivery`：implementation / Critical Review / external verification /
  documentation PASS，未 commit。

Fresh closeout evidence（2026-09-09）：S8T targeted unit 168/168；disposable PostgreSQL 18.6 empty schema
Flyway V1–V14；Planner 7/7、Practice 35/35、Grounded Evaluator Reader 5/5 integration，共 47/47 PASS；
临时容器已删除，primary database 未使用。2026-09-10 完整 S8T Ownership Check PASS：用户能够解释
`TextStepKind` 与 `TextLearningPurpose` 的正交语义、`UNKNOWN` 与 `NOT_PROVIDED` 的证据差异、首次 response/support
snapshot 不可覆盖、exact material version 的历史可重现性，以及单次 candidate 不直接修改长期状态；Understanding
`UNDERSTOOD`，Human Touch `NOT_REQUIRED`。当前下一 Gate 是 S8T-C Commit Decision；不自动 commit 或开始 M1-S9。

## 8. Reference

多邻国官方流程介绍提供“围绕沟通目标、从识别到更独立作答、穿插复习”的产品参考：
[How to Use Duolingo](https://blog.duolingo.com/duolingo-101-how-to-learn-a-language-on-duolingo/)。
显式解释与例句中的规律学习参考：
[Does Duolingo Teach Grammar?](https://blog.duolingo.com/does-duolingo-teach-grammar/)。
查阅日期：2026-09-07。以上不是本产品学习效果的验证证据，也不要求复制具体课程或功能。
