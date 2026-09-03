# Public Language Reference Sources

> Status: APPROVED DESIGN
> Approved: 2026-09-02
> Updated: 2026-09-03 — M1 English / Japanese Built-in lineage
> Implementation scope: NOT_APPROVED
> V1 phases: M1 / M3 / M5

本文定义 V1 对词典、语料、发音参考和语言 / 考试规范等公共语言资料的只读 source boundary。
该能力为 Provider-free Learning、Content preparation 与 grounded retrieval 提供可追溯参考，不建立新的
Learner Model、Content authority 或长期学习状态 truth。

## 1. Decision summary

V1 建立：

**read-only、versioned、带 provenance / license 的 Public Language Reference Source Boundary。**

Provider-free runtime 必须解析到本地已验证、不可变的 Built-in artifact，不得把 Model Provider、公共
source 的实时可用性或外部网络作为 Practice 可执行的硬依赖。

M3 可以通过 typed read-only operation 接入经过批准的公共 source，用于 reference lookup、Content
preparation 或 retrieval。公共结果只作为 Reference / Context；它不能直接修改 Learning Memory、Weakness、
Mastery、Level 或其他长期学习状态。

M1 初始交付限定为已批准的：

```text
targetLanguage = en
supportLanguage = zh-CN

targetLanguage = ja
supportLanguage = zh-CN
```

两种 target language 使用同一 Public Reference boundary，并分别通过 immutable source lineage 支撑
Target Practice Core 与 Chinese Support Scaffold。该 delivery matrix 不代表完整语言覆盖，也不自动批准
具体 external source、connector 或更大内容数量。

## 2. Problem and product value

Provider-free Learning 已要求 Built-in Content 具备稳定 `materialId + version`、deterministic rubric、
provenance 与 license。但如果没有独立 source contract，词义、例句、能力描述和音频可能散落在 Content
artifact、Prompt 或数据库中，难以回答：

- 内容来自哪里、按什么版本解释；
- 是否允许复制、修改、缓存或随 Self-hosted distribution 再分发；
- source 更新是否会改变历史 Practice / Evidence 的含义；
- 公共检索是否接触了个人学习数据；
- reference result 是否被错误提升为 Learner Model truth。

Public Language Reference Sources 通过明确 source lineage、read-only capability 和 data-domain isolation
解决这些问题。它支撑 Provider-free Learning，但不把产品变成大型语言数据平台、通用搜索引擎或考试题库。

## 3. Source categories

V1 识别以下 logical source category：

| Category | V1 role | Must not imply |
| --- | --- | --- |
| Dictionary | definition、part of speech、notation、translation / explanation、expression reference | 用户已掌握该词或某个词义绝对正确 |
| Corpus | grounded example、frequency / register / scenario metadata、collocation reference | 语料频率等于教学优先级或用户 Weakness |
| Pronunciation Reference | 固定参考音频、phonetic metadata、locale / accent、source quality | 单一标准口音、STT 结果或 pronunciation scoring |
| Language / Exam Specification | 官方能力 descriptor、skill / level / version reference | 完整 curriculum、自动 Level mutation 或考试成绩等价关系 |

具体 source 选择、license conclusion、adapter 和覆盖规模必须在相应 Phase slice 单独批准。

## 4. Public and personal data domains

### 4.1 Public Reference Domain

公共 source 和其批准的 snapshot 可以包含：

- source catalog / manifest；
- dictionary entry 或有限 lexical subset；
- corpus sentence / chunk 与 register、scenario、frequency metadata；
- pronunciation reference asset 与 locale / accent metadata；
- language / exam descriptor；
- canonical URL、publisher、license、attribution、source version、retrieval time 与 content hash。

Public Reference 可以由不同用户共享，不复制为每个 `languageProfileId` 一套数据。

### 4.2 Personal Learning Domain

以下数据始终属于个人学习或私有内容边界：

- Language Profile、Level、Weakness、Mastery、Review State；
- PracticeSession、用户回答、Conversation、Evidence、Learning Memory；
- assistance / independence event；
- User Import 及其 private retrieval index；
- account、Credential、identifier 与其他用户数据。

个人学习数据继续通过 `userId` / `languageProfileId` ownership 和 language isolation 管理。公共 source
不能成为访问或推断这些数据的入口。

## 5. Query minimization and read-only capability

公共 lookup 只能接收完成当前 reference operation 所需的最小字段，例如：

```text
targetLanguage
supportLanguage
lemma / expression
languageVariant / locale / accent
register / scenario
examName + specificationVersion + skill + level
bounded limit
```

默认不得向公共 connector 发送：

```text
userId / languageProfileId
用户姓名、联系方式或 Credential
完整 Conversation 或 PracticeSession
用户原始回答
Weakness、Mastery、Level、Evidence 或 Learning Memory
User Import 的私密原文
```

内部 Planner / Content workflow 可以根据合法 structured state 判断需要查询的 reference，但必须在
outbound boundary 收敛为非个人、最小化的 typed query。禁止通过自由 metadata Map、完整 Prompt 或原始
Context 绕过该约束。

Logical read-only operations 包括：

```text
Dictionary Lookup
Corpus Search
Pronunciation Reference Lookup
Language / Exam Specification Lookup
```

这些 operation 的 query、result、failure 和 provenance 不应被压缩为一个万能 DTO。准确 Java Port、
Source Adapter 与 Tool Contract 只在相应 implementation slice 中批准。

## 6. Provider-free runtime flow

M1 / M5 Built-in runtime 使用已验证 snapshot，而不是 live source：

```text
Approved Public Source
        ↓ controlled import / curation
License + Quality + Provenance Validation
        ↓
Immutable Versioned Source Bundle
        ↓
Published Built-in Content / Audio Artifact
        ↓
Provider-free Practice Runtime
```

Provider-free Practice 在 Model、公共 source 或外部网络不可用时仍应执行已发布 artifact。source 更新必须
产生新 bundle / material version，不得覆盖旧 artifact 并改变历史 Session / Evidence 的解释依据。

## 7. M3 retrieval and content preparation flow

M3 可以在现有 Content / RAG / Tool Gateway 下增加 Public Reference 子边界：

```text
Typed Public Reference Query
        ↓
Read-only Source Adapter
        ↓
Schema / License / Provenance Validation
        ↓
Public Reference Result
        ↓
Content Retrieval / Lesson Design / Quality Review
        ↓
Java Validate / Publish
```

Public connector failure只能使当前 lookup 或 preparation 明确失败、降级到已经发布的本地 artifact，或等待
用户选择；不得静默改变 Practice 语义，也不得删除或修改已经确认的个人学习状态。

## 8. Source manifest and result contract

每个批准 source 至少需要记录：

- stable `sourceId` 与 `sourceCategory`；
- publisher 与 canonical URL；
- source / dataset / specification version；
- license identifier、attribution requirement 与允许的使用方式；
- Hosted / Self-hosted redistribution decision；
- supported language、variant、locale / accent 与内容范围；
- update / retrieval method 与 freshness metadata；
- quality / review status；
- source record identity、retrieved / published time 与 content hash；
- failure、deprecation 与 source removal policy。

缺少必要 license、version 或 provenance 的 source / record 不得进入 Built-in publication boundary。公开可访问
不等于允许抓取、修改、缓存或再分发；法律和 license 结论不能由模型猜测。

## 9. Storage and retrieval isolation

V1 不要求为了该 boundary 新建独立数据库。实现可以复用 Content Library、PostgreSQL、pgvector、Redis 和
Object Storage，但必须保持 logical isolation：

```text
Public Reference
  sourceId + sourceVersion + targetLanguage + public record identity

Personal Memory / User Import
  userId + languageProfileId + ownership / permission metadata
```

公共 reference index 与个人 Memory / User Import index 必须使用可审计的独立 namespace、record type 或
强制 metadata filter。组合检索仍需保留 result domain 和 provenance，不得先混成没有来源与权限语义的 chunk。

Public reference cache key 不以个人学习数据构造；cache 不得成为 source version、license 或长期状态的唯一
authority。

## 10. Learning-state authority

公共 result 最多可以：

- 为 Built-in / Content Practice 提供 grounded material；
- 为 Planner 提供候选任务的 reference constraint；
- 为 Evaluator 提供 task-specific rubric / specification context；
- 为用户展示 definition、example、pronunciation reference 或标准说明。

公共 result 不能直接：

- activate / close Weakness；
- 修改 Level、SkillMastery、VocabularyMastery 或 CommunicationSkill；
- 形成用户已经成功或失败的 Evidence；
- 把 corpus frequency 当作用户学习优先级；
- 把 language / exam descriptor 当作考试成绩或长期能力事实。

长期学习状态仍必须经过：

```text
Practice
  ↓
Trusted Event / Evaluator Candidate
  ↓
Qualified Evidence
  ↓
Aggregation / deterministic state transition
```

## 11. Failure, security and verification

V1 implementation 应按相关 source 风险覆盖：

- source timeout、rate limit、invalid response 与 unavailable；
- schema、enum、language / variant 与 provenance validation；
- license / attribution metadata 缺失时 fail closed；
- public query 不接受 personal field 或任意 metadata；
- public / personal retrieval result domain 不可混淆；
- prompt injection 或不可信 corpus text 不能获得 Tool / state authority；
- source / bundle version replay 能解释历史 Content / Practice；
- cross-user、cross-languageProfile 与 cross-language isolation；
- audio 缺失、损坏或 variant 不匹配时不产生虚假 Evidence。

Trace 优先记录 source、operation、version、result status、latency、cache / retrieval metadata 和 content identity，
不记录 Secret、完整个人回答或不必要的私密 Context。

## 12. V1 delivery

### M1-PLR1 — Built-in Source Lineage

为 `en + zh-CN` 第一条 walking skeleton 与 `ja + zh-CN` validation pack 建立最小 source manifest /
lineage，并验证 published material provenance 可以解析 Target Practice Core、selected Support Scaffold 与
不可变 bundle version。M1 不接 live public connector，不新增 RAG、Content database 或通用 registry；具体
external source 与 license conclusion 仍需在对应 Content slice 中批准。

### M3-PLR2 — Public Source Catalog and Read-only Text Operations

在现有 Content / RAG / Tool Gateway boundary 下实现最小 Public Source Catalog、typed read-only operation、
query minimization 与 provenance validation。至少接入一个经过批准的 dictionary / lexical reference source 和
一个经过批准的 curated corpus source；具体 source 与 coverage 由 M3 Scope Decision 确认。

### M3-PLR3 — Public / Personal Retrieval Isolation

为 Public Reference、Personal Memory 与 User Import 建立可验证的 namespace / filter / result-domain isolation，
并覆盖 outbound personal-data rejection、cross-user、cross-languageProfile 与 cross-language tests。

### M3-PLR4 — Language / Exam Specification Reference

允许有限、官方、带版本的 language / exam descriptor 作为 optional goal、difficulty 或 rubric context。V1 不实现
完整 exam curriculum、考试题库或未经官方依据的 score equivalence。

### M5-PLR5 — Verified Pronunciation Reference Audio

将经过批准的固定音频、locale / accent、text alignment、quality、license 与 provenance 接入 Built-in Listening。
Pronunciation scoring 与任意文本 TTS authority 不进入该 slice。

## 13. Explicit non-scope

本 Design 不批准：

- 全语言 Public Source Bundle；
- 大规模通用词库或完整公共语料镜像；
- 任意网页抓取、通用搜索引擎或无 license ingestion；
- 完整 CEFR / exam curriculum、考试题库或 score equivalence；
- pronunciation scoring 或唯一标准口音；
- live public source 作为 Provider-free runtime 硬依赖；
- 公共 connector 接收个人学习数据或 User Import 私密原文；
- Public / RAG result 直接修改长期学习状态；
- 新顶层数据平台、万能 Source interface、dynamic registry 或 arbitrary metadata Map；
- 当前 M0 Production code、schema、API 或 phase status 变化。

## 14. Architecture impact

Architecture Impact: `APPROVED — V1 scope and Content / Retrieval sub-boundary`

- 不改变 Persistent Learner Model、AI vs Java Authority 或 Multi-language Isolation；
- 不建立新的顶层 module，Public Reference 归属于现有 Content / RAG / Tool Gateway 规划边界；
- 明确公共参考数据与个人学习 / 私有导入数据的权限、检索和状态 authority；
- 将 Provider-free Content 的 provenance / license 从 material field 提升为可验证 source lineage；
- 具体 source、Java Port、schema、index、connector、cache 与 Tool API 均需后续 slice 单独批准。
