# Current Handoff — Codex / Zcode 交接快照

> 本文件是可覆盖更新的当前工作快照，不是 Product / Architecture / Scope Source of Truth。
> 与 Git、source、tests 或正式决策冲突时，以后者为准。

## Snapshot

```text
Updated At: 2026-09-16
Updated By: Codex
Handoff State: CURRENT — docs-only M1-S9 reconciliation
Handoff Reason: 用户明确要求同步 S9 final implementation / verification facts，并继续在 macOS 开发
```

## Branch / HEAD / Worktree

```text
Branch: architecture/M1S9-OptionalPlannerEnrichment
HEAD: 786f124
Remote: origin/architecture/M1S9-OptionalPlannerEnrichment at 786f124
Worktree Summary: DIRTY — 仅本次 7 个 M1-S9 reconciliation docs；无 Production / Test change
```

## Current Product Gate

```text
Current Phase: M1 — Minimum Text Practice Loop
Current Feature: M1-S9 — Optional Planner Enrichment
Feature Gate: OWNERSHIP_PENDING
Stop Point: S9A–S9F implementation、Critical/delta Review、external verification、Behavior Flow 与
  docs reconciliation COMPLETE；不开始 M1-S10
```

## Approved Scope / Explicit Non-scope

- Implemented：Java 生成并持有合法 candidate truth；无 provider pair 时 deterministic planning；合法 optional
  pair 走 fixed `PLANNING` route、durable Run/Job/candidate snapshot、transaction-free dispatch、bounded wait 与
  atomic finalization，最终只返回 durable `LearningTask`。
- Invariants：Model 只能选择 Java offered exact identity 并生成 bounded `recommendationReason`；Java 严格验证
  schema、candidate membership、owner/profile identity 与 persistence transition；failure/timeout/invalid output
  回到同一 deterministic fallback，late result 不覆盖 terminal Run，最多创建一个 task。
- Explicit Non-scope：live Provider quality、frontend Credential UX、automatic retry / process-recovery、M2
  learner-state context、Learning Memory / Weakness / Level mutation、M1-S10+。

## Completed Work

1. S9A–S9F 已按 Current Slice Contract 逐项批准、实现与 Review，commit 范围 `d68d70f`…`786f124`；
2. versioned Prompt / strict output contract、candidate snapshot、PlanningRun lifecycle、dispatch/finalization、
   recommendation projection 与 owner-scoped API wiring 已接通；
3. Critical Review、所有修复的 delta-only Review、PostgreSQL fake-worker integration 与 affected regression 无
   remaining blocking finding；
4. `docs/flow/owner-scoped-learning-task-planning.md` 已记录当前真实调用链与验证证据；
5. 本次只 reconciliation Project/Phase/Feature/Architecture inventory 与 handoff，不修改代码或测试。

## Verification Evidence

- fresh 2026-09-15 local targeted：Planner Service 36/36、Controller 17/17、Awaiter 10/10 PASS；
- fresh 2026-09-15 disposable PostgreSQL 18.6 + pgvector 0.8.6：empty schema Flyway V1–V17，
  `LearningTaskPlanningIntegrationTests` fake-worker 12/12 PASS；
- fresh 2026-09-16 same PostgreSQL baseline：19-class affected regression 186/186 PASS；
- prior candidate verification：`git diff --check` PASS；disposable containers 已删除，primary database 未使用；
- current docs-only reconciliation：只执行 link / terminology consistency check 与 `git diff --check`；
- NOT_RUN：live Provider、frontend/client；本次 docs-only reconciliation 未重跑 Java / PostgreSQL tests。

## Uncommitted Changes

- `docs/planning/PROJECT_STATUS.md`
- `docs/planning/V1_PHASE_PLAN.md`
- `docs/features/PLANNER_ENRICHMENT.md`
- `docs/architecture/MODULE_MAP.md`
- `docs/architecture/DATA_FLOW.md`
- `docs/architecture/AGENT_FLOW.md`
- `docs/planning/CURRENT_HANDOFF.md`

以上均为本次 docs-only reconciliation；Production / Test worktree clean at `786f124`。

## Decisions / Blockers / Risks / UNKNOWN

- Decisions：M1-S9 implementation / review / external verification / Behavior Flow COMPLETE；Feature 整体尚未因
  implementation 自动获得 Ownership PASS。
- Blockers：无已知 code、architecture、verification 或 documentation blocker；当前唯一未完成 Gate 是
  full-feature Ownership Check。
- Accepted limitation：process crash / background completion 仍可能留下 `NOT_READY`，留待 M1-S12 重新评估；
  当前不扩展 retry/recovery Scope。
- UNKNOWN：live Provider quality 与 frontend integration 未验证。

## Next Action（单一）

执行一次精简的 M1-S9 full-feature Ownership Check，聚焦 Java / Model authority、Run / Job / task finalization
以及 fallback / late-result 边界；完成后由用户决定 docs commit。不得自动开始 M1-S10。

## 需要用户完成的 Decision

Ownership Check 通过后决定是否 commit / push 本次 docs-only reconciliation；M1-S10 仍需独立 Scope approval。
