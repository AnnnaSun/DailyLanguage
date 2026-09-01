# Current Handoff

> Purpose: Codex / Zcode 共享的当前工作快照；接手时必须通过 Git、source、tests 与正式决策复核。
> Authority: 本文件不是 Product、Architecture、Scope 或 Commit Source of Truth。

## Handoff Metadata

- Updated At: `2026-09-01 16:28 CST` (`Asia/Shanghai`)
- Updated By: `Codex`
- Handoff State: `READY_TO_COMMIT`
- Handoff Reason: `S8D Code Review, verification, Behavior Flow sync and focused Ownership Check are complete`
- Intended Receiver: `User making the S8D Commit Decision`

## Repository Snapshot

- Branch: `codex/M0S8-structure-output`
- HEAD: `3f8838d` (`将调用信息记录至 log 日志`)
- Worktree Summary: `DIRTY with the uncommitted M0-S8D implementation, tests, Behavior Flow sync and this handoff`
- Current Product Gate: `M0-S8D / READY_TO_COMMIT`
- Production Baseline: `M0-S8C committed at 3f8838d`
- Stop Point: `Wait for the user's Commit Decision; do not commit automatically or start another slice`

## Approved Scope

Goal:

- Preserve the provider-neutral `UNKNOWN` finish reason while making missing or unfamiliar Provider raw finish reasons safely
  diagnosable and correlatable with the terminal S8C Trace.

Implemented flow:

```text
RoutedTextGenerationPort creates UUID traceId
→ Executor task explicitly passes traceId to TextGenerationProviderAdapter
→ OpenAiCompatibleTextGenerationAdapter passes traceId to payload mapper
→ mapper normalizes known finish reason without warning
→ missing / unknown finish reason remains portable UNKNOWN
→ OpenAiCompatibleFinishReasonDiagnostics emits at most one safe WARN per Provider / Model per minute
→ terminal ModelCallTrace uses the same traceId
```

Diagnostic policy:

- Known `stop`, `length` and `content_filter`: no raw diagnostic log.
- Missing raw value: `classification=MISSING`; no raw value.
- Unknown value matching `[A-Za-z0-9._-]{1,64}`: `classification=SAFE_TOKEN` and the safe token.
- Invalid, control-character or overlength value: `classification=INVALID`, UTF-16 raw length and SHA-256 digest only.
- WARN includes safe route identity, Trace ID and code-owned Adapter version `openai-compatible-text-v1`.
- Fixed process-local limit: one warning per selected Provider / Model per minute, concurrency-safe.
- Diagnostic computation and logging are fail-open and cannot replace the normalized `UNKNOWN` response.

Explicit non-scope:

- Trace persistence, metrics, OpenTelemetry, MDC, ThreadLocal, AOP or a general execution-context wrapper;
- raw-response Debug switch, configurable or distributed rate limiter;
- retry / fallback, Structured Output Workflow integration, `ModelCallJob` or Learning State mutation;
- public HTTP API, database schema, dependencies or model-call executor policy changes.

## Completed Work

Production files:

- `RoutedTextGenerationPort.java`: passes its existing UUID through the routed call and Executor lambda.
- `TextGenerationProviderAdapter.java`: internal SPI now receives the UUID explicitly.
- `OpenAiCompatibleTextGenerationAdapter.java`: validates and forwards the UUID to response mapping.
- `OpenAiCompatibleTextPayloadMapper.java`: reports only missing or unknown raw finish reasons and still returns `UNKNOWN`.
- `OpenAiCompatibleFinishReasonDiagnostics.java`: applies classification, redaction, SHA-256 correlation, per-route concurrent
  rate limiting and fail-open logging.

Tests:

- Updated direct Adapter lambdas and calls for the internal SPI signature.
- Added worker-to-terminal Trace UUID equality evidence in `ModelCallTraceRuntimeTests`.
- Added four focused diagnostics tests covering known-value silence, missing/safe/invalid classification, raw non-disclosure,
  fail-open, per-route time-window behavior and concurrent suppression.

Documentation:

- Updated `docs/flow/README.md` index.
- Updated `text-generation-credential-propagation.md` with explicit cross-thread Trace-ID propagation.
- Updated `text-generation-openai-compatible-provider.md` with the implemented diagnostics boundary and evidence.

## Review Result

- Scope: `MATCH`; S8D changed the five approved Production files plus direct tests and Flow documentation.
- Production Code Review: `PASS`; no blocking correctness, security, concurrency or Architecture finding in S8D logic.
- Architecture: `PASS`; explicit UUID propagation and one concrete protocol diagnostics component match the approved design.
- Extensibility Fit: `RIGHT_SIZED`; no general diagnostics framework or hidden async context was introduced.
- Cross-cutting Mechanism: `EXPLICIT COMPONENT`; no AOP, ThreadLocal or MDC.
- Review Finding: `RESOLVED`; two default-route assertions and the existing Flow runtime-composition line now use the
  committed `deepseek-v4-flash` default.
- Code Review: `PASS`; no blocking findings remain.
- Behavior Flow: `CURRENT` after the authorized correction.
- Verification: `PASS`; current defaults are covered without model-id environment overrides.
- Ownership Check: `UNDERSTOOD`; the user traced UUID creation through routed execution into the Adapter and terminal Trace,
  distinguished known / safe unknown / invalid finish-reason handling, and explained the per-route one-minute limiter.
  The final clarification established that a suppressed same-route event returns before classification/logging, while an
  event after the window is classified and logged normally.

## Verification Evidence

Fresh S8D evidence:

- Targeted S8D tests: `44/44 PASS`, `0 failures`, `0 errors`, `0 skipped`.
- Model Gateway regression: `95/95 PASS`, `0 failures`, `0 errors`, `0 skipped`, without model-id environment overrides.
- Server compile: `mvn -q -DskipTests compile` PASS after the final Production changes.
- `git diff --check`: PASS after the final handoff update.

Fresh review evidence:

- Initial `TextGenerationGatewayConfigurationTests` run exposed the stale `deepseek-chat` expectation: `6 run / 1 failure`.
- After the authorized correction, `TextGenerationGatewayConfigurationTests`: `6/6 PASS` without model-id overrides.
- After the correction, Model Gateway regression: `95/95 PASS` without model-id overrides.

Not run:

- Full server test suite was not rerun because this slice is isolated to Model Gateway behavior and the approved
  verification plan selected targeted plus Model Gateway regression.

Prior wider evidence, not fresh for S8D:

- S7D wider server regression: `217 total / 0 failures / 0 errors / 33 environment-skipped`.

## Uncommitted Changes

Current S8D Production changes:

- `server/src/main/java/com/dailylanguage/modelgateway/text/execution/RoutedTextGenerationPort.java`
- `server/src/main/java/com/dailylanguage/modelgateway/text/execution/TextGenerationProviderAdapter.java`
- `server/src/main/java/com/dailylanguage/modelgateway/text/openaicompatible/OpenAiCompatibleTextGenerationAdapter.java`
- `server/src/main/java/com/dailylanguage/modelgateway/text/openaicompatible/OpenAiCompatibleTextPayloadMapper.java`
- `server/src/main/java/com/dailylanguage/modelgateway/text/openaicompatible/OpenAiCompatibleFinishReasonDiagnostics.java` (new)

Current S8D test changes:

- `server/src/test/java/com/dailylanguage/modelgateway/application/ProviderConnectionVerificationServiceTests.java`
- `server/src/test/java/com/dailylanguage/modelgateway/text/execution/FixedTextGenerationRoutesTests.java`
- `server/src/test/java/com/dailylanguage/modelgateway/text/execution/RoutedTextGenerationPortTests.java`
- `server/src/test/java/com/dailylanguage/modelgateway/text/execution/TextGenerationRouteTests.java`
- `server/src/test/java/com/dailylanguage/modelgateway/text/openaicompatible/OpenAiCompatibleTextGenerationAdapterTests.java`
- `server/src/test/java/com/dailylanguage/modelgateway/text/openaicompatible/OpenAiCompatibleTextPayloadMapperTests.java`
- `server/src/test/java/com/dailylanguage/modelgateway/text/openaicompatible/OpenAiCompatibleFinishReasonDiagnosticsTests.java` (new)
- `server/src/test/java/com/dailylanguage/modelgateway/trace/ModelCallTraceRuntimeTests.java`

Current S8D documentation / handoff changes:

- `docs/flow/README.md`
- `docs/flow/text-generation-credential-propagation.md`
- `docs/flow/text-generation-openai-compatible-provider.md`
- `docs/planning/CURRENT_HANDOFF.md`

Before S8D implementation, only the approved Design / Scope update in `CURRENT_HANDOFF.md` was uncommitted. No other
pre-existing user-owned work was present.

## Decisions, Risks and UNKNOWN

- The internal Adapter SPI signature change and explicit UUID propagation match the approved Architecture / Scope.
- No raw finish reason enters the portable response, terminal Trace, metrics or persistence.
- SHA-256 digest supports correlation but does not make low-entropy raw values secret; therefore only invalid values use
  the digest while allowlisted protocol-like tokens may be logged directly.
- The rate limiter is process-local and resets on restart; this is intentional for the approved S8D boundary.
- The fixed one-minute window is not based on production measurement.
- UNKNOWN: live DeepSeek finish-reason variants and actual warning frequency.
- The committed `deepseek-v4-flash` defaults, configuration assertions and Behavior Flow now agree.
- Commit Decision remains with the user; no automatic commit is authorized.

## Human Review Focus

1. `RoutedTextGenerationPort`: one UUID is created before routing and explicitly captured by the Executor task, while the
   same value forms the terminal Trace.
2. `OpenAiCompatibleTextPayloadMapper`: known values stay quiet; missing and unknown values alone enter diagnostics and
   still normalize to `UNKNOWN`.
3. `OpenAiCompatibleFinishReasonDiagnostics`: safe-token allowlist, invalid raw non-disclosure, per-route one-minute
   concurrency behavior and fail-open boundary.

## Next Action

Wait for the user's S8D Commit Decision. Do not commit automatically or begin another slice / M0-S9.
