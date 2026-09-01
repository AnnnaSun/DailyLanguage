# Current Handoff

> Purpose: Codex / Zcode 共享的当前工作快照；接手时必须通过 Git、source、tests 与正式决策复核。
> Authority: 本文件不是 Product、Architecture、Scope 或 Commit Source of Truth。

## Handoff Metadata

- Updated At: `2026-09-01 13:51 CST` (`Asia/Shanghai`)
- Updated By: `Codex`
- Handoff State: `READY_TO_COMMIT`
- Handoff Reason: `M0-S8C Code Review, Behavior Flow sync and focused Ownership Check complete`
- Intended Receiver: `User or the next Agent selected by the user`

## Repository Snapshot

- Branch: `codex/M0S8-structure-output`
- HEAD: `16635d0` (`StructuredOutput验证`)
- Worktree Summary: `DIRTY; uncommitted M0-S8C code/tests, this handoff update, and one pre-existing user configuration change`
- Current Product Gate: `M0-S8C / READY_TO_COMMIT`
- Production Baseline: `M0-S8B committed at 16635d0`
- Slice Gate: `Code Review PASS; Behavior Flow CURRENT; Ownership UNDERSTOOD`
- Stop Point: `Wait for the user's Commit Decision; do not start S8D or commit automatically`

## Approved Scope

- Create one safe `ModelCallTrace` for each non-null `TextGenerationPort.generateText(...)` call.
- Record purpose, optional selected Provider / Model identity, Gateway latency, result status, typed failure kind,
  normalized finish reason and optional portable token usage.
- Add a narrow `ModelCallTraceRecorder` observability boundary and a default INFO-level logging implementation.
- Record terminal `ModelResult` and unexpected internal failure without changing the original result or exception contract.
- Keep observability fail-open: a recorder runtime failure must not replace the Model call result.

Explicit non-scope:

- PostgreSQL / Redis Trace persistence, OpenTelemetry or HTTP access logging.
- Request messages, generated text, Credential, Authorization header, Provider raw response or exception details in Trace / Log.
- Provider raw finish-reason diagnostics, rate-limited warning policy or Adapter version; these remain S8D.
- Propagating this Trace ID into the Executor worker / Adapter / outbound Provider request.
- Retry, fallback, ModelCallJob, Application Workflow or Learning State mutation.

## Completed Work

- Added typed `ModelCallTrace` invariants and success / model-failure / internal-failure construction.
- Added `ModelCallTraceRecorder` and `LoggingModelCallTraceRecorder` with safe INFO metadata.
- Wrapped `RoutedTextGenerationPort` result and exception paths with one terminal Trace record.
- Preserved fail-open behavior when the recorder throws a runtime exception.
- Wired the default recorder through `TextGenerationGatewayConfiguration`.
- Added runtime, safe-log, fail-open and Spring composition tests; updated direct port test construction.
- Completed read-only S8C Diff Review with no blocking code findings.
- Updated and source-checked the two affected Behavior Flow documents against the implemented terminal Trace boundary.

## Review Result

- Scope: `MATCH`; the pre-existing `model-gateway.yml` edit is excluded from S8C.
- Code Review: `PASS`; no blocking findings found.
- Architecture: `PASS`; provider-neutral Gateway boundary remains intact and no persistence/dependency/schema change exists.
- Extensibility Fit: `RIGHT_SIZED`; the recorder interface is the concrete observability/test boundary, not a Provider abstraction.
- Cross-cutting Mechanism: `EXPLICIT COMPONENT`; no AOP, interceptor or hidden context propagation was introduced.
- Security: `PASS`; Trace payload contains typed metadata only and tests reject Credential, Prompt and generated text leakage.
- Reliability: `PASS`; recorder runtime failure is isolated from the original `ModelResult` / exception contract.
- Behavior Flow: `CURRENT`; Credential propagation and connection verification now show the terminal safe Trace / INFO log,
  fail-open behavior, non-persistence boundary and Gateway-before rejection boundary.
- Ownership Check: `UNDERSTOOD`; the user traced route selection, Provider identity validation, Executor submission and
  terminal recording, identified allowed Trace metadata and prohibited sensitive data, and confirmed the fail-open ordering:
  `executeRoutedCall` creates the original result before recorder failure is caught, so the caller still receives that result.

## Verification Evidence

Fresh S8C evidence:

- Focused Trace / routing / configuration tests: `24/24 PASS`.
- Model Gateway regression: `91/91 PASS`.
- Server production compile: `PASS`.
- `git diff --check`: `PASS` after the S8C Flow sync and required handoff update.

Prior evidence:

- Latest wider server regression remains the M0-S7D evidence: `217 total / 0 failures / 0 errors / 33 environment-skipped`.

Not run / not claimed:

- Full server regression was not rerun for S8C.
- No live DeepSeek network call or real Credential verification.
- No Browser UI, Hosted TLS/channel enforcement, Trace persistence or multi-component Trace-ID propagation verification.
- Tests used explicit environment overrides to isolate the pre-existing user model-ID configuration change; they do not
  establish live availability of `deepseek-v4-flash`.

## Uncommitted Changes

M0-S8C production changes:

- `server/src/main/java/com/dailylanguage/modelgateway/infrastructure/TextGenerationGatewayConfiguration.java`
- `server/src/main/java/com/dailylanguage/modelgateway/text/execution/RoutedTextGenerationPort.java`
- `server/src/main/java/com/dailylanguage/modelgateway/trace/ModelCallTrace.java`
- `server/src/main/java/com/dailylanguage/modelgateway/trace/ModelCallTraceRecorder.java`
- `server/src/main/java/com/dailylanguage/modelgateway/trace/LoggingModelCallTraceRecorder.java`

M0-S8C test changes:

- `server/src/test/java/com/dailylanguage/modelgateway/infrastructure/TextGenerationGatewayConfigurationTests.java`
- `server/src/test/java/com/dailylanguage/modelgateway/text/execution/RoutedTextGenerationPortTests.java`
- `server/src/test/java/com/dailylanguage/modelgateway/trace/ModelCallTraceRuntimeTests.java`
- `server/src/test/java/com/dailylanguage/modelgateway/trace/LoggingModelCallTraceRecorderTests.java`

Required handoff maintenance:

- `docs/planning/CURRENT_HANDOFF.md`

M0-S8C Behavior Flow synchronization:

- `docs/flow/README.md`
- `docs/flow/text-generation-credential-propagation.md`
- `docs/flow/model-provider-connection-verification.md`

Pre-existing user change, not part of S8C and not modified by Codex:

- `server/src/main/resources/model-gateway.yml`: default route Model IDs changed from `deepseek-chat` to
  `deepseek-v4-flash`.

## Decisions, Risks and UNKNOWN

- The default INFO entry is a terminal Model-call summary, not an HTTP access log or a full distributed call-chain log.
- Calls rejected before `RoutedTextGenerationPort` do not produce this Model-call Trace.
- Unexpected internal failures currently record `INTERNAL_FAILURE` without Provider / Model identity; typed routed
  `ModelFailure` results retain their route identity.
- UNKNOWN: live DeepSeek support for the user's configured `deepseek-v4-flash` model ID.
- UNKNOWN: Hosted TLS/channel enforcement and Browser-local Credential behavior.
- Commit Decision remains with the user; no commit was created.

## Next Action

User decides whether and how to commit the S8C code, tests, Behavior Flow and handoff update. Keep the pre-existing
`server/src/main/resources/model-gateway.yml` change separate unless the user explicitly includes it. Do not start S8D.
