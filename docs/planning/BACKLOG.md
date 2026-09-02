# AI Language Tutor — Development Backlog

## IDEA-001 — Resolve Mockito dynamic-agent warning on JDK 25

- Status: INBOX
- Priority: UNASSESSED
- Target: UNDECIDED
- Type: TECH_DEBT

### Context

`M0-S1` 的 Spring Boot context smoke test 在 Java 25 下通过，但 Mockito 为 inline mock maker 自行加载 Java agent，并提示 future JDK 将禁止这种默认行为。

### Follow-up

在首次真实使用 Mockito 或建立完整 test infrastructure 时，评估显式 Java agent 配置或更小的 test dependency boundary。当前 warning 不影响测试结果，不在 `M0-S1` 扩大修改范围。

## IDEA-002 — Re-evaluate Kafka or RabbitMQ after V1

- Status: INBOX
- Priority: UNASSESSED
- Target: POST_V1_REVIEW
- Type: FUTURE_ARCHITECTURE

### Context

当前 V1 Background Job baseline 使用 `Spring TaskExecutor + DB Job State`，不为了 Engineering Showcase 提前引入 distributed message broker。Evaluation、Evidence aggregation、content parsing / embedding / indexing、audio processing、offline Eval、Trace analytics 与后续 Notification 都可能在规模扩大后形成合理的异步消息场景。

2026-08-30 已批准将 Model Call Job / late-result recovery 纳入 V1，但这不改变 Kafka 决定：Kafka 不会自动
截获 Provider response，必须由存活 Worker 收到后主动发布；message key 不是 UI 可按 `jobId` 查询的业务
存储；Kafka delivery / exactly-once 不覆盖 transaction 外的 Provider side effect；BYOK Credential 也不得
进入 topic。V1 因此继续由 PostgreSQL 保存 Job authority 与 typed result，由 TaskExecutor 执行当前内存任务。

2026-09-02 已批准 `ADR-0004`：V1 在 Application Workflow 与 execution infrastructure 之间保留
operation-specific typed submission boundary，当前 adapter 仍是 bounded in-process `TaskExecutor`。该边界只降低
未来替换 submission mechanism 的影响范围，不增加当前吞吐，也不把本条目提升为已批准实现。

### Post-V1 Review

V1 完成后，根据真实运行数据和瓶颈重新评估：

- 现有 DB-backed job 是否存在吞吐、恢复、横向扩展或任务路由限制；
- 是否主要需要 work queue、ack、retry 与 DLQ；若是，优先评估 RabbitMQ；
- 是否出现多个独立消费者、长期 event retention、replay 或按 `languageProfileId` 分区有序处理需求；若是，再评估 Kafka；
- Model Call Job 是否真实出现跨实例吞吐、DB polling、result fan-out 或 ordering bottleneck；
- 是否值得承担额外部署、运维、监控与 Hosted / Self-hosted 交付成本。

若决定引入，必须先形成 Architecture Change Proposal，并同时设计 Transactional Outbox、idempotent consumer、retry / DLQ、event versioning、trace propagation、per-profile ordering 与 secret / private-content boundary。PostgreSQL 继续作为 structured learning state authority，消息中间件不得使 LLM output 绕过 Evidence qualification 或 Java deterministic state transition。

本条目只安排 V1 完成后的重新评估，不代表已批准引入 Kafka、RabbitMQ 或其他消息中间件。

## IDEA-003 — Evaluate the Netty macOS native DNS resolver for local Redis development

- Status: INBOX
- Priority: UNASSESSED
- Target: UNDECIDED
- Type: TECH_DEBT

### Context

`M0-S2` 引入 Spring Data Redis / Lettuce 后，Java 25 context test 与 local startup 在 macOS 上提示未找到 `io.netty.resolver.dns.macos.MacOSDnsServerAddressStreamProvider`，因此回退到 system defaults。当前 `localhost` Redis connection 与 Actuator health verification 均通过，没有影响 M0-S2 结果。

### Follow-up

在出现非 localhost Redis hostname、真实 DNS resolution 问题或建立跨平台 integration test 时，评估是否按 platform classifier 增加 `netty-resolver-dns-native-macos` runtime dependency。不要仅为消除 warning 无条件增加所有平台的 production dependency。

## IDEA-004 — Define LanguageProfile lifecycle and deletion semantics

- Status: INBOX
- Priority: UNASSESSED
- Target: M0-S5_SCOPE_REVIEW
- Type: PRODUCT_ARCHITECTURE_DECISION

### Context

Language Management 的正式 V1 设计包含 Pause / Resume、Set Primary、Delete 与 Reassess，但当前 `M0-S5` minimum use case 只明确安排 create / list / switch。`LanguageProfile` 的删除方式及其与暂停、归档、恢复的关系尚未形成正式决定。

`M0-S3` 只建立 persistence identity、ownership 与 foreign-key boundary，不提前增加 `deleted_at`、lifecycle status 或 delete use case。User → LanguageProfile 的 foreign key 暂按 `ON DELETE RESTRICT` 设计，避免父记录误删时自动清除高价值学习数据；这不代表未来禁止通过受控 workflow 删除。

### Follow-up

在 `M0-S5` Scope Review 时决定：

- Pause、Archive 与 Delete 是否是不同 lifecycle state；
- Delete 是可恢复的 logical deletion、立即 physical deletion，还是分阶段执行；
- 是否提供恢复窗口，以及由谁触发最终 purge；
- 同一 User 删除某语言后能否重新创建该语言的 `LanguageProfile`；
- `(user_id, language_code)` uniqueness 如何处理已删除记录；
- Profile 下的 Practice、Evidence、Memory 与其他语言专属状态如何清理；
- 哪些 child relationship 可以使用 `ON DELETE CASCADE`，哪些高价值边界必须保持 explicit deletion / `RESTRICT`。

本条目只要求在 `M0-S5` 实现前完成 Product / Architecture Decision，不代表上述 lifecycle 能力已经全部批准进入该 slice。

## IDEA-005 — Define User account deletion, retention, and purge workflow

- Status: INBOX
- Priority: UNASSESSED
- Target: UNDECIDED
- Type: SECURITY_DATA_LIFECYCLE

### Context

当前正式文档尚未定义 User account deletion。User 删除与单个 `LanguageProfile` 删除不是同一能力，它可能影响 Authentication identity、多个语言 workspace、Practice、Evidence、Learning Memory、Trace，以及 Hosted / Self-hosted 下的数据保留与隐私边界。

`M0-S3` 不实现 User 的 physical deletion、soft deletion、`deleted_at` 或 deletion state machine。高层 foreign key 默认阻止意外级联删除，直到受控删除流程经过独立 Scope / Architecture Review。

### Follow-up

后续根据正式 Product / Security Scope 决定：

- account deletion 是否采用 request、disable、recovery window、purge 的分阶段流程；
- 用户可见删除语义与最终 physical deletion 的时间边界；
- retention、backup、Trace 与审计数据的保留规则；
- purge 的 transaction / background job、retry、idempotency 与 failure recovery；
- Hosted / Self-hosted 是否共用同一核心 deletion workflow；
- 如何验证所有 language-specific state 均被完整且不可跨用户地清理。

本条目只保存待决策的数据生命周期问题，不代表已进入 V1 或任何当前 Phase。

## IDEA-006 — Multi-channel authentication and account linking

- Status: INBOX
- Priority: UNASSESSED
- Target: UNDECIDED
- Type: PRODUCT_SECURITY

### Context

Hosted Account 未来可能支持多个登录渠道，例如 Sign in with Apple、手机号 OTP 与其他 OIDC Provider。登录渠道不是 `User` Domain identity：一个 `app_user` 可能绑定多个经过验证的 authentication identity，而 `LanguageProfile` 与所有长期学习状态必须继续归属于稳定的内部 `userId`。

`M0-S4` 不自动实现多个外部 Provider。当前 Account schema / `UserContext` boundary 应避免把 `app_user` 永久等同于 email、手机号或 Apple 返回的 email，也不得仅根据相同 email 自动合并账号。

### Follow-up

在进入正式 Scope 前决定并验证：

- Sign in with Apple 的 token verification、issuer / audience / nonce / state、key rotation、Private Relay 与 disconnect notification；
- 手机号的 E.164 normalization、OTP provider、expiry、attempt limit、rate limit、防短信轰炸、号码回收与 account recovery；
- 多个 authentication identity 与一个 `app_user` 的绑定模型；
- link / unlink 前的 re-authentication、冲突处理与 last-login-method protection；
- duplicate account resolution、显式 merge consent 与可审计的安全流程；
- Hosted / Self-hosted authentication mode 是否共享同一个 `UserContext` contract；
- Provider credential、token 与验证材料的 persistence、encryption、rotation、redaction 和 retention boundary。

本条目只保留多渠道认证与 Account Linking 的后续 Product / Security Decision，不代表已进入 V1、`M0-S4` 或已经批准具体 Provider integration。

## IDEA-007 — Define the minimal user account profile

- Status: INBOX
- Priority: UNASSESSED
- Target: UNDECIDED
- Type: PRODUCT_DATA_MODEL

### Context

当前 `app_user` 只保存稳定的内部 `userId` 与创建时间，email 等登录标识归属于
`auth_identity`，语言、水平、目标与长期学习状态归属于独立的 `LanguageProfile`。产品需要考虑
一个不作为登录凭证、允许重复且可以修改的用户展示名，但 AI Language Tutor 的重点不是社交型
个人资料系统，不应无实际用途地收集头像、性别、生日、地区、个人简介或其他个人信息。

候选最小模型是在 `app_user` 中加入 `display_name` 与相应更新时间。用户级共享的 timezone、
UI language、默认学习时长和 notification preference 只有在 Planner、Today 或对应功能产生真实
需求时再单独决定，不得混入语言专属学习状态。Mutable profile data 不进入 `UserContext` 或
Redis `SecurityContext`；认证 authority 继续只使用稳定的内部 `userId`。

### Follow-up

进入正式 Scope 前决定并验证：

- `display_name` 是 registration 必填字段、可选字段，还是在 onboarding 中补充；
- 长度、Unicode、空白、control character 与展示层 escaping 规则；
- 是否直接扩展 `app_user`，还是已有多个真实 account preference 后再建立独立 profile / preference boundary；
- current-user API 是否返回最新 `display_name`，以及如何避免把可变昵称缓存在长期 Session 中；
- timezone、UI language、默认学习时长等用户级 preference 的真实消费者与实现时点；
- account status、头像、notification 等能力是否存在已批准 use case，避免为假想需求提前建模；
- migration、existing account backfill、registration transaction 与 profile update authorization。

本条目只保存最小 Account Profile 的后续 Product / Data Model Decision，不修改当前
`M0-S4C1` Scope，不代表已经进入 V1 或批准 database schema / public API 变更。

## IDEA-008 — Advanced content capture and content-to-transfer practice

- Status: INBOX
- Priority: UNASSESSED
- Target: POST_M3_REVIEW
- Type: FUTURE_PRODUCT

### Context

V1 `M3` 已包含 Reading / imported content 的最小训练路径和受控 Content / RAG boundary，但不要求一次覆盖网页、PDF、电子书、视频字幕等高级导入入口，也不要求把同一内容自动转换为多种 Practice。未来可以评估以用户真实兴趣内容为起点，将 contextual vocabulary、natural expression 与 source provenance 保留下来，并继续生成 Reading、Conversation 或 Writing Practice。

Content 与 Retrieval Result 只能作为 Practice / Agent Context，不能直接成为长期学习状态事实。任何内容产生的学习变化仍必须经过 Practice、Evaluator、Evidence qualification 与 Learning Memory aggregation。

### Follow-up

在 `M3` 最小 Content Pipeline 获得真实使用证据后评估：

- 网页、PDF、电子书、视频字幕等 connector 的用户价值、解析成本与版权边界；
- contextual vocabulary / expression 是否保存原句、scenario、register、source 与 language metadata；
- 同一内容如何生成 Reading、Conversation、Writing 等不同 Practice，而不建立彼此隔离的长期状态；
- furigana、pinyin 等 language-specific reading aid 如何通过集中配置扩展，避免散落的 language conditional；
- imported private content 的 storage、retention、embedding、trace 与 deletion boundary；
- 如何通过新场景 Practice 验证 Transfer，而不是重复原文即判定掌握。

本条目只保存超出 `M3` 最小范围的高级 Content Capture 与跨 Practice 增强，不重复批准基础 Reading / imported content，也不代表进入 V1。

## IDEA-009 — Shadow Reading and same-content multi-skill transfer

- Status: INBOX
- Priority: UNASSESSED
- Target: M5_SCOPE_REVIEW
- Type: FUTURE_PRACTICE

### Context

同一内容可以依次用于 Reading、Listening、Shadow Reading、Scenario Conversation 与 Free Expression，使识别、模仿和受控练习最终回到真实表达。该方向与项目的 Transfer principle 一致：专项 Repair / imitation success 不能直接视为长期掌握，后续新场景中的独立使用应拥有更高 Evidence 价值。

V1 `M5` 当前只批准 Listening 与 turn-based Voice 的受控最小能力，尚未批准 Shadow Reading、phoneme-level scoring 或完整跨技能 Practice sequence。

### Follow-up

在 `M5` Scope Review 时评估：

- Shadow Reading 是否解决首批 dogfooding 用户的真实 listening / pronunciation / fluency 问题；
- Reading → Listening → Shadow Reading → Conversation 的最小可验证 sequence；
- pronunciation、fluency、comprehension 与 communication Evidence 如何保持语义分离；
- imitation success 与 independent transfer success 的不同 Evidence 权重；
- phoneme / timing score 的 Provider 差异、confidence 与 failure isolation；
- 是否能够复用 Practice Runtime、Evaluator 与 Learning Memory，而不是建立独立 mastery truth。

本条目只记录候选 Practice enhancement；是否进入 V1、`M5` 或后续 Phase 仍需独立 Scope Decision。

## IDEA-010 — Realtime voice runtime and fast/slow evaluation pipeline

- Status: INBOX
- Priority: UNASSESSED
- Target: POST_V1_REVIEW
- Type: FUTURE_PRODUCT_ARCHITECTURE

### Context

V1 Voice 明确采用 turn-based STT / TTS，realtime full-duplex voice、streaming、barge-in 与 semantic endpointing 不进入当前范围。未来若真实用户反馈表明 turn-based interaction 明显妨碍自然交流，可以评估将 Voice Runtime 拆分为维持对话连续性的 Fast Path，以及异步完成 pronunciation、grammar、communication diagnosis 和 Evidence qualification 的 Slow Path。

该设计不得让 realtime Agent 绕过 Model / Tool Gateway，也不得让低延迟要求降低 Structured Output validation、language isolation 或长期状态 mutation boundary。

### Follow-up

V1 完成后根据实际 Voice latency、turn-taking failure 与用户中断行为评估：

- streaming STT / TTS、semantic endpointing、barge-in 和 full-duplex 的真实必要性；
- Fast Path 可以读取的最小 Context，以及它是否只产生 Session interaction；
- Slow Path 如何异步执行 Evaluation、retry、timeout 和 failure recovery；
- Provider-neutral audio event / transcript contract 与 adapter boundary；
- transcript correction、audio retention、privacy、cost、trace 与 deletion policy；
- Fast / Slow result 不一致时的 authority、idempotency 与 Evidence qualification rules。

本条目只安排 Post-V1 architecture evaluation，不代表批准 realtime Voice、具体 Voice Provider 或新增 production dependency。

## IDEA-011 — Contextual expression capture and Expression Garden

- Status: INBOX
- Priority: UNASSESSED
- Target: POST_M3_REVIEW
- Type: FUTURE_PRODUCT_UX

### Context

用户在 Reading、Conversation 或 imported content 中遇到值得保留的词汇和表达时，未来可以提供低摩擦的 contextual capture，并用 `Expression Garden` 展示表达从 encountered、recognized、assisted usage 到 independent usage 的变化。该体验应服务于 Natural Expression 和真实沟通，而不是把产品重心转为词汇数量、收藏数量或普通背单词 App。

Expression Garden 只能作为 Vocabulary / Expression State 的 read-only 或 evidence-backed projection，不得建立第二套 mastery truth。一次收藏、查看或单次答对应不足以直接形成 MASTERED 状态。

### Follow-up

在 `M3` Content 与 `M2` Persistent Adaptation 获得真实 Evidence 后评估：

- capture 时保存 expression、sentence、scenario、register、source 和 languageProfileId 的最小模型；
- 用户手动收藏、模型推荐与 Practice 自动观察之间的 authority 区别；
- recognition、recall、assisted usage 与 independent usage 如何产生不同 Evidence；
- Expression Garden 如何解释状态与 provenance，而不泄露私密 Conversation / imported content；
- 如何从已收藏表达生成 Review、Conversation 或 Writing Practice；
- 是否确有可用性证据支持视觉化 Garden，而不是先实现装饰性 UI。

本条目只保存 Contextual Expression UX 候选，不改变 V1 North Star，也不代表批准新的长期状态模型。

## IDEA-012 — Evaluate FSRS and BKT for advanced review and mastery estimation

- Status: INBOX
- Priority: UNASSESSED
- Target: POST_V1_REVIEW
- Type: FUTURE_LEARNING_MODEL

### Context

V1 Review 使用 simple time / mastery / failure / evidence rules，并保持 Review System、Planner 与 Learning Memory 的职责分离。FSRS 可能适合部分 vocabulary recognition / recall scheduling，BKT 或其他 learner-model algorithm 可能帮助估计特定 Skill State，但在缺少真实 longitudinal Evidence 时提前引入只会增加无法验证的复杂度。

任何后续算法都只能消费经过 qualification 的 language-specific Evidence，并输出受控的 schedule / state candidate；不得绕过 Java deterministic transition、建立第二套 mastery truth，或用单一 score 混合 severity、confidence 与 independence。

### Follow-up

V1 获得足够 dogfooding 与 longitudinal data 后执行离线评估：

- 当前 simple rules 的 prediction、review timing 与 user burden baseline；
- FSRS 是否只适用于 vocabulary / recognition，还是能安全扩展到其他 Review target；
- BKT 的 knowledge component 定义是否适合 communication、natural expression 与 cross-scenario transfer；
- cold start、sparse evidence、correct evidence、confidence 与 multi-language isolation 的处理；
- algorithm versioning、migration、explainability、offline replay 与 regression eval；
- 复杂算法相对简单规则是否产生足够的真实 Product Value。

本条目只批准未来评估，不批准引入 FSRS、BKT、Knowledge Graph 或新的 mastery authority。

## IDEA-013 — Explanation, rule-boundary, and alternative-expression practice

- Status: INBOX
- Priority: UNASSESSED
- Target: UNDECIDED
- Type: FUTURE_PRACTICE

### Context

AI 直接给出正确答案或更自然的改写，可以改善一次输出，却不一定让学习者理解原因、识别规则边界，或在新场景中独立表达。未来可以评估一种反馈后练习闭环：学习者先解释自己的选择，系统再提供 correction / short rule；随后改变时间、语境或交际条件，要求学习者重新作答，并生成、比较 2–3 种自然表达。候选交互包括 `Explain Before Reveal`、`Rule Boundary Challenge`、`Alternative Expression` 与 `Compare and Choose`。

该能力必须服务于 Communication、Natural Expression 与 Transfer，而不是把 Conversation 变成逐句 Grammar correction。这里的“要求解释”是可按能力分级的 Practice flow：初学者可以选择理由或判断对比，高阶学习者可以自由解释；用户仍应能够 Skip、Easier 或回到真实场景。Grammar 相关步骤只作为真实使用后、重复且高置信问题的短 `Grammar Repair`，不能成为默认主学习路径。

解释、规则边界判断和替代表达的表现只形成 Session-level Evidence / Candidate。单次解释正确、micro practice 成功或模型给出答案，不得直接 activate Weakness、标记 MASTERED 或修改 Level。后续 Conversation / Writing 中的独立 `Transfer Success` 应具有更高 Evidence 价值，并继续经过 language-specific Evidence qualification 与 Learning Memory aggregation。

### Follow-up

在进入正式 Scope 前决定并验证：

- 哪些 Practice、task intent 和 Evidence 条件触发该闭环，避免对每次回答机械追问；
- 初学者的 reason selection、提示强度与高阶学习者自由解释之间的 difficulty progression；
- `Explain Before Reveal → correction → boundary challenge → alternative expression → compare and choose → real-world transfer` 的最小交互，以及如何控制时长和中断感；
- Conversation 中应在何种 checkpoint 或 Session 后反馈，避免破坏自然交流与 fluency；
- LLM 负责生成 semantic feedback、counterexample 和 expression candidates 时，Java 如何执行 Schema / Enum / semantic validation、hard constraint 与 persistence authority；
- explanation、assisted repair、alternative generation、comparison 和 independent transfer 的统一 Evidence contract，以及 correct / incorrect Evidence 的平衡；
- 不同语言的 rule taxonomy、writing system、register 与 prompt 差异如何集中配置，并始终通过 `languageProfileId` 隔离；
- 用 Eval 检查 correction correctness、counterexample validity、表达自然度与多样性、难度适配、交互负担和后续 Transfer，而不是只统计完成步骤数。

本条目只保存候选 Practice enhancement，不代表进入 V1、当前 Phase，或批准新增 Agent、production dependency、长期状态类型与 Architecture Change。
