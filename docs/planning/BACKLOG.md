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

### Post-V1 Review

V1 完成后，根据真实运行数据和瓶颈重新评估：

- 现有 DB-backed job 是否存在吞吐、恢复、横向扩展或任务路由限制；
- 是否主要需要 work queue、ack、retry 与 DLQ；若是，优先评估 RabbitMQ；
- 是否出现多个独立消费者、长期 event retention、replay 或按 `languageProfileId` 分区有序处理需求；若是，再评估 Kafka；
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
