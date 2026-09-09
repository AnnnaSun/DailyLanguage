# Guided Language Learning — 输入、理解与独立使用

> Updated: 2026-09-07
> Direction: APPROVED — 用户同意补充教学流程并复用现有模块
> Product design / V1 phase allocation: APPROVED — 2026-09-07
> Current Slice Contract: NOT_APPROVED；Implementation: NOT_STARTED

## 1. Goal and current evidence

系统需要帮助用户接触并理解新语言内容，再逐步转为独立使用。输入包括可理解的对话、文章、
例句和音频，也包括必要的词义、表达用途和简短规则说明；不能把输入仅等同于知识卡片或语法课。

当前已有 `TargetPracticeCore.targetLanguageText` 与 `SupportScaffold` 的 explanation / hint，
`en-builtin-cafe-request/v1` 包含点单表达和中文解释。它们证明内容支架已存在，不证明完整教学流程、
支架使用记录或学习效果已实现。Reading / Listening / Language Fundamentals 的逻辑设计也不等于已交付。

本设计补充 Practice 内的教学行为，不新增顶层 Learning module、独立 Learner Model 或第二套 Session。
已批准更新 V1 范围及 Phase exit criteria；当前 M1-S8 Contract 不变。本次只同步文档，
不修改材料版本、API、schema 或评分实现。

## 2. User learning flow

以下为目标行为，尚未实现；环节名称不是新的持久化状态或强制 API enum。

| 环节 | 系统提供什么 | 用户做什么 |
|---|---|---|
| 接触新内容 | 与沟通目标相关的短示范，控制新词和表达数量 | 阅读或听取有意义的语言内容 |
| 理解意思与用法 | 可按需查看的词义、解释、例句与少量规则说明 | 理解表达何时使用，选择需要的辅助 |
| 理解检查 | 与原材料意义相关的问题 | 判断或回答信息，允许重看和重听 |
| 辅助使用 | 示例、句型或局部提示 | 替换信息、模仿或完成受控沟通 |
| 独立迁移 | 改变商品、人物或场景，减少答案性提示 | 在新条件下理解或完成沟通 |
| 后续复习 | 后续再次出现的相关内容或任务 | 在间隔后重新理解、回忆或使用 |

这不是每次必走的线性课程。已有基础用户可以直接尝试；遇到困难可以选择“先教我”、
查看解释、重试、降低难度或跳过。不能因为跳过教学就推断已掌握。
Reading / Listening 可以以理解为当前任务目标，不要求每次都产出语言。
新手可保留更多辅助；证据不足时采用保守支架，不把“尚未学过”解释成长期 Weakness。

Planner 根据目标、已有能力证据和可用材料选择当前学习需要；教学顺序和内容由明确材料设计承载，
不要求运行时 LLM 自由生成课程。独立环节仍可允许合理辅助，但必须如实解释其表现条件。

## 3. Coffee-shop example

目标：理解点单中的常见问答，并能礼貌提出自己的请求。以下为新设计示例，不是对现有 v1 材料的修改。

1. 示范：`What can I get for you?` / `Could I have a small tea, please?` /
   `For here or to go?` / `To go, please.`
2. 简短解释：`Could I have …?` 用于礼貌请求；`for here` 是店内用，`to go` 是带走。
   解释服务于本次交流，不要求先学习完整情态动词体系。
3. 理解检查：顾客点了什么？是在店内用还是带走？允许回看原文；答对只支持相应阅读理解判断。
4. 辅助使用：保留 `Could I have ___, please?`，让用户改为点咖啡。
5. 迁移尝试：隐藏示范和句型，换为购买一瓶水；若用户打开提示，仍可继续，但结果不能记为无辅助成功。
6. 后续复习：在后续任务重新安排相关请求表达；时间间隔和调度规则留给 Review 的实现设计。

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

未来实现至少需要可追溯的 material identity/version、任务目标、实际回答、评价来源，以及相关辅助条件。
区分“提供了辅助”“用户请求或打开辅助”与 UNKNOWN；不能把缺失记录默认为未使用辅助，
也不能把点击或停留时间当作认知事实。具体 event / schema / API 留待独立 implementation contract。

教学示范与评分答案的可见性应明确区分：可以展示为教学准备的例句，不因此暴露内部完整
accepted answers / rubric。历史结果按当时材料版本和辅助条件解释，不重新标注旧 Session 为独立成功。

所有个人记录继续按 `languageProfileId` 隔离；`supportLanguage` 只控制辅助表达。
Evaluator 产生 Session-level diagnosis / candidate，Java 负责 validation / qualification，
Learning Memory 负责聚合后的长期状态。不存在独立的“课程完成即掌握”路径。

## 5. Module responsibilities and alternatives

| 现有模块 | 目标职责增量 | 保留的边界 |
|---|---|---|
| Content | 组织示范、解释、理解问题与关联练习 | versioned、可追溯；Public Reference 仍只是参考 |
| Planner | 选择接触新内容、辅助练习或迁移需要与支架强度 | 不生成完整课程，不直接写能力状态 |
| Practice Runtime | 承载教学交互、用户选择及辅助条件记录 | 复用 Session 生命周期，不自行决定掌握 |
| Evaluator | 结合表现条件解释理解与使用证据 | 不把模仿当独立表现，不直接修改长期状态 |
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
- 实现验收待定：材料验证、辅助记录、owner/profile isolation、失败路径与评价语义需在批准的 slice 中测试。
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

`M1-S8T` 独立于 S8A–E，在 S9 前安排，保留既有 S9–S12 编号。首个实现前必须设计最小辅助记录合同，
不能等到 M2 才尝试补推历史条件；记录不意味着 M1 提前实现长期聚合。
咖啡店作为首个设计用例，具体发布材料与起始能力要求在 Current Slice Contract 中确认。

当前允许的后续动作是在完整 S8 收口后设计 S8T Current Slice Contract：明确材料、交互、
API / schema impact、评价兼容性与验证，必要时拆分，再批准实现。现有 S8 的剩余 Gate 继续按原合同执行。
本次批准不授权立即创建 schema、修改旧材料、启动课程引擎或扩充全语言课程。

## 8. Reference

多邻国官方流程介绍提供“围绕沟通目标、从识别到更独立作答、穿插复习”的产品参考：
[How to Use Duolingo](https://blog.duolingo.com/duolingo-101-how-to-learn-a-language-on-duolingo/)。
显式解释与例句中的规律学习参考：
[Does Duolingo Teach Grammar?](https://blog.duolingo.com/does-duolingo-teach-grammar/)。
查阅日期：2026-09-07。以上不是本产品学习效果的验证证据，也不要求复制具体课程或功能。
