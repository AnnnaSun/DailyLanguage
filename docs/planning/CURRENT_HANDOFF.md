# Current Handoff

> Purpose: Codex / Zcode 共享的当前工作快照；接手时必须通过 Git、source、tests 与正式决策复核。
> Authority: 本文件不是 Product、Architecture、Scope 或 Commit Source of Truth。

## Handoff Metadata

- Updated At: `2026-09-02 14:26 CST` (`Asia/Shanghai`)
- Updated By: `Codex`
- Handoff State: `DESIGN_SCOPE_PENDING`
- Handoff Reason: `User committed M0-S9H, requested entry into S9I Design / Scope, and explicitly requested this refresh`
- Intended Receiver: `User deciding the proposed M0-S9I Architecture-sensitive implementation scope`

## Repository Snapshot

- Branch: `codex/M0S9`
- HEAD before this documentation update: `de906e9` (`public resource boundary+model call 状态更新的两种情况`)
- Worktree before this documentation update: `CLEAN`
- Worktree now: `DIRTY only because CURRENT_HANDOFF.md was explicitly refreshed`
- Current Product Gate: `M0-S9 / S9I DESIGN_SCOPE_PENDING`
- Current Slice: `PROPOSED M0-S9I — Dedicated Model Call Job TaskExecutor Boundary`
- Slice Gate: `DESIGN PROPOSED; IMPLEMENTATION SCOPE NOT_APPROVED`
- Stop Point: `Do not modify Production code, runtime configuration or tests until the user approves S9I Scope`

Git proves S9H was committed as `de906e9` and the worktree was clean before this requested handoff refresh. The user requested
S9I Design / Scope; that request does not by itself approve S9I implementation.

`PROJECT_STATUS.md`、`V1_PHASE_PLAN.md` 的 current gate、`MODULE_MAP.md` 的 implementation inventory 与
`OWNERSHIP_MATRIX.md` 尚未同步 S9A–S9H。它们不得覆盖 Git、source 与 fresh verification evidence；formal
reconciliation 留到用户批准的 S9 / M0 closeout，不在 S9I Design Scope 内顺手修改。

## Completed M0-S9 Foundation

- S9A `14be507`: PostgreSQL `model_call_job` metadata schema、owner/profile isolation、execution / consumption state、
  workflowVersion、rowVersion 与 timestamp constraints。
- S9B `791f05e`: typed create、PostgreSQL defaults round-trip 与 owner-scoped Job read。
- S9C `dc5e3b4`: atomic `CREATED -> RUNNING` claim。
- S9D `9f9b1ea`: safe failure columns 与 operation-specific typed Text Generation result schema。
- S9E `7495c8f`: atomic `RUNNING -> SUCCEEDED` 与 typed Text Generation result persistence。
- S9F `bca354b`: typed `FAILED / TIMED_OUT` persistence；retryAfter durable unit 统一为 whole seconds。
- S9G `6802bb1`: owner-scoped successful Text Generation result read。
- S9H `de906e9`: versioned `NOT_READY -> CONSUMED / STALE` conditional update 与 consume-once rowVersion protection。

No Job TaskExecutor、runtime worker、interactive wait、HTTP polling、Workflow integration 或 automatic recovery has been
implemented. PostgreSQL remains the durable Job status / safe result authority; Credential remains absent from durable state。

## Proposed M0-S9I Scope

### Goal

Create the dedicated bounded Spring `TaskExecutor` boundary that later ModelCallJob workers will use. This Slice proves the Job
executor is a different bean and thread pool from the existing Gateway `modelCallExecutor`; it does not yet submit or execute a
Model call.

Directly combining executor configuration, worker lifecycle, transient Credential propagation, conditional Job transitions and
unexpected-failure termination would exceed the five-file cognitive budget and hide several independent reliability decisions.
S9I therefore establishes only the execution infrastructure; S9J will propose the first typed Job worker separately.

### Production Files

Target: four Production files, no schema and no dependency change:

1. `server/src/main/java/com/dailylanguage/modelcalljob/infrastructure/ModelCallJobExecutionProperties.java`;
2. `server/src/main/java/com/dailylanguage/modelcalljob/infrastructure/ModelCallJobExecutionConfiguration.java`;
3. `server/src/main/resources/model-call-job.yml`;
4. `server/src/main/resources/application.yml` — import the new module-local resource beside `model-gateway.yml`.

Target Production changed LOC: `<= 150`.

### Expected Behavior

- Bind typed `app.model-call-job.execution.executor.workers` and `queue-capacity` settings; both must be positive.
- Defaults are provisional `4 workers / 16 queued tasks`, independently overridable through dedicated environment variables.
- Expose a specifically named `modelCallJobTaskExecutor` backed by Spring `ThreadPoolTaskExecutor`.
- Use a fixed-size platform-thread worker pool, bounded queue, `model-call-job-` thread prefix and rejection policy that surfaces
  capacity exhaustion to the submitting caller rather than silently dropping work or running it on the request thread.
- Do not inject, wrap or reuse the Gateway `modelCallExecutor`; identical default numbers do not mean shared executor identity.
- S9I contains no task payload and therefore cannot receive, persist, log or trace Credential.

### Why Platform Threads and Abort Rejection

- A Job worker will later block while the independent Gateway executor enforces the Provider deadline; a bounded platform-thread
  pool gives explicit concurrency and queue capacity rather than unbounded virtual-thread fan-out.
- Caller-runs rejection could move a long Model lifecycle onto the HTTP/request thread and destroy the interactive wait boundary.
- Silent discard would leave a durable `CREATED` Job with no worker and no visible submission failure. Abort-style rejection lets
  the later Application boundary handle capacity explicitly before any Provider call occurs.

## Alternatives and Trade-offs

- Reuse Gateway `ExecutorService`: rejected because Job workers can occupy the same pool while waiting for Provider tasks,
  creating starvation/deadlock and violating the approved architecture boundary.
- Use Spring's generic application TaskExecutor: rejected because future unrelated async work could consume Model Job capacity
  and make isolation/configuration ambiguous.
- Use an unbounded queue or virtual-thread-per-task executor: rejected because V1 has no proven load/capacity evidence and must
  fail visibly rather than accumulate unlimited in-memory work.
- Add worker lifecycle now: deferred to S9J because unexpected exceptions require an approved `OUTCOME_UNKNOWN` path and
  submission/winner semantics; mixing those decisions into configuration would exceed this Slice.

## Architecture / Data / Concurrency / Security Impact

- Architecture: adds one module-local infrastructure bean; Model Gateway remains unchanged and does not depend on ModelCallJob.
- Data: no PostgreSQL, Redis, migration or Job state mutation.
- Concurrency: introduces bounded in-process Job capacity only; exact Hosted sizing remains provisional until M6 evidence.
- Failure: queue exhaustion must be observable as submission rejection; no task is silently discarded or executed by caller.
- Shutdown: S9I makes no claim that in-flight work survives process termination; restart / `OUTCOME_UNKNOWN` recovery remains a
  later approved runtime/reliability scope.
- Security: executor settings and task threads carry no Credential in S9I; no secret-bearing configuration is added.

## Test Scope

Planned test files:

1. `ModelCallJobExecutionPropertiesTests.java` — positive settings and invalid capacity rejection;
2. `ModelCallJobExecutionConfigurationTests.java` — default/override binding, fixed pool, bounded queue, thread prefix,
   capacity rejection and distinct bean identity from a `modelCallExecutor` test bean.

Verification plan:

- targeted configuration/property tests;
- existing `TextGenerationGatewayConfigurationTests` regression;
- ModelCallJob module regression where applicable;
- server compile;
- no database test is required because S9I has no persistence change;
- no fresh full server suite unless targeted evidence reveals shared Spring composition risk.

## Explicit Non-scope

- Job submission API、Runnable/Callable worker or `CompletableFuture` handle;
- `CREATED -> RUNNING -> terminal` runtime orchestration;
- `TextGenerationPort` invocation or operation dispatch;
- transient Credential capture / propagation;
- interactive wait、pending response or HTTP status/polling API;
- `OUTCOME_UNKNOWN` transition、restart recovery or graceful-shutdown guarantee;
- retry、fallback、Kafka、RabbitMQ or distributed execution；
- schema、Repository、Mapper、Model Gateway or learning-state mutation；
- Planner、Conversation、Evaluator integration；
- commit、S9J implementation or documentation reconciliation outside this explicitly requested handoff refresh。

## Verification Evidence

- Fresh for S9H before commit: Flyway V1–V6 `PASS`; S9H targeted `6/6 PASS`; complete ModelCallJob regression
  `63/63 PASS`; server compile and `git diff --check` `PASS`.
- Fresh for S9G before commit: owner-scoped typed result targeted `9/9 PASS`; complete ModelCallJob regression
  `57/57 PASS`; server compile `PASS`.
- Prior S9F evidence: targeted S9 regression `65/65 PASS`; V5→V6 upgrade rehearsal `PASS`.
- Fresh full server suite for S9: `NOT_RUN`.
- Live Provider / Credential execution for S9: `NOT_RUN`; no S9 runtime worker exists yet.

Temporary S9G/S9H databases were removed and the PostgreSQL container started for those tests was stopped. The unrelated default
`daily_language` test database retains an old V4 checksum and was not repaired or overwritten.

## Uncommitted Changes

- Current Slice Production code: none.
- Current Slice tests: none.
- Explicitly requested documentation update: `docs/planning/CURRENT_HANDOFF.md` only.
- Pre-existing uncommitted changes before this refresh: none.

## Decisions, Risks and UNKNOWN

- `FACT`: S9A–S9H are committed through `de906e9`; current Production has persistence/query/transition primitives but no Job
  runtime executor or worker.
- `PROPOSED`: S9I creates only a dedicated bounded Job TaskExecutor boundary; implementation remains unapproved.
- `RISK`: executor rejection semantics must remain visible to S9J; catching and ignoring rejection would orphan `CREATED` Jobs.
- `RISK`: same numerical defaults as Gateway do not authorize sharing the executor bean or queue.
- `UNKNOWN`: S9J worker handling for unchecked failure, interruption and `OUTCOME_UNKNOWN` is not approved yet.
- `UNKNOWN`: graceful shutdown and restart reconciliation remain later reliability decisions; S9I must not imply they exist.
- `UNKNOWN`: final Hosted worker/queue capacity remains subject to M6 target-hardware evidence.

## User Decisions Required

- Approve, reject or amend the proposed S9I scope, four-file Production boundary and provisional executor defaults.
- S9I implementation remains unauthorized until that decision.
- Commit Decision remains with the user after future Review and Ownership gates.

## Next Action

Wait for the user's S9I Design / Scope decision. If approved, implement only the typed configuration and dedicated bounded
`modelCallJobTaskExecutor`, run the listed targeted verification and stop at `M0-S9I / REVIEW_PENDING`.
