# Current Handoff

> Purpose: Codex / Zcode 共享的当前工作快照；接手时必须通过 Git、source、tests 与正式决策复核。
> Authority: 本文件不是 Product、Architecture、Scope 或 Commit Source of Truth。

## Handoff Metadata

- Updated At: `2026-09-01 20:02 CST` (`Asia/Shanghai`)
- Updated By: `Codex`
- Handoff State: `REVIEW_PENDING`
- Handoff Reason: `Approved scope-local whitespace and test-evidence corrections are implemented and freshly verified`
- Intended Receiver: `User reviewing the corrected V4 constraint and focused evidence before Ownership closeout`

## Repository Snapshot

- Branch: `codex/M0S9`
- HEAD: `a887bbb` (`S8 收尾`)
- Worktree before M0-S9A: `CLEAN`
- Worktree now: `DIRTY with three M0-S9A files; no pre-existing uncommitted change`
- Current Product Gate: `M0-S9 / REVIEW_PENDING`
- Current Slice: `M0-S9A — Durable ModelCallJob State Schema`
- Slice Gate: `IMPLEMENTATION / TARGETED VERIFICATION COMPLETE; REVIEW_PENDING`
- Stop Point: `Do not implement Repository, TaskExecutor, API, typed outcome persistence or another slice`

The six S8 reconciliation files previously described as uncommitted were committed by the user in `a887bbb`. The commit changed
documentation only; `c9314dd..a887bbb` contains no Production or test change. The prior handoff branch / HEAD / dirty-state snapshot
was stale and is corrected here.

## Approved Scope

User approved implementation in the current workspace of one schema-only slice:

1. add Flyway V4 `model_call_job` durable metadata schema;
2. enforce UUIDv7 identity, owner and optional same-owner Language Profile relation;
3. store separate execution / consumption status, workflow version, row version and timestamps;
4. enforce paired selected Provider / Model identity and finite persisted vocabularies;
5. verify the schema through one focused PostgreSQL integration test class;
6. update this handoff and stop at `REVIEW_PENDING`.

## Explicit Non-scope

- Java Job domain record or transition policy;
- MyBatis Mapper / XML / Repository;
- conditional `rowVersion` update, consume-once, stale, confirmation or expiry execution;
- operation-specific Text result or safe failure persistence;
- Spring TaskExecutor configuration, transient Credential capture or interactive waiting;
- Model Gateway behavior, execution timeout, retry or fallback changes;
- HTTP status query / confirmation API;
- Planner, Conversation, Evaluator or other Application Workflow integration;
- Kafka, RabbitMQ, Push Notification or generic workflow engine;
- Weakness, Level, Mastery, Evidence or other long-term learning-state mutation;
- commit, push, merge or the next M0-S9 slice.

## Completed Work

### Production / Migration

- Added `server/src/main/resources/db/migration/V4__add_model_call_job.sql`.
- Added `language_profile(id, user_id)` composite uniqueness so the optional Job relation can enforce that
  `languageProfileId` belongs to the same `userId` at the database boundary.
- Added `model_call_job` with UUIDv7 identity, owner / workflow / route metadata, separate execution and consumption status,
  optimistic-lock `rowVersion`, expiry and completion timestamps.
- Added database checks for controlled purpose / operation / lifecycle vocabularies, paired route identity, non-negative
  versions, valid completion state and timestamp ordering.
- Replaced the `BTRIM`-only route / Workflow-step checks with explicit POSIX whitespace-boundary checks so ordinary spaces,
  tabs and other PostgreSQL whitespace cannot appear at either end.
- Did not add Credential, Prompt, request payload, raw Provider response, result payload, JSON metadata or arbitrary Map state.
- Added Chinese PostgreSQL table / column comments for Job identity, ownership, route, Workflow version, optimistic-lock
  row version, separate lifecycles and expiry-versus-execution-timeout meaning.

### Test

- Added `server/src/test/java/com/dailylanguage/modelcalljob/infrastructure/ModelCallJobSchemaIntegrationTests.java`.
- The focused test covers UUIDv7/default lifecycle state, optional Language Profile, cross-owner rejection, paired route
  identity, route / Workflow-step boundary whitespace, lifecycle vocabulary, non-negative versions, independently isolated
  completion / expiry timestamp invariants and the exact durable-column allowlist.

## Verification Evidence

Fresh for M0-S9A:

- Server Production compile: `PASS` (`mvn -q -DskipTests compile`).
- Server test compilation: `PASS` (`mvn -q -DskipTests test`).
- PostgreSQL: `18.6` using the project pgvector image on temporary host port `55432`.
- Flyway: `PASS`; four migrations validated and V4 applied successfully from schema version 3 to 4.
- `ModelCallJobSchemaIntegrationTests`: `15/15 PASS` on the final corrected files.
- `PersistenceIdentityIntegrationTests`: `10/10 PASS`; the Language Profile composite uniqueness caused no regression.
- Combined targeted database verification: `25/25 PASS` on the final corrected files.
- After the SQL comment update, a fresh empty database successfully applied Flyway V1 through V4; PostgreSQL metadata lookup
  confirmed the `workflow_version`, `row_version` and `expires_at` comments were persisted.
- The project PostgreSQL container started for verification was stopped afterward; its volume was retained.
- The temporary `daily_language_s9a_review`, `daily_language_s9a_whitespace_review` and
  `daily_language_s9a_final_review` databases were deleted after verification; they contained no user data.

Environment-only attempts before the passing run:

- Docker daemon was initially stopped.
- Default host port `5432` was occupied, so the project PostgreSQL verification used `55432` without changing repository config.
- The first sandboxed database run was blocked by local socket permission; the same command passed with approved local-network access.

Not run:

- Fresh full server suite was not run for this schema-only slice.
- Model Gateway regression was not rerun because no Model Gateway source, configuration or behavior changed.
- Repository, concurrency, TaskExecutor, interactive waiting, restart recovery and consume-once behavior remain unimplemented
  and therefore unverified.

Prior evidence, not fresh for M0-S9A:

- M0-S8 Model Gateway regression: `95/95 PASS`.
- Latest wider server evidence remains S7D: `217 total / 0 failures / 0 errors / 33 environment-skipped`.

## Uncommitted Changes

M0-S9A files:

- `server/src/main/resources/db/migration/V4__add_model_call_job.sql` — new, Production migration;
- `server/src/test/java/com/dailylanguage/modelcalljob/infrastructure/ModelCallJobSchemaIntegrationTests.java` — new test;
- `docs/planning/CURRENT_HANDOFF.md` — updated current snapshot.

Pre-existing uncommitted changes before M0-S9A: `NONE`.

## Decisions, Risks and UNKNOWN

- `FACT`: PostgreSQL now has the approved durable Job metadata contract when V4 is applied.
- `FACT`: execution status and consumption status are separate columns; this slice does not yet implement transition methods.
- `FACT`: `rowVersion` is stored and constrained but does not yet provide consume-once until a later approved Repository slice
  performs conditional updates.
- `FACT`: there is no durable request or result payload in M0-S9A; typed outcome persistence remains a later Scope Decision.
- `FACT`: Model Gateway and its model-call ExecutorService were not modified.
- `RISK`: future Java persisted vocabularies must remain synchronized with the V4 database checks through explicit migration.
- `RISK`: V4 is an additive forward migration; it has no down migration in the current Flyway convention.
- `RESOLVED`: the user approved replacing `BTRIM`-only checks; the final DDL uses POSIX boundary-whitespace checks and the
  focused tests reject tab-only, leading-space, trailing-tab and Workflow-step leading-tab cases.
- `RESOLVED`: completion-state and expiry-order failures are now exercised by independent inserts, so each constraint has
  isolated failure evidence.
- `OWNERSHIP EVIDENCE`: the user confirmed understanding of same-owner Profile correlation and the distinction between
  `workflowVersion`, optimistic-lock `rowVersion` and future consume-once behavior. The corrected whitespace constraint still
  requires final Diff Review before Ownership closeout.
- `UNKNOWN`: Repository method shape, legal transition matrix, operation-specific outcome schema and TaskExecutor capacity remain
  intentionally undecided until later Design / Scope approval.

## User Decisions Required

- Review the corrected V4 boundary-whitespace constraint and its focused tests, then complete Ownership closeout.
- Commit Decision remains with the user after Review / Ownership; no commit is authorized yet.
- Any M0-S9B Repository / transition scope requires a separate Architecture-sensitive Design / Scope approval.

## Next Action

Perform Code Ownership Review of the final M0-S9A Diff, focusing on same-owner composite FK, route / Workflow-step whitespace
constraints and the separation between Workflow version, row version, execution status and consumption status. Do not start
M0-S9B or commit automatically.
