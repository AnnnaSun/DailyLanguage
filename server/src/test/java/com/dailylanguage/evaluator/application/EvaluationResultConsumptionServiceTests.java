package com.dailylanguage.evaluator.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.content.domain.MaterialSourceLineage;
import com.dailylanguage.content.domain.PublishedLearningMaterial;
import com.dailylanguage.content.domain.SupportScaffold;
import com.dailylanguage.content.domain.TargetPracticeCore;
import com.dailylanguage.content.domain.TextPracticeStep;
import com.dailylanguage.content.domain.TextStepKind;
import com.dailylanguage.evaluator.application.EvaluationResultConsumptionService.ConsumptionResult;
import com.dailylanguage.evaluator.application.EvaluationResultConsumptionService.DurableOutcome;
import com.dailylanguage.evaluator.application.GroundedEvaluationInputResult.Ready;
import com.dailylanguage.evaluator.domain.EvaluationRun;
import com.dailylanguage.evaluator.domain.GroundedEvaluationInput;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.GroundedClaim;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.Rejected;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.RejectionReason;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.Validated;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.ValidatedSemanticCandidate;
import com.dailylanguage.evaluator.infrastructure.EvaluationRunRepository;
import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.infrastructure.ModelCallJobRepository;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.structuredoutput.StructuredOutputValidator;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;
import com.dailylanguage.modelgateway.text.TextGenerationResponse.FinishReason;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.practice.domain.DeterministicAssessment;
import com.dailylanguage.practice.domain.DeterministicAssessment.StepKind;
import com.dailylanguage.practice.domain.DeterministicAssessment.StepOutcome;
import com.dailylanguage.practice.domain.DeterministicAssessment.StepResult;
import com.dailylanguage.practice.domain.DeterministicTextAssessmentPolicy;
import com.dailylanguage.practice.domain.PracticeSession;
import com.dailylanguage.practice.domain.PracticeSession.LearnerResponse;
import com.dailylanguage.modelgateway.result.ModelFailure;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.security.domain.UserContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EvaluationResultConsumptionServiceTests {

    private static final String VALID_CLAIM_JSON = """
            {"claims":[{"sourceTurnId":"order-drink","exactQuote":"medium coffee","occurrenceIndex":-1,
            "issueType":"NATURALNESS","explanation":"The quoted request reads as abrupt here.",
            "confidence":0.7}]}
            """;
    private static final String ZERO_CLAIM_JSON = "{\"claims\":[]}";
    private static final String REJECTED_CLAIM_JSON = """
            {"claims":[{"sourceTurnId":"order-drink","exactQuote":"not in the durable text",
            "occurrenceIndex":-1,"issueType":"GRAMMAR","explanation":"fabricated","confidence":0.9}]}
            """;

    private static final UUID USER_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000081");
    private static final UUID OTHER_USER_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000082");
    private static final UUID PROFILE_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000083");
    private static final UUID OTHER_PROFILE_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000084");
    private static final UUID TASK_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000085");
    private static final UUID SESSION_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000086");
    private static final UUID RUN_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000087");
    private static final UUID JOB_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000088");
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-06T10:10:30Z");
    private static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-09-07T10:15:30Z");
    private static final OffsetDateTime COMPLETED_AT = OffsetDateTime.parse("2026-09-07T10:22:00Z");
    private static final OffsetDateTime EXPIRES_AT = OffsetDateTime.now().plusDays(7);

    private final EvaluationRunRepository evaluationRunRepository =
            Mockito.mock(EvaluationRunRepository.class);
    private final ModelCallJobRepository modelCallJobRepository =
            Mockito.mock(ModelCallJobRepository.class);
    private final SemanticGroundingValidator validator =
            Mockito.mock(SemanticGroundingValidator.class);

    // 真实 validator（真实 classpath rubric）驱动 grounding 行为；mock validator 用于交互断言。
    private final EvaluationResultConsumptionService realValidatorService =
            new EvaluationResultConsumptionService(
                    evaluationRunRepository, modelCallJobRepository,
                    new SemanticGroundingValidator(
                            new StructuredOutputValidator(JsonMapper.builder().build()),
                            new ClasspathRubricSource()));
    private final EvaluationResultConsumptionService mockValidatorService =
            new EvaluationResultConsumptionService(
                    evaluationRunRepository, modelCallJobRepository, validator);

    // --- Consumed：validated candidate ---

    @Test
    void consumesValidatedCandidateInContractedOrder() {
        stubPendingRunWithSucceededJob(VALID_CLAIM_JSON);
        EvaluationRun finalized = succeededRun();
        when(evaluationRunRepository.tryFinalizeOwned(
                RUN_ID, USER_ID, PROFILE_ID, EvaluationRun.Status.SUCCEEDED, Optional.empty(), 0L))
                .thenReturn(finalized);

        ConsumptionResult result = realValidatorService.consumeForReadyInput(
                ready(), new UserContext(USER_ID));

        assertThat(result).isInstanceOfSatisfying(ConsumptionResult.Consumed.class, consumed -> {
            DurableOutcome outcome = consumed.outcome();
            assertThat(outcome.run()).isEqualTo(finalized);
            ValidatedSemanticCandidate candidate = ((Validated) outcome.groundingResult()).candidate();
            assertThat(candidate.sessionId()).isEqualTo(SESSION_ID);
            assertThat(candidate.materialIdentity())
                    .isEqualTo(new MaterialIdentity("en-builtin-cafe-request", "v1"));
            assertThat(candidate.claims()).hasSize(1);
            GroundedClaim claim = candidate.claims().getFirst();
            assertThat(claim.sourceTurnId()).isEqualTo("order-drink");
            assertThat(claim.exactQuote()).isEqualTo("medium coffee");
            assertThat(claim.startOffset()).isEqualTo("Could I have a medium coffee, please?".indexOf("medium coffee"));
            assertThat(claim.confidence()).isEqualTo(0.7);
        });
        InOrder order = inOrder(modelCallJobRepository, evaluationRunRepository);
        order.verify(modelCallJobRepository).findByIdAndUserId(JOB_ID, USER_ID);
        order.verify(modelCallJobRepository).findTextGenerationResultByJobIdAndUserId(JOB_ID, USER_ID);
        order.verify(modelCallJobRepository)
                .tryConsumeSucceededResult(JOB_ID, USER_ID, 0L, 0L);
        order.verify(evaluationRunRepository)
                .insertValidatedCandidate(eq(RUN_ID), eq(USER_ID), eq(PROFILE_ID), any());
        order.verify(evaluationRunRepository)
                .insertValidatedClaim(eq(RUN_ID), eq(USER_ID), eq(PROFILE_ID), eq(0), any());
        order.verify(evaluationRunRepository).tryFinalizeOwned(
                RUN_ID, USER_ID, PROFILE_ID, EvaluationRun.Status.SUCCEEDED, Optional.empty(), 0L);
    }

    @Test
    void zeroClaimCandidatePersistsHeaderWithoutClaims() {
        stubPendingRunWithSucceededJob(ZERO_CLAIM_JSON);
        when(evaluationRunRepository.tryFinalizeOwned(
                RUN_ID, USER_ID, PROFILE_ID, EvaluationRun.Status.SUCCEEDED, Optional.empty(), 0L))
                .thenReturn(succeededRun());

        ConsumptionResult result = realValidatorService.consumeForReadyInput(
                ready(), new UserContext(USER_ID));

        assertThat(result).isInstanceOfSatisfying(ConsumptionResult.Consumed.class, consumed ->
                assertThat(((Validated) consumed.outcome().groundingResult()).candidate().claims()).isEmpty());
        // 零 claim 也保存 header：区分“已评估且零 claim”与“尚未评估”。
        verify(evaluationRunRepository).insertValidatedCandidate(eq(RUN_ID), eq(USER_ID), eq(PROFILE_ID), any());
        verify(evaluationRunRepository, never())
                .insertValidatedClaim(any(), any(), any(), anyInt(), any());
    }

    // --- Consumed：safe rejection ---

    @Test
    void rejectedOutputFinalizesFailedRunWithSafeReason() {
        stubPendingRunWithSucceededJob(REJECTED_CLAIM_JSON);
        when(evaluationRunRepository.tryFinalizeOwned(
                RUN_ID, USER_ID, PROFILE_ID, EvaluationRun.Status.FAILED,
                Optional.of(RejectionReason.QUOTE_MISMATCH), 0L))
                .thenReturn(failedRun(RejectionReason.QUOTE_MISMATCH));

        ConsumptionResult result = realValidatorService.consumeForReadyInput(
                ready(), new UserContext(USER_ID));

        assertThat(result).isInstanceOfSatisfying(ConsumptionResult.Consumed.class, consumed -> {
            assertThat(consumed.outcome().run().status()).isEqualTo(EvaluationRun.Status.FAILED);
            assertThat(((Rejected) consumed.outcome().groundingResult()).reason())
                    .isEqualTo(RejectionReason.QUOTE_MISMATCH);
        });
        verify(modelCallJobRepository).tryConsumeSucceededResult(JOB_ID, USER_ID, 0L, 0L);
        verify(evaluationRunRepository, never()).insertValidatedCandidate(any(), any(), any(), any());
        verify(evaluationRunRepository, never()).insertValidatedClaim(any(), any(), any(), anyInt(), any());
    }

    // --- Existing：terminal replay，不重新 grounding ---

    @Test
    void terminalSucceededRunReplaysDurableCandidateWithoutRegrounding() {
        when(evaluationRunRepository.findOwnedBySessionIdForUpdate(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(succeededRun()));
        ValidatedSemanticCandidate candidate = candidate(List.of(new GroundedClaim(
                "order-drink", "medium coffee", 0, 17, 30,
                com.dailylanguage.evaluator.domain.SemanticEvaluationOutput.IssueType.NATURALNESS,
                "explanation", 0.7)));
        when(evaluationRunRepository.findOwnedCandidateByRunId(RUN_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(candidate));

        ConsumptionResult result = mockValidatorService.consumeForReadyInput(
                ready(), new UserContext(USER_ID));

        assertThat(result).isInstanceOfSatisfying(ConsumptionResult.Existing.class, existing ->
                assertThat(((Validated) existing.outcome().groundingResult()).candidate()).isEqualTo(candidate));
        verifyNoInteractions(validator, modelCallJobRepository);
    }

    @Test
    void terminalFailedRunReplaysRejectionReason() {
        when(evaluationRunRepository.findOwnedBySessionIdForUpdate(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(failedRun(RejectionReason.UNSUPPORTED_ISSUE)));

        ConsumptionResult result = mockValidatorService.consumeForReadyInput(
                ready(), new UserContext(USER_ID));

        assertThat(result).isInstanceOfSatisfying(ConsumptionResult.Existing.class, existing -> {
            assertThat(((Rejected) existing.outcome().groundingResult()).reason())
                    .isEqualTo(RejectionReason.UNSUPPORTED_ISSUE);
        });
        verifyNoInteractions(validator, modelCallJobRepository);
        verify(evaluationRunRepository, never()).findOwnedCandidateByRunId(any(), any(), any());
    }

    // --- Pending / DeferredToReconciliation ---

    @Test
    void createdOrRunningJobIsPending() {
        stubPendingRun(job(ModelCallJob.ExecutionStatus.CREATED,
                ModelCallJob.ConsumptionStatus.NOT_READY, 0L));
        assertThat(realValidatorService.consumeForReadyInput(ready(), new UserContext(USER_ID)))
                .isEqualTo(new ConsumptionResult.Pending());

        Mockito.reset(evaluationRunRepository, modelCallJobRepository);
        stubPendingRun(job(ModelCallJob.ExecutionStatus.RUNNING,
                ModelCallJob.ConsumptionStatus.NOT_READY, 1L));
        assertThat(realValidatorService.consumeForReadyInput(ready(), new UserContext(USER_ID)))
                .isEqualTo(new ConsumptionResult.Pending());
        verifyNoInteractions(validator);
    }

    @Test
    void terminalModelFailureDefersToReconciliation() {
        for (ModelCallJob.ExecutionStatus failed : List.of(
                ModelCallJob.ExecutionStatus.FAILED,
                ModelCallJob.ExecutionStatus.TIMED_OUT,
                ModelCallJob.ExecutionStatus.OUTCOME_UNKNOWN,
                ModelCallJob.ExecutionStatus.SUBMISSION_REJECTED)) {
            Mockito.reset(evaluationRunRepository, modelCallJobRepository);
            stubPendingRun(job(failed, ModelCallJob.ConsumptionStatus.NOT_READY, 1L));

            assertThat(realValidatorService.consumeForReadyInput(ready(), new UserContext(USER_ID)))
                    .as("execution status %s", failed)
                    .isEqualTo(new ConsumptionResult.DeferredToReconciliation());
        }
        verifyNoInteractions(validator);
    }

    @Test
    void expiredOrDepletedResultDefersToReconciliation() {
        for (ModelCallJob.ConsumptionStatus depleted : List.of(
                ModelCallJob.ConsumptionStatus.EXPIRED,
                ModelCallJob.ConsumptionStatus.STALE,
                ModelCallJob.ConsumptionStatus.DISCARDED)) {
            Mockito.reset(evaluationRunRepository, modelCallJobRepository);
            stubPendingRun(job(ModelCallJob.ExecutionStatus.SUCCEEDED, depleted, 1L));
            assertThat(realValidatorService.consumeForReadyInput(ready(), new UserContext(USER_ID)))
                    .as("consumption status %s", depleted)
                    .isEqualTo(new ConsumptionResult.DeferredToReconciliation());
        }

        Mockito.reset(evaluationRunRepository, modelCallJobRepository);
        // NOT_READY 但数据库 CAS 拒绝：重读同一 durable row 后归类为 DB expiry。
        stubPendingRun(job(ModelCallJob.ExecutionStatus.SUCCEEDED,
                ModelCallJob.ConsumptionStatus.NOT_READY, 1L, OffsetDateTime.now().minusHours(1)));
        when(modelCallJobRepository.findTextGenerationResultByJobIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(new TextGenerationResponse(
                        new ProviderId("deepseek"), new ModelId("deepseek-chat"),
                        ZERO_CLAIM_JSON, FinishReason.COMPLETED, Optional.empty())));
        when(modelCallJobRepository.tryConsumeSucceededResult(JOB_ID, USER_ID, 0L, 1L))
                .thenReturn(Optional.empty());
        assertThat(realValidatorService.consumeForReadyInput(ready(), new UserContext(USER_ID)))
                .isEqualTo(new ConsumptionResult.DeferredToReconciliation());
        verify(evaluationRunRepository, never()).tryFinalizeOwned(any(), any(), any(), any(), any(), anyLong());
    }

    // --- 持久化不变量损坏：fail closed ---

    @Test
    void consumedJobWithPendingRunIsInvariantCorruption() {
        stubPendingRun(job(ModelCallJob.ExecutionStatus.SUCCEEDED,
                ModelCallJob.ConsumptionStatus.CONSUMED, 2L));

        assertThatThrownBy(() ->
                realValidatorService.consumeForReadyInput(ready(), new UserContext(USER_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("evaluation job is already consumed while its run is still pending");
    }

    @Test
    void missingJobOrIdentityMismatchThrows() {
        List<ModelCallJob> brokenJobs = List.of(
                job(ModelPurpose.PLANNING, ModelOperation.TEXT_GENERATION, RUN_ID,
                        EvaluationRun.WORKFLOW_STEP_ID, 0L, PROFILE_ID),
                job(ModelPurpose.EVALUATION, ModelOperation.EMBEDDING, RUN_ID,
                        EvaluationRun.WORKFLOW_STEP_ID, 0L, PROFILE_ID),
                job(ModelPurpose.EVALUATION, ModelOperation.TEXT_GENERATION, UUID.randomUUID(),
                        EvaluationRun.WORKFLOW_STEP_ID, 0L, PROFILE_ID),
                job(ModelPurpose.EVALUATION, ModelOperation.TEXT_GENERATION, RUN_ID,
                        "OTHER_STEP", 0L, PROFILE_ID),
                job(ModelPurpose.EVALUATION, ModelOperation.TEXT_GENERATION, RUN_ID,
                        EvaluationRun.WORKFLOW_STEP_ID, 1L, PROFILE_ID),
                job(ModelPurpose.EVALUATION, ModelOperation.TEXT_GENERATION, RUN_ID,
                        EvaluationRun.WORKFLOW_STEP_ID, 0L, OTHER_PROFILE_ID));
        for (ModelCallJob brokenJob : brokenJobs) {
            Mockito.reset(evaluationRunRepository, modelCallJobRepository);
            stubPendingRun(brokenJob);

            assertThatThrownBy(() ->
                    realValidatorService.consumeForReadyInput(ready(), new UserContext(USER_ID)))
                    .as("job %s", brokenJob)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("evaluation run has a missing or inconsistent evaluation job");
        }

        Mockito.reset(evaluationRunRepository, modelCallJobRepository);
        when(evaluationRunRepository.findOwnedBySessionIdForUpdate(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(pendingRun()));
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() ->
                realValidatorService.consumeForReadyInput(ready(), new UserContext(USER_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("evaluation run has a missing or inconsistent evaluation job");
    }

    @Test
    void succeededJobWithoutResultRowThrows() {
        stubPendingRun(job(ModelCallJob.ExecutionStatus.SUCCEEDED,
                ModelCallJob.ConsumptionStatus.NOT_READY, 1L));
        when(modelCallJobRepository.findTextGenerationResultByJobIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                realValidatorService.consumeForReadyInput(ready(), new UserContext(USER_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("succeeded evaluation job is missing its durable text generation result");
    }

    @Test
    void consumptionCasRejectedByDatabaseExpiryDefersWithoutOutcomePersistence() {
        stubPendingRunWithSucceededJob(VALID_CLAIM_JSON);
        when(modelCallJobRepository.tryConsumeSucceededResult(JOB_ID, USER_ID, 0L, 0L))
                .thenReturn(Optional.empty());

        assertThat(realValidatorService.consumeForReadyInput(ready(), new UserContext(USER_ID)))
                .isEqualTo(new ConsumptionResult.DeferredToReconciliation());
        verify(evaluationRunRepository, never()).insertValidatedCandidate(any(), any(), any(), any());
        verify(evaluationRunRepository, never()).tryFinalizeOwned(any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void consumptionCasLosingToAnotherConsumerFailsClosed() {
        ModelCallJob readyJob = job(ModelCallJob.ExecutionStatus.SUCCEEDED,
                ModelCallJob.ConsumptionStatus.NOT_READY, 0L);
        when(evaluationRunRepository.findOwnedBySessionIdForUpdate(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(pendingRun()));
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(readyJob), Optional.of(consumedJob()));
        when(modelCallJobRepository.findTextGenerationResultByJobIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(new TextGenerationResponse(
                        new ProviderId("deepseek"), new ModelId("deepseek-chat"),
                        ZERO_CLAIM_JSON, FinishReason.COMPLETED, Optional.empty())));
        when(modelCallJobRepository.tryConsumeSucceededResult(JOB_ID, USER_ID, 0L, 0L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                realValidatorService.consumeForReadyInput(ready(), new UserContext(USER_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("evaluation job was consumed without a matching evaluation outcome");
    }

    @Test
    void claimPersistenceFailurePropagatesWithoutFinalize() {
        stubPendingRunWithSucceededJob(VALID_CLAIM_JSON);
        Mockito.doThrow(new IllegalStateException("claim insert failed"))
                .when(evaluationRunRepository)
                .insertValidatedClaim(any(), any(), any(), anyInt(), any());

        assertThatThrownBy(() ->
                realValidatorService.consumeForReadyInput(ready(), new UserContext(USER_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("claim insert failed");
        verify(evaluationRunRepository, never()).tryFinalizeOwned(any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void finalizeCasEmptyThrows() {
        stubPendingRunWithSucceededJob(VALID_CLAIM_JSON);
        // repository 是 CAS 零行 -> ISE 的归属者；service 必须原样传播并放弃本次 outcome。
        when(evaluationRunRepository.tryFinalizeOwned(
                RUN_ID, USER_ID, PROFILE_ID, EvaluationRun.Status.SUCCEEDED, Optional.empty(), 0L))
                .thenThrow(new IllegalStateException(
                        "evaluation run finalize transition was not recorded"));

        assertThatThrownBy(() ->
                realValidatorService.consumeForReadyInput(ready(), new UserContext(USER_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("evaluation run finalize transition was not recorded");
    }

    // --- 业务分支与编程错误 ---

    @Test
    void unknownRunOrMismatchedCallerReturnsTypedResults() {
        when(evaluationRunRepository.findOwnedBySessionIdForUpdate(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.empty());
        assertThat(realValidatorService.consumeForReadyInput(ready(), new UserContext(USER_ID)))
                .isEqualTo(new ConsumptionResult.NotFound());

        Mockito.reset(evaluationRunRepository, modelCallJobRepository);
        assertThat(realValidatorService.consumeForReadyInput(ready(), new UserContext(OTHER_USER_ID)))
                .isEqualTo(new ConsumptionResult.InconsistentInput());
        verifyNoInteractions(evaluationRunRepository, modelCallJobRepository, validator);
    }

    @Test
    void readySnapshotNotBoundToRunReturnsInconsistentInputBeforeReadingJob() {
        when(evaluationRunRepository.findOwnedBySessionIdForUpdate(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(pendingRun()));
        PracticeSession mismatchedSession = new PracticeSession(
                SESSION_ID, UUID.randomUUID(), PracticeSession.Status.COMPLETED, STARTED_AT,
                Optional.of(COMPLETED_AT), Optional.empty());
        Ready mismatchedReady = new Ready(new GroundedEvaluationInput(
                USER_ID, PROFILE_ID, task(), mismatchedSession, assessment(), responses(), material()));

        assertThat(realValidatorService.consumeForReadyInput(mismatchedReady, new UserContext(USER_ID)))
                .isEqualTo(new ConsumptionResult.InconsistentInput());
        verifyNoInteractions(modelCallJobRepository, validator);
    }

    @Test
    void nullArgumentsAreProgrammingErrors() {
        assertThatThrownBy(() -> realValidatorService.consumeForReadyInput(null, new UserContext(USER_ID)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("ready must not be null");
        assertThatThrownBy(() -> realValidatorService.consumeForReadyInput(ready(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("userContext must not be null");
    }

    @Test
    void consumeRunsInSingleReadWriteTransaction() throws NoSuchMethodException {
        Transactional transactional = EvaluationResultConsumptionService.class
                .getMethod("consumeForReadyInput", Ready.class, UserContext.class)
                .getAnnotation(Transactional.class);

        // 所有 Job / Run / candidate mutation 加入同一默认读写事务。
        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isFalse();
    }

    // --- stubs 与 fixtures ---

    private void stubPendingRunWithSucceededJob(String generatedJson) {
        stubPendingRun(job(ModelCallJob.ExecutionStatus.SUCCEEDED,
                ModelCallJob.ConsumptionStatus.NOT_READY, 0L));
        when(modelCallJobRepository.findTextGenerationResultByJobIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(new TextGenerationResponse(
                        new ProviderId("deepseek"), new ModelId("deepseek-chat"),
                        generatedJson, FinishReason.COMPLETED, Optional.empty())));
        when(modelCallJobRepository.tryConsumeSucceededResult(JOB_ID, USER_ID, 0L, 0L))
                .thenReturn(Optional.of(consumedJob()));
    }

    private void stubPendingRun(ModelCallJob boundJob) {
        when(evaluationRunRepository.findOwnedBySessionIdForUpdate(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(pendingRun()));
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(boundJob));
    }

    private static Ready ready() {
        return new Ready(new GroundedEvaluationInput(
                USER_ID, PROFILE_ID, task(), completedSession(), assessment(), responses(), material()));
    }

    private static EvaluationRun pendingRun() {
        return new EvaluationRun(RUN_ID, SESSION_ID, JOB_ID, EvaluationRun.Status.PENDING,
                0L, 0L, CREATED_AT, Optional.empty(), Optional.empty());
    }

    private static EvaluationRun succeededRun() {
        return new EvaluationRun(RUN_ID, SESSION_ID, JOB_ID, EvaluationRun.Status.SUCCEEDED,
                0L, 1L, CREATED_AT, Optional.of(COMPLETED_AT), Optional.empty());
    }

    private static EvaluationRun failedRun(RejectionReason reason) {
        return new EvaluationRun(RUN_ID, SESSION_ID, JOB_ID, EvaluationRun.Status.FAILED,
                0L, 1L, CREATED_AT, Optional.of(COMPLETED_AT), Optional.of(reason));
    }

    private static ModelCallJob job(
            ModelCallJob.ExecutionStatus executionStatus,
            ModelCallJob.ConsumptionStatus consumptionStatus,
            long rowVersion) {
        return job(executionStatus, consumptionStatus, rowVersion, EXPIRES_AT);
    }

    private static ModelCallJob job(
            ModelCallJob.ExecutionStatus executionStatus,
            ModelCallJob.ConsumptionStatus consumptionStatus,
            long rowVersion,
            OffsetDateTime expiresAt) {
        // terminal executionStatus 必须携带 completedAt；FAILED / TIMED_OUT 必须携带匹配的 failure。
        boolean nonTerminal = executionStatus == ModelCallJob.ExecutionStatus.CREATED
                || executionStatus == ModelCallJob.ExecutionStatus.RUNNING;
        Optional<ModelFailure> failure =
                executionStatus == ModelCallJob.ExecutionStatus.FAILED
                        ? Optional.of(new ModelFailure(
                                ModelFailureKind.CAPABILITY_UNAVAILABLE,
                                Optional.empty(), Optional.empty(), Optional.empty()))
                        : executionStatus == ModelCallJob.ExecutionStatus.TIMED_OUT
                                ? Optional.of(new ModelFailure(
                                        ModelFailureKind.TIMEOUT,
                                        Optional.empty(), Optional.empty(), Optional.empty()))
                                : Optional.empty();
        return job(ModelPurpose.EVALUATION, ModelOperation.TEXT_GENERATION, RUN_ID,
                EvaluationRun.WORKFLOW_STEP_ID, 0L, PROFILE_ID,
                JOB_ID, executionStatus, consumptionStatus, rowVersion, expiresAt,
                nonTerminal ? Optional.empty() : Optional.of(COMPLETED_AT), failure);
    }

    private static ModelCallJob job(
            ModelPurpose purpose, ModelOperation operation, UUID workflowId,
            String workflowStepId, long workflowVersion, UUID profileId) {
        return job(purpose, operation, workflowId, workflowStepId, workflowVersion, profileId,
                JOB_ID, ModelCallJob.ExecutionStatus.SUCCEEDED,
                ModelCallJob.ConsumptionStatus.NOT_READY, 1L, EXPIRES_AT,
                Optional.of(COMPLETED_AT), Optional.empty());
    }

    private static ModelCallJob consumedJob() {
        return job(ModelPurpose.EVALUATION, ModelOperation.TEXT_GENERATION, RUN_ID,
                EvaluationRun.WORKFLOW_STEP_ID, 0L, PROFILE_ID,
                JOB_ID, ModelCallJob.ExecutionStatus.SUCCEEDED,
                ModelCallJob.ConsumptionStatus.CONSUMED, 1L, EXPIRES_AT,
                Optional.of(COMPLETED_AT), Optional.empty());
    }

    private static ModelCallJob job(
            ModelPurpose purpose, ModelOperation operation, UUID workflowId,
            String workflowStepId, long workflowVersion, UUID profileId,
            UUID jobId, ModelCallJob.ExecutionStatus executionStatus,
            ModelCallJob.ConsumptionStatus consumptionStatus, long rowVersion,
            OffsetDateTime expiresAt, Optional<OffsetDateTime> completedAt,
            Optional<ModelFailure> failure) {
        return new ModelCallJob(
                jobId, USER_ID, Optional.of(profileId), purpose, operation,
                Optional.empty(), Optional.empty(), workflowId, workflowStepId, workflowVersion,
                executionStatus, consumptionStatus, failure, rowVersion,
                CREATED_AT, completedAt, expiresAt);
    }

    private static ValidatedSemanticCandidate candidate(List<GroundedClaim> claims) {
        return new ValidatedSemanticCandidate(
                PROFILE_ID, SESSION_ID, new MaterialIdentity("en-builtin-cafe-request", "v1"),
                "builtin-text-communication-rubric/v1", "en", "M1_GROUNDED_QUOTE_V1", claims);
    }

    private static LearningTask task() {
        return new LearningTask(
                TASK_ID, USER_ID, PROFILE_ID,
                new MaterialIdentity("en-builtin-cafe-request", "v1"),
                "en", "zh-cn", MaterialDifficulty.FOUNDATION, 10,
                "CAFE_SIMPLE_REQUEST", "objective",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK,
                LearningTask.Status.COMPLETED,
                CREATED_AT, Optional.of(STARTED_AT), Optional.of(COMPLETED_AT));
    }

    private static PracticeSession completedSession() {
        return new PracticeSession(
                SESSION_ID, TASK_ID, PracticeSession.Status.COMPLETED, STARTED_AT,
                Optional.of(COMPLETED_AT), Optional.empty());
    }

    private static DeterministicAssessment assessment() {
        return new DeterministicAssessment(
                SESSION_ID, DeterministicTextAssessmentPolicy.ASSESSMENT_POLICY_VERSION,
                390L, COMPLETED_AT,
                List.of(new StepResult("order-drink", StepKind.EXACT, StepOutcome.MATCHED)));
    }

    private static List<LearnerResponse> responses() {
        return List.of(new LearnerResponse(
                SESSION_ID, "order-drink", "Could I have a medium coffee, please?", STARTED_AT));
    }

    private static PublishedLearningMaterial material() {
        return new PublishedLearningMaterial(
                new MaterialIdentity("en-builtin-cafe-request", "v1"),
                new TargetPracticeCore(
                        "en", MaterialDifficulty.FOUNDATION, "CAFE_SIMPLE_REQUEST", "objective",
                        "target text", null,
                        List.of(new TextPracticeStep("order-drink", TextStepKind.EXACT,
                                "prompt", List.of("answer"))),
                        "builtin-text-communication-rubric/v1"),
                List.of(new SupportScaffold("zh-cn", "instruction", "explanation", "hint", null)),
                new MaterialSourceLineage("PROJECT_ORIGINAL", "1", "AGPL-3.0",
                        "sha256:" + "0".repeat(64)));
    }
}
