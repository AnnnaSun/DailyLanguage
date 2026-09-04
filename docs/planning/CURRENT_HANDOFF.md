# Current Handoff — Codex / Zcode 交接快照

> 本文件是可覆盖更新的当前工作快照，不是 Product / Architecture / Scope Source of Truth。
> 与 Git、source、tests 或正式决策冲突时，以后者为准。

## Snapshot

```text
Updated At: 2026-09-04 15:01 CST
Updated By: Codex
Handoff State: CURRENT — prior M1-S3 snapshot 已根据当前 Git 与 M1-S4 Diff 刷新
Handoff Reason: 用户提供 Codex 剩余额度 5% 的明确 signal，并把 M1-S4 external verification 改交 Zcode
```

## Branch / HEAD / Worktree

```text
Branch: codex/m1s4-current-slice-contract
HEAD: cfe9884（M1-S3 documentation closeout）
Worktree Summary: DIRTY — 6 个 M1-S4 untracked implementation/test files + 本 handoff snapshot；无其他已发现修改
```

## Current Product Gate

```text
Current Phase: M1 — Minimum Text Practice Loop
Current Slice: M1-S4 — Owner-scoped planning API
Slice Gate: EXTERNAL_VERIFICATION_PENDING
Stop Point: implementation complete；Codex Critical Review 与 HIGH finding delta Review PASS；
  PostgreSQL/Flyway/Integration、Behavior Flow、Human Ownership 与 commit 尚未完成
```

## Approved Scope / Explicit Non-scope

- Approved：authenticated、CSRF-protected
  `POST /api/language-profiles/{languageProfileId}/learning-tasks`；通过 explicit Application Service 串联
  owned `LanguageProfile`、existing deterministic `LearningTaskPlanner` 与
  `LearningTaskRepository.createOwned`，成功返回数据库创建后的 durable `PLANNED` task，失败返回 stable typed code。
- Invariants：`userId` 只来自 authenticated `UserContext`；request Profile 与 Planner result Profile 必须相同；
  Planner unavailable 不写数据库；Repository 继续以 `INSERT ... SELECT` 原子重校验 owner/profile/target language；
  exact `materialId + publishedVersion` 不变；unknown 与 wrong-owner Profile 对外不可区分。
- API decision：request 只含 `supportLanguage`、`requestedDifficulty`、`availableMinutes`；M1 difficulty 仅
  `FOUNDATION`；support language 规范化为 lowercase BCP 47；成功返回 201 + Location；业务失败使用
  400 / 404 / 422 / 503 contract。
- Explicit Non-scope：GET/start/complete task API、PracticeSession、response、assessment、Evaluator、Model enrichment、
  skip/replace、client UI、PlanningRun、idempotency/dedup、active-task uniqueness、schema/migration/Mapper change、
  Evidence/Memory/Weakness/Level/Mastery、M1-S5+、commit/push/merge。

## Completed Work

1. M1-S4 Current Slice Contract 已由用户批准，并把 implementation owner 指定为 Zcode；
2. Zcode 新增 3 个 Production files：`LearningTaskPlanningService`、`LearningTaskPlanningResult`、
   `LearningTaskPlanningController`；
3. Zcode 新增 3 个 test files：Service unit、HTTP contract/security、database-gated Application integration；
4. Codex 首轮 Critical Diff Review 发现 HIGH：Application 未把 Planner result Profile 绑定到 URL/owned Profile；
5. Zcode 已在持久化前增加 profile identity guard，并增加“同一 user 的另一 Profile”回归测试；
6. Codex delta-only Review：Scope MATCH、Architecture PASS、HIGH finding CLOSED、无剩余 blocking code finding；
7. 用户因 Codex 剩余额度 5%，明确把 PostgreSQL/Flyway/Integration verification 改交 Zcode。

## Verification Evidence

- fresh Zcode test evidence（2026-09-04，Surefire reports 存在）：
  - `LearningTaskPlanningServiceTests`：18/18 PASS，包含 mismatched same-user Profile fail-closed；
  - `LearningTaskPlanningControllerTests`：12/12 PASS；
  - `DeterministicLearningTaskPlannerTests`：14/14 PASS；
  - Zcode reported affected regression：78/78 PASS；
  - `LearningTaskPlanningIntegrationTests`：5 个因未设置 `RUN_DATABASE_TESTS=true` skipped。
- fresh Codex read-only evidence（2026-09-04）：
  - 实际范围仍为批准的 6 个 untracked files；
  - delta guard 位于 `createOwned` 前，mismatch 返回 `SELECTED_MATERIAL_UNAVAILABLE` 且测试验证 Repository 零交互；
  - delta-only Review PASS；两个增量文件未发现 whitespace error。
- not run：真实 PostgreSQL M1-S4 integration、empty-database Flyway V1–V8、S3+S4 affected database regression、
  wider server regression after final candidate、真实容器 sanitized 5xx 检查、client build。

## Uncommitted Changes

- M1-S4 Production（untracked）：
  - `server/src/main/java/com/dailylanguage/planner/application/LearningTaskPlanningService.java`；
  - `server/src/main/java/com/dailylanguage/planner/application/LearningTaskPlanningResult.java`；
  - `server/src/main/java/com/dailylanguage/planner/api/LearningTaskPlanningController.java`。
- M1-S4 tests（untracked）：
  - `server/src/test/java/com/dailylanguage/planner/application/LearningTaskPlanningServiceTests.java`；
  - `server/src/test/java/com/dailylanguage/planner/application/LearningTaskPlanningIntegrationTests.java`；
  - `server/src/test/java/com/dailylanguage/planner/api/LearningTaskPlanningControllerTests.java`。
- Handoff：`docs/planning/CURRENT_HANDOFF.md`（本次额度型交接刷新）。
- 未发现接手前的其他未提交修改；不得覆盖或丢弃以上文件。

## Decisions / Blockers / Risks / UNKNOWN

- Decisions：`InvalidRequest` 使用 typed Application result；Profile mismatch 映射
  `SELECTED_MATERIAL_UNAVAILABLE` / HTTP 503；POST 当前非幂等，每次成功请求可创建新的 `PLANNED` task；
  当前不新增 migration、共享 language abstraction、ControllerAdvice 或 generic workflow abstraction。
- Blockers：没有已知 code-review blocker；external verification、Behavior Flow、Ownership 与 commit Gate 尚未完成。
- Risks：真实容器 sanitized 5xx 尚未验证；非幂等 POST 的不确定响应不得自动 retry；当前 6 个实现/test files
  仍是 untracked，操作 Git 时必须显式保护。
- UNKNOWN：M1-S4 在 PostgreSQL 18 + Flyway V1–V8 下的实际 integration 结果、最终 wider regression 结果。

## Next Action（单一）

Zcode 按用户本轮明确分工执行 M1-S4 external verification：使用 disposable PostgreSQL 18 从 empty schema
应用/验证 Flyway V1–V8，运行 `LearningTaskPlanningIntegrationTests` 与受影响的
`LearningTaskPersistenceIntegrationTests`，检查 exact material version、owner/profile/language isolation、PLANNED row，
再执行 final candidate 所需的 wider server regression；区分 PASS、failure 与 environment blocker，不自动修改
Production、开始 M1-S5 或 commit。

## 需要用户完成的 Decision

1. external verification 完成后决定是否进入 Behavior Flow / Human Ownership Gate；
2. Ownership 完成后决定是否 commit；不得自动 commit、push、merge 或开始 M1-S5。
