# Current Handoff — Codex / Zcode 交接快照

> 本文件是可覆盖更新的当前工作快照，不是 Product / Architecture / Scope Source of Truth。
> 与 Git、source、tests 或正式决策冲突时，以后者为准。

## Snapshot

```text
Updated At: 2026-09-16 16:50 CST
Updated By: Codex
Handoff State: CURRENT — 为用户切换到 Windows 开发而显式刷新
Handoff Reason: S9F external verification 已完成；需要保存当前未提交工作、验证证据与下一 Gate
```

## Branch / HEAD / Worktree

```text
Branch: architecture/M1S9-OptionalPlannerEnrichment
HEAD: e189c3c
Worktree Summary: DIRTY — 4 个 S9F production files、9 个相关 test files、2 个 Behavior Flow docs，
  以及本 handoff snapshot；未发现其他无关修改
```

## Current Product Gate

```text
Current Phase: M1 — Minimum Text Practice Loop
Current Slice: M1-S9F — Owner-scoped API Wiring
Slice Gate: EXTERNAL_VERIFICATION_COMPLETE — COMMIT_DECISION_PENDING
Stop Point: Critical Review、delta-only Review、PostgreSQL fake-worker integration、affected regression
  与 Behavior Flow evidence 均完成；未 commit / push，不开始 M1-S10
```

## Approved Scope / Explicit Non-scope

- Approved：在现有 authenticated owner-scoped planning POST 上接入 optional `providerId` + transient
  Credential；无 pair 时保持 deterministic planning；合法 pair 走 fixed `PLANNING` route、dispatch、
  bounded wait 与 atomic finalization，最终只返回 durable `LearningTask`。
- Invariants：Java 保持 profile ownership、exact candidate identity、schema/semantic validation、bounded
  `recommendationReason` 与 persistence authority；Credential 不进入 DB、response、log 或 trace；每次 poll
  使用独立 `REQUIRES_NEW` 读事务观察 Worker terminal update；fallback 不伪装 Model success。
- Explicit Non-scope：live Provider、frontend、retry/recovery、M2 context、DB/schema migration、Credential
  persistence、Learning Memory / Weakness / Level mutation、M1-S10+、commit / push / merge。

## Completed Work

1. S9F 将 optional provider/Credential 接入 `LearningTaskPlanningController` 与
   `LearningTaskPlanningService`，增加 typed 400 / 422 result 与 `recommendationReason` response projection；
2. `PlannerEnrichmentJobAwaiter` 每次 poll 使用独立 `REQUIRES_NEW` 事务，避免 MyBatis first-level cache
   永久返回首次 `CREATED` snapshot；
3. Planner unit/controller/awaiter tests 与 PostgreSQL fake-worker integration 覆盖 deterministic、enriched、
   Model failure、timeout、capacity、mismatch、invalid pair、secret absence 与 exactly-one task；
4. 5 个 Evaluator integration fixtures 已切换到 café v2 exact steps，并移除与 planning
   `Propagation.NEVER` 冲突的 test-managed outer transaction，改为显式 FK cleanup；
5. Codex Critical Review、polling fix delta review、Evaluator fixture delta review均无剩余 blocking finding；
6. `docs/flow/owner-scoped-learning-task-planning.md` 已同步当前实现与最终验证证据。

## Verification Evidence

- fresh 2026-09-15 local targeted：`LearningTaskPlanningServiceTests` 36/36、
  `LearningTaskPlanningControllerTests` 17/17、`PlannerEnrichmentJobAwaiterTests` 10/10 PASS；
- fresh 2026-09-15 disposable PostgreSQL 18.6 + pgvector 0.8.6：empty schema Flyway V1–V17，
  `LearningTaskPlanningIntegrationTests` fake-worker 12/12 PASS；
- fresh 2026-09-16 disposable PostgreSQL 18.6 + pgvector 0.8.6：19-class affected regression
  186/186 PASS；
- fresh：`git diff --check` PASS；disposable containers 已删除，主数据库未使用；
- NOT_RUN：live Provider、frontend/client、Windows 环境构建与测试。

## Uncommitted Changes

- S9F production：
  - `server/src/main/java/com/dailylanguage/planner/api/LearningTaskPlanningController.java`
  - `server/src/main/java/com/dailylanguage/planner/application/LearningTaskPlanningResult.java`
  - `server/src/main/java/com/dailylanguage/planner/application/LearningTaskPlanningService.java`
  - `server/src/main/java/com/dailylanguage/planner/application/PlannerEnrichmentJobAwaiter.java`
- S9F Planner tests：Controller、Service、fake-worker integration、awaiter 共 4 个 test files；
- affected Evaluator regression：5 个 integration test files；
- documentation：`docs/flow/README.md`、`docs/flow/owner-scoped-learning-task-planning.md` 与本 snapshot；
- 以上修改尚未 commit / push；切换机器不会自动携带 working tree。

## Decisions / Blockers / Risks / UNKNOWN

- Decisions：blank optional field 视为 present-but-invalid；provider mismatch 422；route/prompt unavailable
  在创建 Run/Job 前 deterministic fallback；terminal invalid/failure/timeout 由 finalizer durable fallback；
  unknown dispatch/invariant/DB exception fail closed。
- Blockers：无已知 code、architecture 或 PostgreSQL verification blocker。
- Risks：当前工作全部未提交；若未 commit/push 或采用其他明确 transfer 方式，Windows 无法恢复这些修改。
- UNKNOWN：Windows JDK / Docker / PostgreSQL 环境是否与当前 macOS 验证一致；live Provider 行为未验证。

## Next Action（单一）

用户在当前机器完成 S9F commit decision；若决定提交，则 commit 并 push 当前 branch，随后在 Windows
checkout `architecture/M1S9-OptionalPlannerEnrichment` 并先核对 HEAD、`git status` 与本 snapshot。

## 需要用户完成的 Decision

1. 是否 commit 当前 S9F candidate；
2. 是否 push branch 以通过 Git 转移到 Windows；Codex 不自动 commit / push / merge。
