# Current Handoff — Codex / Zcode 交接快照

> 本文件是可覆盖更新的当前工作快照，不是 Product / Architecture / Scope Source of Truth。
> 与 Git、source、tests 或正式决策冲突时，以后者为准。

## Snapshot

```text
Updated At: 2026-09-04 13:04 CST
Updated By: Codex
Handoff State: CURRENT
Handoff Reason: 用户明确要求在 M1-S3 commit 与 Human Ownership 完成后刷新交接快照
```

## Branch / HEAD / Worktree

```text
Branch: codex/M1-S3
HEAD: 45143af（learning task 构造）
Worktree Summary: DIRTY — 仅 M1-S3 post-Ownership documentation closeout；Production / tests 无未提交修改
```

## Current Product Gate

```text
Current Phase: M1 — Minimum Text Practice Loop
Current Slice: M1-S3 — LearningTask persistence
Slice Gate: COMPLETE
Stop Point: implementation / Critical Review / external verification / Behavior Flow / Human Ownership / implementation commit complete；M1-S4 Scope 未批准
```

## Approved Scope / Explicit Non-scope

- Approved：在既有 `planner` module 内新增 durable `LearningTask`、Flyway V8 `learning_task` schema、
  `LearningTaskRepository` + MyBatis Mapper，以及 owner/profile-scoped create/read 与
  `PLANNED → STARTED → COMPLETED` conditional transition。
- Invariants：可信 `trustedUserId` 只能来自 future authenticated Application flow；`LearningTaskPlan` 不是
  authorization proof；PostgreSQL 是 UUIDv7 identity、status 与 lifecycle timestamp authority；保存 exact
  `materialId + publishedVersion`；所有 read/mutation 绑定 task + owner + language profile。
- Explicit Non-scope：S4 owner-scoped API / orchestration、PracticeSession、deterministic assessment、Evaluator、
  Model enrichment、skip/replace、expiration/cancellation/list、rowVersion / generic state machine / outbox、Content
  database FK、S4 implementation 与 commit。

## Completed Work

1. Zcode implementation candidate：新增 `LearningTask` domain snapshot、`LearningTaskRepository`、package-local
   Mapper contract、MyBatis XML、Flyway V8 migration、7 个 domain tests 与 10 个 persistence integration tests；
2. Codex Critical Diff Review：Scope MATCH、Architecture PASS、无 blocking Production finding；
3. Codex external verification：disposable PostgreSQL 18.6、Flyway V1–V8、schema inspection、targeted Integration
   与 full server regression PASS；
4. Behavior Flow：新增 `docs/flow/learning-task-persistence.md` 并同步 index；
5. Human Ownership：用户可解释 `INSERT … SELECT` 的原子 create gate 与 conditional transition 零行失败语义，
   结果 `UNDERSTOOD`；
6. M1-S3 implementation、tests 与 pre-Ownership documentation 已由用户提交为 `45143af`。

## Verification Evidence

- fresh（2026-09-04）：
  - `LearningTaskTests`：7/7 PASS；
  - `LearningTaskPersistenceIntegrationTests`：10/10 PASS；
  - empty disposable PostgreSQL 18.6 database：Flyway V1–V8 validated 8/8、applied 8/8，schema version `v8`；
  - schema inspection：16 个预期 columns、UUIDv7 default、composite ownership FK、enum / duration / text /
    lifecycle constraints 均存在；
  - full server regression：419 tests / 0 failures / 0 errors / 11 Redis 或 Redis+login environment-gated skips；
  - mapper 使用 MyBatis `#{...}` binding，无 `${...}`；
  - disposable database `daily_language_m1_s3_verify_20260904` 已删除，primary `daily_language` 未改动；
  - 项目 PostgreSQL / Redis containers 已恢复到验证前的 stopped 状态。
- first attempt：受 sandbox local socket policy 阻止，根因 `SocketException: Operation not permitted`；提升本机连接
  权限后同一 targeted command PASS，不属于 implementation failure。
- Ownership（2026-09-04）：S3 Explain Back `UNDERSTOOD`。
- not run：Redis-only integration、client build、S4 API behavior。

## Uncommitted Changes

- 仅 post-Ownership documentation closeout：
  - `docs/planning/CURRENT_HANDOFF.md`、`docs/planning/PROJECT_STATUS.md`、`docs/planning/V1_PHASE_PLAN.md`；
  - `docs/features/M1_MINIMUM_TEXT_PRACTICE.md`；
  - `docs/architecture/MODULE_MAP.md`；
  - `docs/ownership/OWNERSHIP_MATRIX.md`。
- Production Code 与 tests：无未提交修改。

## Decisions / Blockers / Risks / UNKNOWN

- Decisions：当前线性 lifecycle 使用 current-status predicate 实现原子一次性 transition，不引入 `rowVersion`；
  target language 从 immutable-by-current-API Profile row 还原，不在 task table 重复保存；Content 不建立数据库 FK。
- Blockers：无 technical / Review / verification / Ownership blocker。
- Risks：post-Ownership documentation closeout 尚未提交；M1-S4 Scope 未批准。
- UNKNOWN：无 M1-S3 completion UNKNOWN。

## Next Action（单一）

由用户决定是否提交本次 post-Ownership documentation closeout；不自动开始 M1-S4。

## 需要用户完成的 Decision

1. 决定是否提交 post-Ownership documentation closeout；
2. M1-S4 必须另行完成 Design / Scope approval，不因 S3 完成自动开始。
