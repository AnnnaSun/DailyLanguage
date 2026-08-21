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
