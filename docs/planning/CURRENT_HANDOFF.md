# Current Handoff

> Purpose: Codex / Zcode 共享的当前工作快照；接手时必须通过 Git、source、tests 与正式决策复核。
> Authority: 本文件不是 Product、Architecture、Scope 或 Commit Source of Truth。

## Handoff Metadata

- Updated At: `2026-09-02 16:30 CST` (`Asia/Shanghai`)
- Updated By: `Zcode`
- Handoff State: `S9J_REVIEW_PENDING`
- Handoff Reason: `User explicitly requested this refresh after S9J implementation and verification completed`
- Intended Receiver: `User running Code Ownership Review on the uncommitted S9J diff`

## Repository Snapshot

- Branch: `codex/M0S9`
- HEAD: `430d151` (`refactor(server): 移除模型网关与安全会话的基础设施配置`)
- Worktree: `DIRTY — 仅 S9J 的 8 个文件（5 modified + 3 untracked 路径），无其他修改`
- Current Product Gate: `M0-S9 / S9J REVIEW_PENDING`
- Current Slice: `M0-S9I 已实现并 commit；M0-S9J 已实现、验证完成、未 commit`
- Slice Gate: `S9J IMPLEMENTATION COMPLETE; CODE OWNERSHIP REVIEW NOT RUN`
- Stop Point: `不要修改 S9J 代码或开始 S9K，直到用户完成 S9J Review 并做出 commit 决定`

Git 证明：S9A–S9H 提交至 `de906e9`；S9I + config 包重组由用户提交为 `b336993`（含旧 handoff 刷新与 .zcode 计划文件）与 `430d151`（rename 的删除侧）。
未提交变更只属于 S9J。

## Completed M0-S9 Foundation

- S9A `14be507` – S9H `de906e9`: `model_call_job` schema、typed create、atomic claim、failure/result schema、
  `RUNNING -> SUCCEEDED`、typed `FAILED / TIMED_OUT`、owner-scoped result read、consume-once rowVersion protection。
- S9I `b336993` + `430d151`: 专用有界 `modelCallJobTaskExecutor`（固定池 + AbortPolicy + 显式优雅关闭由 S9J 补齐）；
  同时完成用户批准的 config 包重组（`modelgateway/config`、`security/config`、`modelcalljob/config`，
  含 security 三个 infra 成员最小可见性放宽）。
- S9J（本 handoff 对象，未提交）: 第一个 typed Job worker。

## Completed S9J Work（未提交）

Production（5 文件，约 240 LOC，无 schema / dependency 变化）:

1. NEW `modelcalljob/application/TextGenerationJobWorkItem.java` — 内存工作单元；
   请求内容与 transient Credential 只在提交方与 worker 之间传播，不持久化、不落日志。
2. NEW `modelcalljob/application/TextGenerationJobWorker.java` — claim → `TextGenerationPort` → 终态分类，
   返回 7 值 `WorkerOutcome`：
   `CLAIM_LOST / SUCCEEDED / FAILED / TIMED_OUT / OUTCOME_UNKNOWN / OUTCOME_UNKNOWN_UNRECORDED / TERMINAL_WRITE_LOST`。
3. CHANGE `ModelCallJobRepository` + `ModelCallJobMapper` + `ModelCallJobMapper.xml` —
   `tryRecordOutcomeUnknown`：条件 `RUNNING -> OUTCOME_UNKNOWN`（仅 RUNNING + 精确 rowVersion），
   写 `completed_at`，不写 failure 列；V4 CHECK 已支持，无 migration。
4. CHANGE `modelcalljob/config/ModelCallJobExecutionConfiguration.java` — 显式 shutdown 语义
   `waitForTasksToCompleteOnShutdown=true` + `awaitTerminationMillis=35_000`
   （≥ 最大 route timeout 30s + 落库余量；消化 S9I review 的 MEDIUM finding）。

Tests（3 文件）:

1. NEW `TextGenerationJobWorkerTests`（9 用例，mock repository + port，覆盖全部 outcome 分支）；
2. NEW `ModelCallJobOutcomeUnknownRepositoryIntegrationTests`（3 用例，需 PostgreSQL）；
3. CHANGE `ModelCallJobExecutionConfigurationTests`（新增优雅关闭行为断言；Spring 6.2.7 无 shutdown getter，
   改用"close 前 in-flight 任务必须完成"的行为验证）。

## Approved S9J Decisions（用户本 session 批准）

- `FACT`: 用户批准 S9J scope：worker 核心 + OUTCOME_UNKNOWN + shutdown 语义，不含提交方。
- `FACT`: 用户批准分类规则：以 `ModelResult` 跨过 port 的结果为已知；port 抛出的任何异常一律 OUTCOME_UNKNOWN
  （worker 无法区分"未提交"与"已提交可能已执行"；Error 记录后重抛）。
- `FACT`: 已知结果落库失败 → 尽力写 OUTCOME_UNKNOWN；该写入也失败 → Job 留 RUNNING（不可归约，reconciliation non-scope）。
- `FACT`: 用户批准 shutdown 常量 `waitFor=true + 35s`，不做成 property（M6 有证据前）。

## Verification Evidence

- Fresh for S9J: 单测 `20/20 PASS`（worker 9 + 配置 7 + properties 4）；Flyway V1–V6 在全新临时库成功应用；
  `ModelCallJobOutcomeUnknownRepositoryIntegrationTests` `3/3 PASS`；完整 ModelCallJob 模块回归 `86/86 PASS`；
  `git diff --check` `PASS`；server compile 随 test 生命周期 `PASS`。
- Prior for S9I before commit: Gateway 回归与 10 个受影响测试类 `71/71 PASS`、`clean test-compile` `PASS`。
- Fresh full server suite: `NOT_RUN`。
- Live Provider / Credential execution: `NOT_RUN`（S9J worker 无生产提交方，仅测试直调）。
- S9J Ownership Review: `NOT_RUN`（这是当前 Gate）。

临时 DB 测试资源已清理：pg18 临时容器（127.0.0.1:55433）与临时库 `daily_language_s9j_test` 已删除；
用户的 postgres / redis 容器已停回接手前 Exited 状态。

## Uncommitted Changes

- Current Slice Production code: S9J 5 文件（见上）。
- Current Slice tests: S9J 3 文件（见上）。
- Pre-existing uncommitted changes: none（S9I 与包重组已由用户 commit）。

## Decisions, Risks and UNKNOWN

- `RISK`: S9K 提交方必须显式处理 `modelCallJobTaskExecutor` 的 `RejectedExecutionException`；
  捕获后吞掉会把持久化 Job 孤儿化在 `CREATED`（S9I Ownership Explain Back 已确认用户理解此链）。
- `RISK`: `OUTCOME_UNKNOWN_UNRECORDED` 与硬杀进程都会留下 RUNNING 孤儿；reconciliation 属未批准 scope。
- `UNKNOWN`: S9K worker 投递 API、interactive wait、HTTP 状态/polling 均未设计、未批准。
- `UNKNOWN`: 最终 Hosted worker/queue 容量仍待 M6 目标硬件证据。
- `ENVIRONMENT`: 用户本机 `daily-language-postgres-1` 容器的 host 端口发布损坏
  （配置 `127.0.0.1:55432`，运行时绑定为空、连接拒绝；数据卷完好）。本 session 的 DB 测试未依赖它。
  修复选项（`docker compose up -d --force-recreate postgres` 或重启 Docker Desktop）留给用户决定。
- `UNKNOWN`: `PROJECT_STATUS.md`、`V1_PHASE_PLAN.md`、`MODULE_MAP.md`、`OWNERSHIP_MATRIX.md` 尚未同步 S9A–S9J；
  formal reconciliation 留在用户批准的 S9 / M0 closeout，不在 S9J 内顺手修改。

## User Decisions Required

- 对 S9J 未提交 diff 运行 Code Ownership Review（`/code-ownership-review`），重点：
  `TextGenerationJobWorker` 的降级链与 `tryRecordOutcomeUnknown` 条件更新语义。
- S9J commit 时机与拆分由用户决定。
- S9K Design / Scope 需要用户明确批准后才会开始。
- postgres 容器修复方式由用户决定（见 ENVIRONMENT）。

## Next Action

Run Code Ownership Review on the uncommitted S9J diff（8 个文件），通过后由用户决定 commit；
S9K（submission boundary）设计需另行批准，不得自动开始。
