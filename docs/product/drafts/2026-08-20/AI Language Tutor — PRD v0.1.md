# AI Language Tutor — PRD v0.1

## 1. 产品定位

面向非应试型语言学习者的多语言 AI 个性化语言教练。

用户可以同时学习多门语言。系统分别维护每门语言的学习状态，并根据用户历史表现持续调整阅读、对话、词汇和复习任务。

核心目标：

- 提高日常沟通能力
- 提高真实文字阅读能力
- 通过长期学习记录实现个性化训练

不以考试刷题、证书和标准化题库为主要目标。

---

# 2. 核心产品价值

传统语言学习产品主要提供固定课程。

本产品核心能力是：

```text
Understand User
      ↓
Plan
      ↓
Practice
      ↓
Evaluate
      ↓
Remember
      ↓
Re-plan
```

系统持续回答：

1. 用户现在会什么？
2. 用户经常错什么？
3. 今天最值得练什么？
4. 这次训练表现如何？
5. 下一次应该针对什么继续练？

---

# 3. 核心用户

目标用户：

- 已经开始学习某门语言
- 不以考试为主要目的
- 希望进行日常交流
- 希望能够阅读真实生活中的文字
- 希望同时学习一门或多门语言
- 不知道每天应该学习什么

典型场景：

- 英语 B1，希望提高日常表达
- 日语 A1，希望完成旅行基本交流
- 西班牙语 A2，希望提高阅读和基础会话

---

# 4. 多语言设计

一个用户可以同时拥有多个 Language Profile。

```text
User
├── English Profile
├── Japanese Profile
└── Spanish Profile
```

每门语言独立维护：

- 当前等级
- Vocabulary Mastery
- Weakness
- Error History
- Practice History
- Reading History
- Conversation History
- Learning Plan

不同语言的数据不得互相污染。

首批支持：

- English
- Japanese
- Spanish

系统架构必须支持后续新增其他语言。

---

# 5. AI Learning Loop

核心闭环：

```text
Learner Profile
      ↓
Planner
      ↓
Learning Task
      ↓
Practice
      ↓
Evaluator
      ↓
Memory Update
      ↓
Learner Profile
      ↓
Planner
```

该 Loop 是项目的核心能力。

---

# 6. Learner Profile

每门语言拥有独立 Learner Profile。

主要包含：

## 基础信息

- language
- overall level
- reading level
- speaking level
- vocabulary level

## Vocabulary

词汇状态：

```text
NEW
LEARNING
WEAK
MASTERED
```

记录：

- encounter count
- correct count
- error count
- last seen
- last error
- next review

## Weakness

记录用户长期薄弱点，例如：

```text
ARTICLE_USAGE
QUESTION_WORD_ORDER
PREPOSITION_USAGE
PARTICLE_USAGE
VERB_CONJUGATION
```

Weakness 使用标准化标签。

具体解释与示例单独保存。

## Learning History

记录：

- 阅读内容
- 对话场景
- 用户原始回答
- AI 评价
- 错误
- 改进结果

---

# 7. AI Planner

Planner 根据用户当前状态生成学习任务。

输入：

- Language Profile
- 当前等级
- Weakness
- Vocabulary Mastery
- 最近训练内容
- 到期复习词汇
- 用户学习目标
- 用户当前可用时间

输出：

```json
{
  "activity": "conversation",
  "topic": "restaurant",
  "level": "B1",
  "targetVocabulary": ["available"],
  "targetWeaknesses": ["QUESTION_WORD_ORDER"]
}
```

Planner 需要：

- 优先处理持续出现的弱点
- 避免机械重复同一场景
- 将旧知识放入新语境
- 控制一次训练任务量
- 根据不同语言采用不同教学策略

---

# 8. Language-specific Prompt Architecture

不使用万能多语言 Prompt。

Prompt 按：

```text
Language × Task
```

管理。

例如：

```text
english/
├── planner
├── evaluator
├── reading
└── conversation

japanese/
├── planner
├── evaluator
├── reading
└── conversation

spanish/
├── planner
├── evaluator
├── reading
└── conversation
```

底层 Agent 流程可以共享。

语言教学规则不强制共享。

---

# 9. Vocabulary

Vocabulary 不是独立背单词 App。

核心是 Personal Vocabulary Bank。

单词主要来自：

- Reading
- Conversation
- 用户主动收藏
- Evaluator 发现
- 外部内容导入

用户可以查看：

- 单词
- 释义
- 发音
- 示例
- 当前掌握状态
- 最近出现位置
- 错误次数
- 下次复习时间

后续加入 FSRS / Spaced Repetition。

---

# 10. Reading

统一使用：

`ReadingMaterial`

而不是只使用 Article。

支持的内容类型最终包括：

- MESSAGE
- EMAIL
- NOTICE
- POST
- ARTICLE
- STORY
- MENU
- INSTRUCTION
- NEWS

## 第一阶段

AI 根据：

- 用户等级
- weak vocabulary
- weakness
- 兴趣

生成个性化 Reading Material。

阅读页支持：

- 点击查词
- 加入 Vocabulary
- TTS
- 句子解释
- 阅读理解题
- 阅读结果 Evaluation

---

# 11. Content RAG

后续建立 Learning Content Library。

来源：

- AI-generated content
- VOA
- Project Gutenberg
- LibriVox
- Wikinews
- 用户 URL
- PDF
- EPUB
- TXT / Markdown

内容进入：

```text
Parse
↓
Chunk
↓
Embedding
↓
Vector Store
↓
Metadata
```

Planner 可以根据：

- language
- level
- topic
- weakness
- vocabulary

检索适合用户的学习材料。

支持：

```text
Retrieval
→ Filtering
→ Ranking
→ Context Assembly
→ Learning Task
```

---

# 12. Personal Memory RAG

历史学习数据不能全部塞进 Prompt。

建立长期学习记忆检索。

检索内容包括：

- 历史错误
- 类似错误表达
- 学过的单词
- 过去对话
- 阅读表现
- 反复出现的 weakness

例如：

用户再次出现类似错误时：

```text
Current Error
↓
Retrieve Similar Historical Errors
↓
判断是否 recurring weakness
↓
调整 mastery / weakness score
```

目标：

区分：

- 一次性错误
- 长期反复错误

---

# 13. Conversation

Conversation 是核心练习模式之一。

支持预定义场景：

- Restaurant
- Hotel
- Airport
- Shopping
- Taxi
- Small Talk
- Workplace
- Travel

Conversation Agent 根据：

- Language Profile
- 目标场景
- target vocabulary
- target weakness

推动用户使用目标知识。

后续支持语音：

```text
User Voice
↓
STT
↓
Conversation Agent
↓
TTS
```

---

# 14. Tool Calling

Agent 不直接拥有业务数据。

通过 Tool 获取和修改系统状态。

核心 Tools：

```text
getLearnerProfile()

getWeakPoints()

getDueVocabulary()

searchDictionary()

searchLearningHistory()

searchLearningMaterials()

savePracticeResult()

updateVocabularyMastery()

updateWeakness()

scheduleReview()
```

LLM 负责：

- 判断是否需要调用
- 选择 Tool
- 生成参数

应用负责：

- 参数校验
- 权限检查
- Tool 执行
- 状态更新
- 错误处理

---

# 15. Evaluator

Evaluator 独立于练习 Agent。

主要负责：

- 判断任务完成度
- 检查表达错误
- 判断词汇是否正确使用
- 判断 weakness
- 生成结构化学习结果

Evaluator 输出固定 Schema。

区分：

```text
LANGUAGE_ERROR
NATURALNESS_ISSUE
TASK_COMPLETION
```

避免把“没有完成任务”错误记录成 Grammar Weakness。

---

# 16. Memory Update

Evaluator 不直接随意修改 Learner Profile。

通过 Memory Update Layer：

```text
Evaluation
↓
Normalize
↓
Validate
↓
Update Mastery
↓
Update Weakness
↓
Save History
```

Memory 必须支持：

- 状态变化
- 历史追踪
- 重复错误累计
- 正确使用后逐渐提升 mastery

---

# 17. Context & Token Optimization

长对话不能无限携带完整历史。

使用：

## Recent Window

只保留最近若干轮完整消息。

## Session Summary

旧消息压缩成结构化 Summary：

```text
Scenario
Completed goals
Current weaknesses
Vocabulary already used
Remaining goals
```

## Relevant Memory Retrieval

历史学习记录通过 RAG 检索。

不全量加载。

## Agent-specific Context

不同 Agent 只读取自己需要的信息。

例如：

```text
Planner
→ Profile + Weakness + Recent Practice

Evaluator
→ Current Task + Current Response + Rubric

Conversation
→ Scenario + Recent Context + Learning Goals
```

记录：

- input tokens
- output tokens
- cached tokens
- latency
- estimated cost

用于后续优化。

---

# 18. AI Output Quality

所有生成内容采用：

```text
Generate
↓
Validate
↓
Publish
```

检查包括：

- 格式
- Language Level
- Vocabulary Constraint
- Grammar Quality
- Content Quality

事实型内容后续增加：

- source grounding
- Fact Validator

AI Reading 与 TTS 均需要逐步建立质量验证体系。

---

# 19. Evaluation / Evals

项目需要建立独立 Eval Dataset。

至少覆盖：

## Prompt Eval

- Planner
- Evaluator
- Reading Generator
- Conversation Agent

## RAG Eval

- retrieval relevance
- recall
- grounding

## Tool Calling Eval

- tool selection
- parameter correctness
- unnecessary calls
- failure handling

## Agent Eval

- task completion
- weakness utilization
- memory utilization
- incorrect state mutation

测试集按语言隔离：

```text
eval/
├── en/
├── ja/
└── es/
```

---

# 20. Observability

所有 AI 调用必须拥有 Trace。

记录：

- traceId
- model
- provider
- prompt version
- language
- task
- input
- output
- tool calls
- retrieved context
- token usage
- latency
- cost
- success / error

目标：

出现异常时可以回答：

> 是哪个 Prompt、模型、RAG 结果或 Tool 导致的问题？

---

# 21. Voice

支持：

- STT
- TTS
- Voice Conversation

Pronunciation Assessment 单独设计。

普通 TTS 不作为发音学习的绝对标准。

后续加入：

- pronunciation accuracy
- fluency
- stress
- prosody
- phoneme-level analysis

---

# 22. 功能阶段

## V1 — Core Learning Product

实现：

- Multi-language Profile
- English / Japanese / Spanish
- Initial Assessment
- Vocabulary Bank
- AI Planner
- AI Reading
- Scenario Conversation
- Evaluator
- Learning Memory
- Basic Tool Calling
- Basic Trace
- PWA 基础界面

目标：

真实跑通：

```text
学习
→ 犯错
→ 记录
→ 后续再次训练
→ 状态变化
```

---

## V1.5 — AI Engineering Enhancement

加入：

- Personal Memory RAG
- Content RAG
- 用户内容导入
- Tool Calling 扩展
- Context Compression
- Token Tracking
- Prompt Version
- Eval Dataset
- RAG Eval
- Tool Eval

---

## V2 — Multimodal Learning

加入：

- STT
- TTS
- Voice Conversation
- Pronunciation Assessment
- Listening
- Writing
- Grammar Training
- FSRS

---

# 23. Backlog

以下功能必须保留，不因阶段未实现而删除：

## Learning

- Pronunciation Training
- Listening
- Writing
- Grammar
- FSRS
- 完整词书
- 自定义词表

## Content

- VOA
- Gutenberg
- LibriVox
- Wikinews
- News
- RSS
- URL Import
- PDF
- EPUB
- TXT / Markdown
- Original / Adapted / Explain

## AI

- Advanced RAG
- Hybrid Search
- Reranking
- Fact Validator
- CEFR Validator
- LLM-as-Judge
- Model Routing
- Model Drift Monitoring
- Long-term Semantic Memory

## Product

- Push Notification
- Offline
- Streak
- Achievement
- Daily Goal
- Reminder
- Social
- Ranking

---

# 24. 面试项目目标

项目最终需要能够清晰展示：

1. Agent Loop
2. Planner
3. Tool Calling
4. Structured Memory
5. Long-term Memory
6. RAG
7. Context Engineering
8. Token / Cost Optimization
9. Prompt Engineering
10. Multi-language Prompt Architecture
11. Structured Output
12. Evaluation
13. Observability
14. Voice / Multimodal
15. AI Reliability

最终核心 Demo：

```text
用户练习
↓
产生错误
↓
Evaluator 识别
↓
Memory 记录
↓
历史错误进入长期 Memory
↓
Planner 下次调用 Tools / RAG
↓
主动安排针对性训练
↓
用户再次正确完成
↓
Mastery 提升
```

当这一条链路完整运行时，该项目达到主要 AI 面试项目标准。