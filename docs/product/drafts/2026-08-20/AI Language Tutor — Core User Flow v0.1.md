# AI Language Tutor — Core User Flow v0.1

## 0. 核心设计原则

产品目标是帮助用户真正使用语言进行日常交流、阅读和本地生活沟通，而不是构建考试刷题型语言学习 App。

核心 AI 闭环：

```text
Language Profile
→ Planner
→ Practice
→ Evaluator
→ Learning Memory
→ Profile Update
→ Re-planning
```

长期差异化能力：

- Persistent Learner Model
- 多语言独立学习状态
- 动态 Planner
- Practice 后持续 Evaluator
- Learning Memory
- Personal Memory RAG
- Communication & Fluency
- Weakness 自动发现与修复
- Continuous Assessment
- Context / Token Optimization
- Tool Calling
- Trace / Eval
- 后期 Optimization Loop

Grammar 仅作为 `Grammar Repair` 短时修补能力，不作为主要学习路径。

---

# Flow 1：新用户首次开始学习语言

## 目标

让用户尽快完成第一次真实学习，而不是长时间配置系统。

## 流程

```text
注册 / 登录
→ 添加目标语言
→ 自评当前水平
→ Initial Assessment
→ 建立 Language Profile
→ 设置基础 Learning Preferences
→ 必要时进入 Language Fundamentals
→ Planner 生成首个任务
→ Practice
→ Evaluator
→ Learning Memory
→ 更新 Profile
→ Today
```

## 首次只要求必要信息

- 目标语言
- 大致水平
- 主要学习目标
- 每日预计学习时间

例如：

```text
Japanese
零基础
目标：日常交流
每天：20 分钟
```

Provider、Review、Conversation Assist 等高级设置不阻塞首次学习。

---

# Flow 2：Initial Assessment

## 目标

快速形成一个可工作的初始能力画像，不模拟正式 CEFR/JLPT/DELE 考试。

## 流程

```text
用户自评
→ 选择起始难度
→ Vocabulary
→ Reading
→ Writing
→ 可选 Speaking
→ 动态调整题目难度
→ Initial Profile
```

建议时长：

```text
5–10 分钟
6–10 个动态任务
```

主要能力：

- Vocabulary
- Reading
- Writing / Text Expression
- Speaking / Oral Expression（Voice 可用时）

输出：

```text
English

Overall: B1

Reading: B1+
Vocabulary: B1
Writing: A2+
Speaking: Not Evaluated

Potential Weakness:
QUESTION_WORD_ORDER
ARTICLE_USAGE
```

初始等级只是初始假设，之后由真实学习数据持续校正。

---

# Flow 3：老用户每日学习

## 入口

Today / Learning Hub。

## 流程

```text
打开 App
→ 进入当前语言 Today
→ Planner 读取：
   Profile
   Weakness
   Communication Skills
   Vocabulary
   Review State
   Recent Practice
   Preferences
→ 生成今日建议
→ 用户接受 / 修改
→ Practice
→ Evaluator
→ Memory
→ 更新 Profile
→ Planner 动态调整剩余任务
```

例如：

```text
今天 20 分钟

Reading 8 min
Conversation 8 min
Review 4 min
```

用户可以直接说：

- 今天只有 5 分钟
- 今天只练口语
- 今天不要 Vocabulary
- 换个主题
- 换一篇 Reading

Planner 重新规划。

Planner 不要求一天只规划一次。

前一个 Practice 的结果可以改变后续任务。

---

# Flow 4：用户自由开始 Practice

Planner 不是所有学习的强制入口。

用户可以直接进入：

- Reading
- Conversation
- Writing
- Listening
- Vocabulary
- Language Fundamentals
- Grammar Repair

流程：

```text
用户选择 Practice
→ 读取当前 Language Profile
→ 自动适配难度和训练目标
→ Practice
→ Evaluator
→ Memory
→ Profile / Progress Update
```

自由学习产生的数据与 Planner 推荐任务同样进入长期学习闭环。

---

# Flow 5：短期错误 → 长期 Weakness

## 短期错误

每次错误先保存为：

```text
Error Event
```

例如：

```text
Original:
Is available a table?

Error:
QUESTION_WORD_ORDER
```

一次错误不会直接成为 Weakness。

## 聚合

Learning Memory 综合：

- 出现次数
- 时间跨度
- 不同场景是否重复
- Evaluator Confidence
- 最近正确表现

形成：

```text
Weakness:
QUESTION_WORD_ORDER

severity: MEDIUM
confidence: HIGH
evidenceCount: 3
```

## 消除

后续正确使用会降低 Weakness：

```text
MEDIUM
→ LOW
→ INACTIVE
```

历史 Error Event 保留。

如果以后再次反复出现，可以重新激活。

---

# Flow 6：Review / Weakness 回到训练

Review 主要作为后台学习机制，不强制用户每天清 Review Queue。

流程：

```text
Learning Memory
→ 找到值得再次验证的内容
→ Review System
→ Planner 判断是否安排
→ 优先嵌入真实 Practice
→ Evaluator
→ Memory
```

例如 `QUESTION_WORD_ORDER` 不一定安排语法题，可以安排：

```text
机场改签 Conversation
目标：主动提出多个问题
```

用户可以选择：

- 自动穿插复习
- 专项 Review
- 关闭主动复习

默认推荐自然穿插。

---

# Flow 7：多语言学习

用户可以同时拥有：

```text
English
Japanese
Spanish
...
```

每种语言拥有独立：

- Language Profile
- Vocabulary
- Weakness
- Communication Profile
- Error History
- Review State
- Progress
- Planner Context
- Learning Preferences

切换语言：

```text
English Context
→ Japanese Context
```

英语 Weakness 不得污染日语。

允许共享的主要是：

- 全局用户兴趣
- 全局时间偏好
- UI 设置

Today 可以支持：

```text
单语言模式
全部语言概览
```

---

# Flow 8：用户导入学习内容

支持：

- URL
- PDF
- EPUB
- TXT
- Markdown

流程：

```text
Import
→ Content Pipeline
→ 正文解析
→ Language Detection
→ Difficulty Analysis
→ Metadata
→ 保存 My Library
```

之后用户可以选择：

- Original Reading
- Adapted Reading
- Listening
- Translation Practice
- Vocabulary Extraction

原始内容永远保留。

AI Adapted Version 是派生内容，不覆盖原文。

导入不等于自动进入训练。

---

# Flow 9：Conversation

## 核心形式

采用半结构化 Conversation：

```text
Scenario
+ Communication Goals
+ Optional Learning Targets
```

不给用户固定台词。

AI 根据用户实际回复动态继续。

## 用户卡住时的辅助梯度

```text
给我思路
→ 给我关键词
→ 给我句型
→ 这句话怎么说
```

AI 自己的话用户看不懂时：

```text
换个简单说法
```

也可以：

```text
给我三个回复方向
```

Conversation 默认不频繁中断纠错。

优先让用户把对话继续完成，结束后集中 Evaluator。

## 新表达

AI 引入的新词或表达可以：

```text
Add to Vocabulary
```

## 结束

```text
Conversation Session
→ Evaluator
→ Error Events
→ Communication Evidence
→ Memory
→ Profile Update
```

用户频繁使用帮助可以作为学习证据，但一次求助不能直接形成 Weakness。

---

# Flow 10：Communication & Fluency

这是区别普通语言学习 App 的核心能力。

## 训练目标

包括：

- Follow-up Questions
- Topic Initiation
- Topic Shift
- Clarification
- Conversation Repair
- Turn Taking
- Elaboration
- Naturalness
- Register / Politeness
- Everyday Expressions
- Listening Interaction
- Fluency

## 示例任务

```text
场景：
第一次和新同事吃午饭

目标：
主动追问 2 次
自然回应对方
主动开启一个话题
听不懂时主动澄清
```

Conversation Agent 正常交流，不直接告诉用户：

> 现在请完成第二个训练目标。

## 结果

```text
Communication Profile

Follow-up Questions    DEVELOPING
Clarification          GOOD
Naturalness            DEVELOPING
Register               GOOD
```

可能形成：

```text
COMM_FOLLOW_UP
COMM_TOPIC_SHIFT
COMM_CLARIFICATION
FLUENCY_HESITATION
PRAGMATICS_REGISTER
NATURAL_EXPRESSION
```

Grammar 关注语言结构是否正确。

Communication & Fluency 关注：

> 能否继续交流、自然表达、正确处理真实互动。

---

# Flow 11：Reading

流程：

```text
选择 Reading Material
→ Original / Adapted
→ 阅读
→ 查词 / Sentence Explain / TTS
→ 可选 Translation Practice
→ Comprehension
→ Evaluator
→ Memory
```

Reading 支持：

- 单词查询
- 收藏 Vocabulary
- 句子解释
- TTS
- 显示 / 隐藏翻译
- Original / Adapted 切换

## Translation Practice

```text
原文
→ 用户自己翻译
→ 提交
→ Reference Translation
→ AI Evaluation
```

评价：

- Semantic Understanding
- Missing Information
- Vocabulary Misunderstanding
- Grammar Misunderstanding
- Context Misunderstanding

不要求与 Reference Translation 完全一致。

## Reading 难度判断

结合：

- 理解题
- 查词次数
- Explain 次数
- Translation 结果
- 用户主观难度反馈
- Material Difficulty

不能因为用户查很多词就直接降低 Reading Level。

---

# Flow 12：Writing

流程：

```text
Writing Prompt
→ Draft 1
→ Evaluator
→ Feedback
→ 可选 Retry
→ Draft 2
→ Final Result
→ Memory
```

反馈层级：

1. Task Completion
2. Language Errors
3. Naturalness
4. Suggested Revision
5. Reference Version

默认不直接用 AI 完整答案覆盖用户原文。

优先让用户自己修改。

可以记录：

```text
第一次错误
→ 获得提示
→ 第二次能否自己修正
```

这是重要的能力证据。

---

# Flow 13：Listening

流程：

```text
Audio
→ 首次纯听
→ 理解题 / 简短复述
→ 可选辅助
→ 再次作答
→ Session Performance
→ Evaluator
→ Memory
```

辅助梯度：

- Replay
- Slow Playback
- Sentence Replay
- Keywords
- Partial Transcript
- Full Transcript

Listening 诊断维度：

- General Comprehension
- Detail Comprehension
- Speech Recognition
- Vocabulary Comprehension
- Sentence Parsing

每次 Listening 只产生：

```text
Session Performance
+ Error Evidence
```

不会因为一篇材料直接修改整体等级。

多次跨难度表现后，才更新 Listening Level。

---

# Flow 14：Vocabulary

Vocabulary 来源：

- Base Vocabulary / Dictionary
- Reading
- Listening
- Conversation
- Writing
- 用户主动查词
- Planner 推荐

状态：

```text
NEW
LEARNING
WEAK
MASTERED
```

Vocabulary Entry 记录：

- word / expression
- meaning
- POS
- pronunciation
- example
- source
- encounterCount
- errorCount
- usage evidence
- mastery state

真正掌握不能只看“看过多少次”。

理解和主动使用都应作为 Evidence。

---

# Flow 15：Grammar Repair

原 Grammar Training 降级为短时修补能力。

触发：

```text
某类语言结构反复出错
→ Grammar Repair
```

例如：

```text
SER / ESTAR 持续混淆

→ 2 分钟解释
→ 2–3 个微型练习
→ 马上回到真实 Conversation / Writing
```

Grammar Repair 不作为主要学习路径，也不需要形成传统“语法课程体系”。

优先级低于 Communication & Fluency。

---

# Flow 16：Language Fundamentals

针对存在文字系统门槛的语言按需启用。

例如：

### Japanese

- Hiragana
- Katakana
- Basic Kanji Recognition
- Character → Pronunciation
- Basic Input

### Spanish

只需要轻量处理：

- ñ
- accented vowels
- pronunciation / spelling rules

### English

通常跳过。

流程：

```text
Initial Assessment
→ 判断文字系统掌握程度
→ 必要时 Fundamentals
→ 很快进入 Vocabulary / Reading
```

目标是尽快跨过文字门槛。

不做重型：

- 描红
- 手写识别
- 笔顺训练

这些进入 Backlog。

---

# Flow 17：Progress

Progress 主要回答：

- 我现在什么水平？
- 最近哪里进步了？
- 现在主要问题是什么？

展示：

```text
English B1

Reading        B1+
Listening      B1
Writing        B1
Speaking       A2+

Communication

Follow-up      GOOD
Naturalness    DEVELOPING
Clarification  GOOD
```

重点展示变化证据：

```text
QUESTION_WORD_ORDER
错误率：18% → 6%

Listening B1
首次理解率：55% → 76%

Conversation
主动追问：0.7 → 2.1
```

学习时长、连续学习天数可以存在，但不是能力进步的核心指标。

---

# Flow 18：Continuous Assessment

用户等级主要通过日常学习持续变化。

流程：

```text
Practice
→ Skill Evidence
→ Learning Memory
→ 长期聚合
→ Skill Level 缓慢变化
```

例如：

```text
Listening
A2+ → B1
```

不需要每次重新考试。

每个 Level 建议包含：

```text
level
confidence
```

例如：

```text
Speaking: B1
confidence: 0.51
```

数据不足时，系统知道该等级可信度有限。

---

# Flow 19：Milestone Check

用于校准 Continuous Assessment。

触发：

- 某项 Skill 接近升级
- Confidence 过低
- 用户主动重新评估

流程：

```text
Skill 接近升级
→ 轻量 Milestone Check
→ 陌生主题
→ 标准难度任务
→ Evaluator
→ 与历史 Evidence 综合
→ Level Update
```

只测试需要校准的 Skill。

不会突然重新考所有能力。

允许跳过。

---

# Flow 20：Practice Feedback

Reading / Writing / Conversation / Listening 后提供轻量反馈。

例如：

```text
这次难度怎么样？

太简单
刚刚好
太难
```

也可以提供模块相关可选反馈。

反馈作为：

```text
UserFeedbackEvent
```

## 三种用途

### Planner

短期调整下一次 Practice。

### Memory / Profile

作为辅助 Evidence。

一次反馈不直接改变 Level。

### 产品 Optimization

后期再做。

现阶段不优先实现群体反馈驱动的自动产品优化。

---

# Flow 21：Explore / Content Library

用户不依赖 Planner，也可以自己找材料。

支持：

- Search
- Language Filter
- Difficulty
- Topic
- Content Type
- My Imports
- Favorites
- Recently Learned

内容来源：

- System Content
- AI Generated
- Open Content
- User Imports

用户等级只影响排序和提示，不硬限制。

例如：

```text
你的 Reading：A2
Material：B2

可能偏难

[直接阅读]
[Adapt to My Level]
[收藏]
```

---

# Flow 22：AI Provider / BYOK

BYOK：

```text
Bring Your Own Key
```

即用户提供自己的模型 API Key。

两种模式：

```text
System Managed
My API Key
```

自定义可以支持：

- OpenAI
- DeepSeek
- Gemini
- OpenAI-Compatible
- ...

API Key 必须由后端安全处理。

前端不能明文长期保存。

## 模型异常

区分：

- Timeout
- Network Error
- Provider Error
- Invalid API Key
- Quota Exceeded
- Structured Output Error

模型输出失败：

```text
Schema Validation
→ Repair / Retry
→ 仍失败则结束
```

错误输出不能写入 Learning Memory。

BYOK 失败时不能偷偷切换系统模型。

必须由用户确认。

---

# Flow 23：Local-first Data Architecture

PWA 采用：

```text
Local-first
+ Minimal Cloud Sync
```

## 本地保存

主要使用 IndexedDB。

保存：

- Full Conversation History
- Error Events
- Practice History
- Writing Draft
- Reading / Listening Sessions
- Detailed Learning Memory
- Local Vocabulary Data
- Imported Content metadata

较大文件未来可以考虑 OPFS。

## Cloud 保存最小恢复状态

保存：

- Account
- Language Profile
- Skill Level
- Weakness Summary
- Communication Profile
- Vocabulary Mastery
- Review State
- Progress Snapshot
- Preferences
- Recent Key Evidence

不无限保存全部历史。

## Evidence Window

例如：

```text
Weakness:
QUESTION_WORD_ORDER

Recent Evidence:
error #182
error #191
correct #207
correct #215
```

只保留有限关键证据。

---

# Flow 24：多设备恢复

新设备登录：

```text
Cloud
→ Language Profile
→ Weakness
→ Communication Profile
→ Vocabulary Mastery
→ Preferences
→ Recent Evidence
```

用户可以立即恢复核心个性化能力。

完整三个月前 Conversation 不一定自动同步。

如果需要完整迁移，则使用 Learning Vault。

---

# Flow 25：Learning Vault

支持完整导出 / 导入。

例如：

```text
learning-vault.zip

profile.json
memory.json
vocabulary.json
weaknesses.json
error-events.jsonl
practice-history.jsonl
conversations.jsonl
preferences.json
imports/
```

必须包含：

```text
schemaVersion
```

以后数据结构升级时支持 migration。

可以进一步支持加密。

未来可以允许用户自己备份到：

- iCloud Drive
- Google Drive
- OneDrive
- WebDAV

---

# Flow 26：删除与数据控制

用户可以：

- 删除单条历史
- Clear Local History
- 删除某门语言
- 删除账号
- Export Data

删除语言：

```text
只删除该语言相关学习状态
```

暂停语言：

```text
数据保留
Planner 暂停安排
```

删除账号：

```text
删除云端 Account + Core State
```

同时询问是否清除当前设备 Local Data。

---

# Flow 27：离线学习

PWA 离线时可以继续：

- Cached Reading
- Vocabulary
- Language Fundamentals
- Writing Draft
- 部分 Review
- 部分 Grammar Repair

结果保存：

```text
PracticeEvent
syncStatus = PENDING
```

恢复网络：

```text
Pending Event
→ 上传必要状态
→ Memory Processing
→ Profile Update
→ 本地同步最新状态
```

依赖实时模型的能力暂时不可用：

- AI Conversation
- Evaluator
- AI Adapt
- Content Generation

离线 Writing 可以保存为：

```text
WAITING_FOR_EVALUATION
```

联网后再评估。

客户端不自行计算最终 Weakness / Level。

---

# Flow 28：Notification / Learning Recall

通知是低优先级产品能力。

可能包含：

- 每日学习提醒
- Review 到期
- Milestone Check
- 长时间未学习
- 未完成 Practice
- 可选内容推荐

流程：

```text
Learning State
+ Preferences
→ Notification Candidate
→ User Notification Settings
→ Rate Limit / Deduplicate
→ PWA Push
→ 点击进入对应任务
```

原则：

- 默认低打扰
- 每条提醒对应具体行动
- 用户可以关闭

---

# Flow 29：AI Usage & Cost Tracking

当前阶段只做内部统计，不做正式付费系统。

记录：

```text
taskType
provider
model
inputTokens
outputTokens
cachedTokens
latency
estimatedCost
timestamp
```

用于判断：

- Conversation 平均成本
- Evaluator 成本
- Reading Generation 成本
- 每日个人使用成本
- Context Optimization 收益
- 不同模型性价比

未来商业化可以扩展：

- Subscription
- Recharge
- Credits
- BYOK
- Training Minutes

但目前全部进入 Backlog。

---

# Flow 30：Observability / Trace

每次 AI 调用保留必要 Trace：

```text
Agent
Prompt Version
Model
Input
Output
Tool Calls
RAG Results
Token Usage
Latency
Error
```

普通用户不查看完整 Trace。

用途：

- AI 错误排查
- Prompt Regression
- Token Optimization
- Tool 调用问题定位
- Eval

敏感信息禁止进入 Trace。

---

# Flow 31：Eval

Eval 判断 AI 系统是否真正工作。

覆盖：

### Planner Eval
是否真正使用 Profile / Weakness。

### Evaluator Eval
是否正确识别语言问题。

### Difficulty Eval
材料是否匹配目标 Level。

### RAG Eval
检索结果是否相关。

### Tool Eval
工具选择和参数是否正确。

### Conversation Eval
是否自然、符合训练目标。

### Language-specific Eval

分别维护：

```text
eval/en
eval/ja
eval/es
```

生产失败案例可以沉淀为 Regression Dataset。

---

# Flow 32：Optimization Loop【后期】

当前不作为首期功能。

未来可以：

```text
Trace
+ Eval
+ User Feedback
→ Problem Detection
→ Optimization Candidate
→ Regression Eval
→ Experiment
→ Human Approval
→ Release
```

不允许 AI 未经控制直接修改生产 Prompt。

---

# 最终用户核心闭环

产品最核心体验最终应该表现为：

```text
系统认识我
↓
知道我当前真正卡在哪里
↓
给我适合的真实语言任务
↓
我实际去读、听、写、说
↓
系统理解我哪里做得好、哪里困难
↓
把一次表现沉淀成长期学习记忆
↓
下一次训练发生变化
↓
随着能力提升，从语言正确性逐渐走向真实交流与自然表达
```

最终目标不是：

```text
完成了多少题
背了多少词
连续打卡多少天
```

而是：

```text
以前不会表达
→ 现在能表达

以前只能回答
→ 现在能主动追问

以前听不懂自然表达
→ 现在能参与真实对话

以前知道语法规则
→ 现在能自然使用
```

这应当作为后续详细功能设计、数据模型、AI 架构和开发优先级判断的核心依据。