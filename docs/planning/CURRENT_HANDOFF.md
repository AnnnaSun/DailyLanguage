# Current Handoff

> Purpose: Codex / Zcode 共享的当前工作快照；接手时必须通过 Git、source、tests 与正式决策复核。
> Authority: 本文件不是 Product、Architecture、Scope 或 Commit Source of Truth。

## Handoff Metadata

- Updated At: `2026-09-01 20:09 CST` (`Asia/Shanghai`)
- Updated By: `Codex`
- Handoff State: `DESIGN_SCOPE_PENDING`
- Handoff Reason: `User committed M0-S9A and explicitly requested entry into M0-S9B`
- Intended Receiver: `User deciding the proposed M0-S9B Architecture-sensitive Design / Scope`

## Repository Snapshot

- Branch: `codex/M0S9`
- HEAD: `14be507` (`新建 job table`)
- Worktree before this handoff update: `CLEAN`
- Worktree now: `DIRTY only because this handoff snapshot was refreshed`
- Current Product Gate: `M0-S9 / S9B DESIGN_SCOPE_PENDING`
- Current Slice: `PROPOSED M0-S9B — Durable Job Creation and Owner-scoped Read`
- Slice Gate: `DESIGN PROPOSED; IMPLEMENTATION SCOPE NOT_APPROVED`
- Stop Point: `Do not modify Production code, schema, mapper or tests until the user approves S9B Scope`

The previous snapshot still reported `a887bbb`, a dirty S9A worktree and `REVIEW_PENDING`. Git now proves that the user committed
the complete S9A Diff as `14be507` and the worktree was clean before this handoff refresh. The user then explicitly requested
entry into S9B, which authorizes S9B Design / Scope but does not silently authorize implementation.

`PROJECT_STATUS.md` and the M0-S9 gate section of `V1_PHASE_PLAN.md` still describe the pre-S9A state. They are stale for the
S9A commit fact and must not be used to deny the verified Git state; formal reconciliation remains pending.

## Prior Slice: M0-S9A

- State: `COMMITTED / ACCEPTED FOR PROGRESSION TO S9B DESIGN`
- Commit: `14be507`
- Added PostgreSQL V4 `model_call_job` metadata schema and same-owner Language Profile composite FK.
- Added separate execution / consumption status, Workflow version, optimistic-lock row version and timestamp invariants.
- Added SQL table / column comments and boundary-whitespace checks that cover ordinary spaces and tab-like whitespace.
- Persisted no Credential, request, Prompt, raw Provider response, typed result payload or arbitrary metadata Map.
- Fresh final verification before commit: Flyway V1 through V4 `PASS`; ModelCallJob schema `15/15 PASS`;
  persistence identity regression `10/10 PASS`; combined `25/25 PASS`.
- Fresh full server suite was not run for S9A.

## Proposed M0-S9B Scope

### Goal

Add the smallest executable Java persistence boundary that can create the approved initial Job state and read it only through
owner-scoped identity. S9B does not implement lifecycle transition or runtime execution.

### Production Files

Target maximum: five main Production files and no migration:

1. `server/src/main/java/com/dailylanguage/modelcalljob/domain/ModelCallJob.java`;
2. `server/src/main/java/com/dailylanguage/modelcalljob/domain/NewModelCallJob.java`;
3. `server/src/main/java/com/dailylanguage/modelcalljob/infrastructure/ModelCallJobRepository.java`;
4. `server/src/main/java/com/dailylanguage/modelcalljob/infrastructure/ModelCallJobMapper.java`;
5. `server/src/main/resources/mapper/ModelCallJobMapper.xml`.

Target Production changed LOC: `<= 250`. If explicit safe mapping cannot stay inside that budget, stop and split the Domain
contract from Repository implementation instead of compressing responsibilities or hiding types for file-count reasons.

### Expected Behavior

- `NewModelCallJob` accepts owner, optional Language Profile, purpose / operation, optional paired route identity, Workflow
  reference/version and expiry; it carries no Credential or arbitrary payload.
- Repository creation lets PostgreSQL assign UUIDv7, `CREATED`, `NOT_READY`, `rowVersion=0` and `createdAt`, then returns a
  typed `ModelCallJob` snapshot.
- Provider / Model remain both present or both absent; the API does not represent a partial route.
- Public lookup requires both `jobId` and owner `userId`; another user receives no Job snapshot.
- The Java state exposes execution and consumption as separate typed vocabularies and treats `rowVersion` as read-only in S9B.

### Explicit Non-scope

- lifecycle transition matrix or conditional update;
- incrementing `rowVersion`, consume-once, confirmation, discard, stale or expiry execution;
- safe typed result / failure tables or payload persistence;
- route selection or route mutation;
- Spring TaskExecutor, interactive wait, transient Credential capture or restart recovery;
- Model Gateway changes or reuse of its `ExecutorService`;
- HTTP status / polling / confirmation API;
- Planner, Conversation, Evaluator or other Workflow integration;
- schema V5, retry, fallback, Kafka, RabbitMQ or learning-state mutation;
- commit or any later S9 slice.

## Proposed Verification

- Focused unit tests for Java route-pair, version, Workflow-step and timestamp invariants without duplicating every database
  constraint case.
- Fresh PostgreSQL integration tests for create / round-trip mapping, UUIDv7 and database defaults, optional Profile / route,
  and owner-scoped read denial.
- Extend mapper SQL safety evidence for prepared parameter binding; no `${}` or annotation SQL.
- Rerun `ModelCallJobSchemaIntegrationTests` and `PersistenceIdentityIntegrationTests` as targeted regression.
- Server compile; no full server suite unless implementation changes a shared boundary or targeted evidence exposes a wider risk.

## Architecture / Data / Concurrency / Security Notes

- Architecture: new `modelcalljob` module depends only on portable Model Gateway routing vocabulary; Model Gateway does not
  depend on Job and is not modified.
- Data: PostgreSQL remains authority; S9B adds no schema and callers cannot choose initial lifecycle status or row version.
- Concurrency: `rowVersion` is returned but never updated; S9B makes no consume-once claim.
- Security: every public read is owner-scoped; Credential, Prompt, request and raw result are absent from domain and mapper.
- Extensibility Fit: direct typed records + Repository / Mapper are sufficient for the current persisted variation; no generic
  Job payload, Factory, Registry, base class or workflow engine.

## Decisions, Risks and UNKNOWN

- `FACT`: S9A is committed at `14be507`; current worktree was clean before this handoff refresh.
- `FACT`: the current source has schema and tests only; no `modelcalljob` Java Production package exists yet.
- `PROPOSED`: S9B combines typed initial state with create / owner-scoped read because schema is already stable and no
  concurrency transition is included.
- `RISK`: Java enums and V4 database vocabulary must remain aligned; focused round-trip tests must expose drift.
- `RISK`: MyBatis mapping of nullable route / Profile values must not bypass the paired-route invariant.
- `UNKNOWN`: exact legal lifecycle transitions, outcome schema, TaskExecutor capacity and recovery policy remain later Scope
  Decisions.

## User Decisions Required

- Approve, reject or amend the proposed S9B Scope and five-file boundary.
- S9B implementation remains unauthorized until that decision.
- Commit Decision remains with the user after a later implementation Review / Ownership gate.

## Next Action

Wait for the user's S9B Design / Scope decision. If approved, implement only typed initial Job state plus PostgreSQL create and
owner-scoped read, run the listed targeted verification, update this handoff and stop at `REVIEW_PENDING`.
