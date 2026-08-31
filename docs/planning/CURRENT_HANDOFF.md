# Current Handoff

> Purpose: Codex / Zcode 共享的当前工作快照；接手时必须通过 Git、source、tests 与正式决策复核。
> Authority: 本文件不是 Product、Architecture、Scope 或 Commit Source of Truth。

## Handoff Metadata

- Updated At: `2026-08-31 17:49 CST` (`Asia/Shanghai`)
- Updated By: `Codex`
- Handoff State: `OWNERSHIP_PENDING`
- Handoff Reason: `M0-S7D read-only Diff Review passed; stopped for Human Ownership Check`
- Intended Receiver: `User or the next Review Agent selected by the user`

## Repository Snapshot

- Branch: `codex/m0s7`
- HEAD: `1c8136e` (`Reconcile M0-S7C runtime composition documentation`)
- Worktree Summary: `DIRTY; uncommitted M0-S7D changes plus a concurrent harness change in AGENTS.md`
- Current Product Gate: `M0-S7D / REVIEW_PENDING`
- Current Slice: `M0-S7D — DeepSeek-first BYOK Connection Verification`
- Slice Gate: `IMPLEMENTATION COMPLETE / VERIFICATION PASS / CODE REVIEW PASS / OWNERSHIP_PENDING`
- Stop Point: `Do not commit; complete the scoped S7D Explain Back before Browser UI or M0-S8`

## Approved Scope / Explicit Non-scope

Approved:

- Add authenticated fixed Provider preset discovery.
- Add CSRF-protected transient Credential connection verification through the existing `TextGenerationPort`.
- Add a dedicated `CONNECTION_VERIFICATION` fixed route.
- Keep Provider / Model / endpoint authority in trusted route configuration.
- Discard generated verification text and expose only safe Provider / Model identity or typed failure.

Explicitly out of scope:

- Browser local/session storage UI and live DeepSeek Credential verification.
- Hosted TLS / channel enforcement verification.
- Dynamic Provider / Model selection, custom endpoint, second simultaneously configured Provider.
- Provider Registry, Factory, Base Class or per-call route replacement.
- Retry, fallback, Structured Output, Trace, ModelCallJob, Agent Workflow or Learning State mutation.
- Commit, push, merge or rebase.

## Completed Work

- Added `GET /api/model-provider-presets` for safe configured Provider / Model metadata.
- Added `POST /api/model-provider-presets/{providerId}/verify` using the
  `X-Model-Provider-Credential` header.
- Added `ProviderConnectionVerificationService` with a fixed probe request and generated-text discard.
- Added `CONNECTION_VERIFICATION` to `ModelPurpose` and `model-gateway.yml`.
- Mapped safe operational failures to stable HTTP statuses and optional positive `Retry-After`.
- Added focused Service, MVC / Security and runtime configuration tests.
- Added the S7D Behavior Flow and reconciled directly affected Architecture / Feature / Planning documents.
- Corrected documentation to distinguish the implemented Backend API from unverified Hosted TLS enforcement.
- Completed read-only S7D Diff Review: Scope MATCH, no blocking findings, Architecture PASS, Behavior Flow CURRENT.

## Verification Evidence

Fresh:

- `mvn -q -DskipTests compile`: PASS.
- Focused S7D tests: `16/16 PASS`.
- Model Gateway regression: `77/77 PASS`.
- Server regression: `217 total / 0 failures / 0 errors / 33 environment-skipped`.
- Final Controller test rerun after a test-only assertion tightening: `7/7 PASS`.
- `git diff --check`: PASS.

Execution note:

- The first targeted run stopped before assertions because Mockito could not self-attach on the current Java 25 runtime.
  Rerunning with the project-used explicit Mockito `-javaagent` passed; no production fix was required.

Not run / not claimed:

- No live DeepSeek request or real API Key was used.
- No Browser UI, local/session storage or Hosted TLS/channel enforcement was verified.

## Uncommitted Changes

M0-S7D changes created by Codex:

- `docs/architecture/AGENT_FLOW.md`
- `docs/architecture/MODULE_MAP.md`
- `docs/features/MODEL_GATEWAY.md`
- `docs/flow/README.md`
- `docs/flow/text-generation-openai-compatible-provider.md`
- `docs/flow/model-provider-connection-verification.md`
- `docs/planning/PROJECT_STATUS.md`
- `docs/planning/V1_PHASE_PLAN.md`
- `server/src/main/java/com/dailylanguage/modelgateway/api/ModelProviderPresetController.java`
- `server/src/main/java/com/dailylanguage/modelgateway/application/ProviderConnectionVerificationService.java`
- `server/src/main/java/com/dailylanguage/modelgateway/routing/ModelPurpose.java`
- `server/src/main/resources/model-gateway.yml`
- `server/src/test/java/com/dailylanguage/modelgateway/api/ModelProviderPresetControllerTests.java`
- `server/src/test/java/com/dailylanguage/modelgateway/application/ProviderConnectionVerificationServiceTests.java`
- `server/src/test/java/com/dailylanguage/modelgateway/infrastructure/TextGenerationGatewayConfigurationTests.java`

Concurrent harness changes not created by the M0-S7D implementation:

- `AGENTS.md` — adds the Codex / Zcode `CURRENT_HANDOFF` convention.
- `docs/planning/CURRENT_HANDOFF.md` — initially created by that harness slice, then updated by Codex as required at
  the S7D stop point.

Do not silently combine the harness change with S7D when making a later Commit Decision.

## Decisions, Blockers, Risks and UNKNOWN

- FACT: path `providerId` scopes `TransientProviderCredential`; it cannot change the fixed route, endpoint, ModelId or
  Adapter.
- FACT: Credential is accepted only as a request header and is not returned in success / failure payloads.
- FACT: the fixed probe output is discarded and cannot become Learning Evidence or state.
- FACT: S7D is Backend Credential API ingress complete, not full product BYOK End-to-End complete.
- FACT: Code Review passed with no blocking findings; Extensibility Fit is RIGHT_SIZED and no Registry / Factory /
  dynamic router was added.
- Residual risk: repeated authenticated verification calls share the bounded model-call executor; per-user fairness and
  Hosted capacity remain outside S7D and require later operational evidence.
- Risk / UNKNOWN: live DeepSeek compatibility and Credential behavior remain unverified without a user-authorized real key.
- Risk / UNKNOWN: Hosted TLS/channel enforcement is a deployment boundary and has no current verification evidence.
- Blocker: none for Ownership Check.
- User Decision Required: Architecture and Scope are already approved. Commit Decision remains with the user after
  Diff Review and Ownership Check.

## Next Action

Complete the scoped M0-S7D Explain Back for the authenticated verification entry, route / Credential mismatch and safe
success / failure response. Do not start Browser UI, live Provider verification, M0-S8 or commit before Ownership closes.
