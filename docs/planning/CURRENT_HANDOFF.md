# Current Handoff — Codex / Zcode 交接快照

> 本文件是可覆盖更新的当前工作快照，不是 Product / Architecture / Scope Source of Truth。
> 与 Git、source、tests 或正式决策冲突时，以后者为准。

## Snapshot

```text
Updated At: 2026-09-03 23:30 CST
Updated By: Codex
Handoff State: CURRENT
Handoff Reason: 用户明确要求同步 M1-S2 正式状态与 Ownership 文档
```

## Branch / HEAD / Worktree

```text
Branch: codex/M1-S3
HEAD: 91b6c91（Document Codex and Zcode collaboration boundaries）
Worktree Summary: DIRTY — 仅本次 M1-S2 documentation reconciliation；
  Production / tests / resources clean，开始同步时 worktree clean
```

## Current Product Gate

```text
Current Phase: M1 — Minimum Text Practice Loop
Current Slice: M1-S2 — Deterministic Planner core
Slice Gate: COMPLETE（implementation / Review / verification / Ownership complete）
Stop Point: M1-S3 SCOPE_NOT_APPROVED；已提出 Current Slice Contract，等待用户批准
```

## Approved Scope / Explicit Non-scope

- Approved：M1-S2 module-local deterministic Planner——从 exact language-pair Catalog candidate set 执行
  target/support/difficulty/duration/exclusion hard filtering、stable identity fallback、final material re-resolution
  validation，返回尚未持久化的 `LearningTaskPlan` 或 typed `Unavailable`。
- Review amendment：immutable published material；Built-in manifest 以 `PLANNABLE / HISTORICAL_ONLY` 区分新规划
  candidate 与历史 identity resolution，同一 materialId 最多一个 PLANNABLE version。
- Explicit Non-scope：PostgreSQL / LearningTask lifecycle（S3）、owner-scoped API（S4）、PracticeSession（S5）、
  deterministic completion（S6）、Evaluator（S7–S8）、optional Planner Model enrichment（S9）、Japanese pack（S10）、
  Vue UX（S11）与 M1 closeout（S12）。

## Completed Work

1. `fcefedb`：`LearningTaskPlanner`、`DeterministicLearningTaskPlanner`、typed request/result/plan 与 14 个
   Planner tests；
2. `4322499`：Review fixes，补齐 Content collection immutability、完整 published-version retention、
   `PLANNABLE / HISTORICAL_ONLY` availability、manifest v2 与相关 regression tests；
3. S2 Critical Review：无 blocking finding；Behavior Flow `NOT_REQUIRED`（module-local、无 durable state/API/Model）；
4. Ownership：用户确认已完成，结果 `UNDERSTOOD`；Planner 记录为 L3；
5. 本次只同步正式状态、Feature dossier、Architecture implementation mappings、Ownership Matrix 与本 handoff，
   不修改实现。

## Verification Evidence

- fresh（2026-09-03，本次状态核对）：
  - Planner + affected Content tests：64/64 PASS（Planner 14 + Content 50）；
  - non-database server regression：389 tests / 0 failures / 0 errors / 85 environment-gated skips；
  - Java 25 首次 wider run 因 Mockito self-attach 环境失败；使用本地 Byte Buddy agent 重跑后 PASS；
  - `git diff --check 6b0212f..91b6c91`：PASS；开始同步时 `git status --short` clean。
- prior / user-confirmed：S2 technical implementation、Critical Review、verification 与 Ownership complete。
- not run：PostgreSQL/Flyway integration、Redis tests、client build、S3 behavior（S2 无 DB/API/UI scope）。

## Uncommitted Changes

- 本次 S2 documentation reconciliation：
  - `docs/planning/PROJECT_STATUS.md`；
  - `docs/planning/V1_PHASE_PLAN.md`；
  - `docs/planning/CURRENT_HANDOFF.md`；
  - `docs/features/M1_MINIMUM_TEXT_PRACTICE.md`；
  - `docs/architecture/MODULE_MAP.md`；
  - `docs/architecture/AGENT_FLOW.md`；
  - `docs/architecture/DATA_FLOW.md`；
  - `docs/ownership/OWNERSHIP_MATRIX.md`。
- Production / tests / resources：none。
- 接手前已有修改：无；开始本次同步时 worktree clean。

## Decisions / Blockers / Risks / UNKNOWN

- Decisions：S2 final implementation identity 是 `fcefedb` + review fixes `4322499`；`91b6c91` 只修改
  collaboration harness，不属于 S2 behavior；Planner 保持无 persistence / Model / downstream mutation authority。
- Blockers：无 S2 blocker；M1-S3 仍需用户明确 Scope approval。
- Risks：正式文档本次尚未 commit；不得把 docs sync 当作 M1-S3 implementation approval。
- UNKNOWN：历史 Explain Back 的问题数量未保留，因此 Ownership Matrix 明确记录为 `UNKNOWN`，不补造数字。

## Next Action（单一）

用户 Review 本次 M1-S2 documentation reconciliation diff；确认无误后再决定是否批准已提出的
`M1-S3 — LearningTask persistence` Current Slice Contract。

## 需要用户完成的 Decision

1. 本次 S2 documentation reconciliation 的 Review / Commit Decision；
2. M1-S3 Current Slice Contract 的 Scope / Architecture Approval。
