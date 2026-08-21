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
