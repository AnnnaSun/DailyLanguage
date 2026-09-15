package com.dailylanguage.planner.application;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.dailylanguage.content.domain.AvailableMaterialSummary;
import com.dailylanguage.content.domain.LearningMaterialCatalog;
import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.content.domain.MaterialQueryResult;
import com.dailylanguage.content.domain.MaterialSourceLineage;
import com.dailylanguage.content.domain.MaterialUnavailableReason;
import com.dailylanguage.content.domain.PublishedLearningMaterial;
import com.dailylanguage.content.domain.SupportScaffold;
import com.dailylanguage.content.domain.TargetPracticeCore;
import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.infrastructure.ModelCallJobRepository;
import com.dailylanguage.modelgateway.result.ModelFailure;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.planner.domain.PlanningRequest;
import com.dailylanguage.planner.domain.PlanningResult;
import com.dailylanguage.planner.domain.PlanningRun;
import com.dailylanguage.planner.infrastructure.LearningTaskRepository;
import com.dailylanguage.planner.infrastructure.PlanningRunRepository;
import com.dailylanguage.security.domain.UserContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlannerEnrichmentFinalizationServiceTests {

    private static final UUID USER_ID = UUID.fromString("019cc10c-a56a-7000-8000-0000000000b1");
    private static final UUID PROFILE_ID = UUID.fromString("019cc10c-a56a-7000-8000-0000000000b2");
    private static final UUID RUN_ID = UUID.fromString("019cc10c-a56a-7000-8000-0000000000b3");
    private static final UUID JOB_ID = UUID.fromString("019cc10c-a56a-7000-8000-0000000000b4");
    private static final UUID TASK_ID = UUID.fromString("019cc10c-a56a-7000-8000-0000000000b5");
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-15T08:00:00.123Z");
    private static final OffsetDateTime COMPLETED_AT = OffsetDateTime.parse("2026-09-15T08:00:12.456Z");
    private static final OffsetDateTime EXPIRES_AT = OffsetDateTime.parse("2026-09-15T08:05:00.123Z");
    private static final UserContext USER = new UserContext(USER_ID);
    private static final MaterialIdentity CAFE = new MaterialIdentity("en-builtin-cafe-request", "v2");
    private static final MaterialIdentity GREETING =
            new MaterialIdentity("en-builtin-greeting-intro", "v1");
    private static final String ENRICHED_REASON = "今天先练习问候与自我介绍。";

    /** snapshot index 0 是唯一 deterministic fallback；enrichment 只能选择 snapshot member。 */
    private static final PlanningRun.Snapshot SNAPSHOT = new PlanningRun.Snapshot(PROFILE_ID, List.of(
            new PlanningRun.Candidate(0, CAFE),
            new PlanningRun.Candidate(1, GREETING)));
    private static final PlanningRequest REQUEST = new PlanningRequest(
            new LanguageProfileIdentity(PROFILE_ID, USER_ID, "en"),
            "zh-cn",
            MaterialDifficulty.FOUNDATION,
            7,
            Set.of());
    private static final String VALID_ENRICHMENT_JSON = """
            {"materialId":"en-builtin-greeting-intro","publishedVersion":"v1","recommendationReason":"%s"}"""
            .formatted(ENRICHED_REASON);
    private static final String UNKNOWN_CANDIDATE_JSON = """
            {"materialId":"en-builtin-not-offered","publishedVersion":"v1","recommendationReason":"任意理由"}""";

    private final PlanningRunRepository planningRunRepository = mock(PlanningRunRepository.class);
    private final ModelCallJobRepository modelCallJobRepository = mock(ModelCallJobRepository.class);
    private final LearningTaskRepository learningTaskRepository = mock(LearningTaskRepository.class);
    private final FakeMaterialCatalog catalog = new FakeMaterialCatalog();
    private final PlannerEnrichmentFinalizationService service = new PlannerEnrichmentFinalizationService(
            planningRunRepository,
            modelCallJobRepository,
            learningTaskRepository,
            new EligibleLearningTaskCandidateReader(catalog));

    @Test
    void replaysTerminalRunWithDurableBoundTaskWithoutMutatingAnything() {
        PlanningRun terminal = terminalRun(PlanningRun.Status.MODEL_APPLIED, Optional.empty(), TASK_ID, 0L);
        LearningTask bound = task(GREETING, LearningTaskPlan.PlanningReason.MODEL_ENRICHED,
                Optional.of(ENRICHED_REASON));
        when(planningRunRepository.findOwnedForUpdate(RUN_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(terminal));
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(bound));

        PlannerEnrichmentFinalizationService.FinalizationResult result = service.finalizeRun(
                RUN_ID, REQUEST,
                new PlannerEnrichmentJobAwaiter.AwaitResult.Terminal(
                        plannerJob(ModelCallJob.ExecutionStatus.SUCCEEDED,
                                ModelCallJob.ConsumptionStatus.CONSUMED, 3L)),
                USER);

        assertThat(result).isEqualTo(
                new PlannerEnrichmentFinalizationService.FinalizationResult.Existing(bound, terminal));
        verify(planningRunRepository, never()).findOwnedSnapshot(any(), any(), any());
        verify(modelCallJobRepository, never()).findByIdAndUserId(any(), any());
        verify(learningTaskRepository, never()).createOwned(any(), any());
    }

    @Test
    void returnsNotFoundWhenRunIsInvisibleToCaller() {
        when(planningRunRepository.findOwnedForUpdate(RUN_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.empty());

        PlannerEnrichmentFinalizationService.FinalizationResult result = service.finalizeRun(
                RUN_ID, REQUEST, new PlannerEnrichmentJobAwaiter.AwaitResult.BudgetExhausted(), USER);

        assertThat(result).isEqualTo(new PlannerEnrichmentFinalizationService.FinalizationResult.NotFound());
        verify(planningRunRepository, never()).findOwnedSnapshot(any(), any(), any());
        verify(modelCallJobRepository, never()).findByIdAndUserId(any(), any());
        verify(learningTaskRepository, never()).createOwned(any(), any());
    }

    @Test
    void rejectsRequestOwnedByAnotherUser() {
        PlanningRequest foreignRequest = new PlanningRequest(
                new LanguageProfileIdentity(PROFILE_ID, UUID.randomUUID(), "en"),
                "zh-cn", MaterialDifficulty.FOUNDATION, 7, Set.of());

        assertThatThrownBy(() -> service.finalizeRun(
                RUN_ID, foreignRequest,
                new PlannerEnrichmentJobAwaiter.AwaitResult.BudgetExhausted(), USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("userContext must match the planning request language profile owner");
    }

    @Test
    void nullArgumentsAreProgrammingErrors() {
        assertThatThrownBy(() -> service.finalizeRun(
                null, REQUEST, new PlannerEnrichmentJobAwaiter.AwaitResult.BudgetExhausted(), USER))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("planningRunId must not be null");
        assertThatThrownBy(() -> service.finalizeRun(
                RUN_ID, null, new PlannerEnrichmentJobAwaiter.AwaitResult.BudgetExhausted(), USER))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("request must not be null");
        assertThatThrownBy(() -> service.finalizeRun(RUN_ID, REQUEST, null, USER))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("awaitResult must not be null");
        assertThatThrownBy(() -> service.finalizeRun(
                RUN_ID, REQUEST, new PlannerEnrichmentJobAwaiter.AwaitResult.BudgetExhausted(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("userContext must not be null");
    }

    @Test
    void failsClosedWhenBoundJobIsMissingOrIdentityDrifts() {
        stubPendingWorkflow();
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.finalizeRun(
                RUN_ID, REQUEST, terminalAwait(plannerJob(
                        ModelCallJob.ExecutionStatus.SUCCEEDED,
                        ModelCallJob.ConsumptionStatus.NOT_READY, 2L)), USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planning run has a missing or inconsistent planning job");

        // workflow_id 与 Run 脱钩。
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(new ModelCallJob(
                        JOB_ID, USER_ID, Optional.of(PROFILE_ID),
                        ModelPurpose.PLANNING, ModelOperation.TEXT_GENERATION,
                        Optional.empty(), Optional.empty(),
                        UUID.randomUUID(), PlanningRun.WORKFLOW_STEP_ID, 0L,
                        ModelCallJob.ExecutionStatus.SUCCEEDED, ModelCallJob.ConsumptionStatus.NOT_READY,
                        Optional.empty(), 2L, CREATED_AT, Optional.of(COMPLETED_AT), EXPIRES_AT)));
        assertThatThrownBy(() -> service.finalizeRun(
                RUN_ID, REQUEST, terminalAwait(plannerJob(
                        ModelCallJob.ExecutionStatus.SUCCEEDED,
                        ModelCallJob.ConsumptionStatus.NOT_READY, 2L)), USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planning run has a missing or inconsistent planning job");

        // workflow purpose / profile 漂移。
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(new ModelCallJob(
                        JOB_ID, USER_ID, Optional.of(PROFILE_ID),
                        ModelPurpose.EVALUATION, ModelOperation.TEXT_GENERATION,
                        Optional.empty(), Optional.empty(),
                        RUN_ID, PlanningRun.WORKFLOW_STEP_ID, 0L,
                        ModelCallJob.ExecutionStatus.SUCCEEDED, ModelCallJob.ConsumptionStatus.NOT_READY,
                        Optional.empty(), 2L, CREATED_AT, Optional.of(COMPLETED_AT), EXPIRES_AT)));
        assertThatThrownBy(() -> service.finalizeRun(
                RUN_ID, REQUEST, terminalAwait(plannerJob(
                        ModelCallJob.ExecutionStatus.SUCCEEDED,
                        ModelCallJob.ConsumptionStatus.NOT_READY, 2L)), USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planning run has a missing or inconsistent planning job");

        verify(learningTaskRepository, never()).createOwned(any(), any());
        verify(planningRunRepository, never())
                .tryFinalizeOwned(any(), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void failsClosedWhenAwaitedJobDoesNotMatchTheRunBoundJob() {
        stubPendingWorkflow();
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(plannerJob(
                        ModelCallJob.ExecutionStatus.SUCCEEDED,
                        ModelCallJob.ConsumptionStatus.NOT_READY, 2L)));
        ModelCallJob foreignAwaitedJob = new ModelCallJob(
                UUID.randomUUID(), USER_ID, Optional.of(PROFILE_ID),
                ModelPurpose.PLANNING, ModelOperation.TEXT_GENERATION,
                Optional.empty(), Optional.empty(),
                RUN_ID, PlanningRun.WORKFLOW_STEP_ID, 0L,
                ModelCallJob.ExecutionStatus.SUCCEEDED, ModelCallJob.ConsumptionStatus.NOT_READY,
                Optional.empty(), 2L, CREATED_AT, Optional.of(COMPLETED_AT), EXPIRES_AT);

        assertThatThrownBy(() -> service.finalizeRun(
                RUN_ID, REQUEST,
                new PlannerEnrichmentJobAwaiter.AwaitResult.Terminal(foreignAwaitedJob), USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("awaited planning job does not match the run bound planning job");
        verify(learningTaskRepository, never()).createOwned(any(), any());
    }

    @Test
    void failsClosedWhenJobHasNotReachedTerminalExecutionStatus() {
        stubPendingWorkflow();
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(plannerJob(
                        ModelCallJob.ExecutionStatus.RUNNING,
                        ModelCallJob.ConsumptionStatus.NOT_READY, 1L)));

        assertThatThrownBy(() -> service.finalizeRun(
                RUN_ID, REQUEST, terminalAwait(plannerJob(
                        ModelCallJob.ExecutionStatus.RUNNING,
                        ModelCallJob.ConsumptionStatus.NOT_READY, 1L)), USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planning job has not reached a terminal execution status");
        verify(learningTaskRepository, never()).createOwned(any(), any());
    }

    @Test
    void appliesValidSucceededResultAsModelEnrichedTask() {
        ModelCallJob succeeded = plannerJob(
                ModelCallJob.ExecutionStatus.SUCCEEDED, ModelCallJob.ConsumptionStatus.NOT_READY, 2L);
        stubPendingWorkflow();
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(succeeded));
        when(modelCallJobRepository.findTextGenerationResultByJobIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(resultResponse(VALID_ENRICHMENT_JSON)));
        when(modelCallJobRepository.tryConsumeSucceededResult(JOB_ID, USER_ID, 0L, 2L))
                .thenReturn(Optional.of(plannerJob(
                        ModelCallJob.ExecutionStatus.SUCCEEDED,
                        ModelCallJob.ConsumptionStatus.CONSUMED, 3L)));
        LearningTask enrichedTask = task(GREETING, LearningTaskPlan.PlanningReason.MODEL_ENRICHED,
                Optional.of(ENRICHED_REASON));
        when(learningTaskRepository.createOwned(eq(USER_ID), any(LearningTaskPlan.class)))
                .thenReturn(Optional.of(enrichedTask));
        when(planningRunRepository.tryFinalizeOwned(
                RUN_ID, USER_ID, PROFILE_ID, PlanningRun.Status.MODEL_APPLIED,
                Optional.empty(), TASK_ID, 0L))
                .thenReturn(terminalRun(PlanningRun.Status.MODEL_APPLIED, Optional.empty(), TASK_ID, 0L));

        PlannerEnrichmentFinalizationService.FinalizationResult result = service.finalizeRun(
                RUN_ID, REQUEST, terminalAwait(succeeded), USER);

        assertThat(result).isEqualTo(new PlannerEnrichmentFinalizationService.FinalizationResult.Created(
                enrichedTask, terminalRun(PlanningRun.Status.MODEL_APPLIED, Optional.empty(), TASK_ID, 0L)));
        ArgumentCaptor<LearningTaskPlan> planCaptor =
                ArgumentCaptor.forClass(LearningTaskPlan.class);
        verify(learningTaskRepository).createOwned(eq(USER_ID), planCaptor.capture());
        LearningTaskPlan enrichedPlan = planCaptor.getValue();
        assertThat(enrichedPlan.materialIdentity()).isEqualTo(GREETING);
        assertThat(enrichedPlan.reason()).isEqualTo(LearningTaskPlan.PlanningReason.MODEL_ENRICHED);
        assertThat(enrichedPlan.recommendationReason()).contains(ENRICHED_REASON);
        assertThat(enrichedPlan.languageProfileId()).isEqualTo(PROFILE_ID);
        assertThat(enrichedPlan.estimatedDurationMinutes()).isEqualTo(7);
        verify(modelCallJobRepository).tryConsumeSucceededResult(JOB_ID, USER_ID, 0L, 2L);
    }

    @Test
    void consumesJobAndFallsBackWhenOutputIsRejected() {
        ModelCallJob succeeded = plannerJob(
                ModelCallJob.ExecutionStatus.SUCCEEDED, ModelCallJob.ConsumptionStatus.NOT_READY, 2L);
        stubPendingWorkflow();
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(succeeded));
        when(modelCallJobRepository.findTextGenerationResultByJobIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(resultResponse(UNKNOWN_CANDIDATE_JSON)));
        when(modelCallJobRepository.tryConsumeSucceededResult(JOB_ID, USER_ID, 0L, 2L))
                .thenReturn(Optional.of(plannerJob(
                        ModelCallJob.ExecutionStatus.SUCCEEDED,
                        ModelCallJob.ConsumptionStatus.CONSUMED, 3L)));
        LearningTask fallbackTask = task(CAFE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK, Optional.empty());
        when(learningTaskRepository.createOwned(eq(USER_ID), any(LearningTaskPlan.class)))
                .thenReturn(Optional.of(fallbackTask));
        when(planningRunRepository.tryFinalizeOwned(
                RUN_ID, USER_ID, PROFILE_ID, PlanningRun.Status.FALLBACK_APPLIED,
                Optional.of(PlanningRun.FallbackReason.MODEL_OUTPUT_REJECTED), TASK_ID, 0L))
                .thenReturn(terminalRun(PlanningRun.Status.FALLBACK_APPLIED,
                        Optional.of(PlanningRun.FallbackReason.MODEL_OUTPUT_REJECTED), TASK_ID, 1L));

        PlannerEnrichmentFinalizationService.FinalizationResult result = service.finalizeRun(
                RUN_ID, REQUEST, terminalAwait(succeeded), USER);

        assertThat(result).isEqualTo(new PlannerEnrichmentFinalizationService.FinalizationResult.Created(
                fallbackTask, terminalRun(PlanningRun.Status.FALLBACK_APPLIED,
                        Optional.of(PlanningRun.FallbackReason.MODEL_OUTPUT_REJECTED), TASK_ID, 1L)));
        ArgumentCaptor<LearningTaskPlan> planCaptor =
                ArgumentCaptor.forClass(LearningTaskPlan.class);
        verify(learningTaskRepository).createOwned(eq(USER_ID), planCaptor.capture());
        LearningTaskPlan fallbackPlan = planCaptor.getValue();
        assertThat(fallbackPlan.materialIdentity()).isEqualTo(CAFE);
        assertThat(fallbackPlan.reason())
                    .isEqualTo(LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK);
        assertThat(fallbackPlan.recommendationReason()).isEmpty();
        // rejected output 的 Job 仍被消费，不允许同一 result 再次进入裁决。
        verify(modelCallJobRepository).tryConsumeSucceededResult(JOB_ID, USER_ID, 0L, 2L);
    }

    @Test
    void fallsBackDeterministicallyOnEveryTerminalModelFailure() {
        for (ModelCallJob.ExecutionStatus failureStatus : List.of(
                ModelCallJob.ExecutionStatus.FAILED,
                ModelCallJob.ExecutionStatus.TIMED_OUT,
                ModelCallJob.ExecutionStatus.OUTCOME_UNKNOWN,
                ModelCallJob.ExecutionStatus.SUBMISSION_REJECTED)) {
            ModelCallJob failedJob = plannerJob(failureStatus, ModelCallJob.ConsumptionStatus.NOT_READY, 2L);
            stubPendingWorkflow();
            when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                    .thenReturn(Optional.of(failedJob));
            LearningTask fallbackTask = task(CAFE,
                    LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK, Optional.empty());
            when(learningTaskRepository.createOwned(eq(USER_ID), any(LearningTaskPlan.class)))
                    .thenReturn(Optional.of(fallbackTask));
            when(planningRunRepository.tryFinalizeOwned(
                    RUN_ID, USER_ID, PROFILE_ID, PlanningRun.Status.FALLBACK_APPLIED,
                    Optional.of(PlanningRun.FallbackReason.MODEL_CALL_FAILED), TASK_ID, 0L))
                    .thenReturn(terminalRun(PlanningRun.Status.FALLBACK_APPLIED,
                            Optional.of(PlanningRun.FallbackReason.MODEL_CALL_FAILED), TASK_ID, 1L));

            PlannerEnrichmentFinalizationService.FinalizationResult result = service.finalizeRun(
                    RUN_ID, REQUEST, terminalAwait(failedJob), USER);

            assertThat(result).isEqualTo(new PlannerEnrichmentFinalizationService.FinalizationResult.Created(
                    fallbackTask, terminalRun(PlanningRun.Status.FALLBACK_APPLIED,
                            Optional.of(PlanningRun.FallbackReason.MODEL_CALL_FAILED), TASK_ID, 1L)));
            verify(modelCallJobRepository, never())
                    .findTextGenerationResultByJobIdAndUserId(any(), any());
            verify(modelCallJobRepository, never())
                    .tryConsumeSucceededResult(any(), any(), anyLong(), anyLong());
        }
    }

    @Test
    void fallsBackWhenSucceededResultIsAlreadyDepleted() {
        for (ModelCallJob.ConsumptionStatus depletedStatus : List.of(
                ModelCallJob.ConsumptionStatus.PENDING_CONFIRMATION,
                ModelCallJob.ConsumptionStatus.EXPIRED,
                ModelCallJob.ConsumptionStatus.STALE,
                ModelCallJob.ConsumptionStatus.DISCARDED)) {
            ModelCallJob depletedJob = plannerJob(
                    ModelCallJob.ExecutionStatus.SUCCEEDED, depletedStatus, 3L);
            stubPendingWorkflow();
            when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                    .thenReturn(Optional.of(depletedJob));
            LearningTask fallbackTask = task(CAFE,
                    LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK, Optional.empty());
            when(learningTaskRepository.createOwned(eq(USER_ID), any(LearningTaskPlan.class)))
                    .thenReturn(Optional.of(fallbackTask));
            when(planningRunRepository.tryFinalizeOwned(
                    RUN_ID, USER_ID, PROFILE_ID, PlanningRun.Status.FALLBACK_APPLIED,
                    Optional.of(PlanningRun.FallbackReason.MODEL_RESULT_UNAVAILABLE), TASK_ID, 0L))
                    .thenReturn(terminalRun(PlanningRun.Status.FALLBACK_APPLIED,
                            Optional.of(PlanningRun.FallbackReason.MODEL_RESULT_UNAVAILABLE),
                            TASK_ID, 1L));

            PlannerEnrichmentFinalizationService.FinalizationResult result = service.finalizeRun(
                    RUN_ID, REQUEST, terminalAwait(depletedJob), USER);

            assertThat(result).isEqualTo(new PlannerEnrichmentFinalizationService.FinalizationResult.Created(
                    fallbackTask, terminalRun(PlanningRun.Status.FALLBACK_APPLIED,
                            Optional.of(PlanningRun.FallbackReason.MODEL_RESULT_UNAVAILABLE),
                            TASK_ID, 1L)));
            verify(modelCallJobRepository, never())
                    .findTextGenerationResultByJobIdAndUserId(any(), any());
        }
    }

    @Test
    void failsClosedWhenSucceededJobIsMissingItsDurableResultRow() {
        ModelCallJob succeeded = plannerJob(
                ModelCallJob.ExecutionStatus.SUCCEEDED, ModelCallJob.ConsumptionStatus.NOT_READY, 2L);
        stubPendingWorkflow();
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(succeeded));
        when(modelCallJobRepository.findTextGenerationResultByJobIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.finalizeRun(RUN_ID, REQUEST, terminalAwait(succeeded), USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("succeeded planning job is missing its durable text generation result");
        verify(modelCallJobRepository, never()).tryConsumeSucceededResult(any(), any(), anyLong(), anyLong());
        verify(learningTaskRepository, never()).createOwned(any(), any());
    }

    @Test
    void failsClosedWhenConsumedJobIsPairedWithPendingRun() {
        stubPendingWorkflow();
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(plannerJob(
                        ModelCallJob.ExecutionStatus.SUCCEEDED,
                        ModelCallJob.ConsumptionStatus.CONSUMED, 3L)));

        assertThatThrownBy(() -> service.finalizeRun(RUN_ID, REQUEST, terminalAwait(plannerJob(
                        ModelCallJob.ExecutionStatus.SUCCEEDED,
                        ModelCallJob.ConsumptionStatus.CONSUMED, 3L)), USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planning job is already consumed while its run is still pending");
        verify(learningTaskRepository, never()).createOwned(any(), any());
    }

    @Test
    void consumptionCasExpiredResultFallsBackAsUnavailable() {
        ModelCallJob attempted = plannerJob(
                ModelCallJob.ExecutionStatus.SUCCEEDED, ModelCallJob.ConsumptionStatus.NOT_READY, 2L);
        stubPendingWorkflow();
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(attempted))
                .thenReturn(Optional.of(plannerJob(
                        ModelCallJob.ExecutionStatus.SUCCEEDED,
                        ModelCallJob.ConsumptionStatus.EXPIRED, 2L)));
        when(modelCallJobRepository.findTextGenerationResultByJobIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(resultResponse(VALID_ENRICHMENT_JSON)));
        when(modelCallJobRepository.tryConsumeSucceededResult(JOB_ID, USER_ID, 0L, 2L))
                .thenReturn(Optional.empty());
        LearningTask fallbackTask = task(CAFE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK, Optional.empty());
        when(learningTaskRepository.createOwned(eq(USER_ID), any(LearningTaskPlan.class)))
                .thenReturn(Optional.of(fallbackTask));
        when(planningRunRepository.tryFinalizeOwned(
                RUN_ID, USER_ID, PROFILE_ID, PlanningRun.Status.FALLBACK_APPLIED,
                Optional.of(PlanningRun.FallbackReason.MODEL_RESULT_UNAVAILABLE), TASK_ID, 0L))
                .thenReturn(terminalRun(PlanningRun.Status.FALLBACK_APPLIED,
                        Optional.of(PlanningRun.FallbackReason.MODEL_RESULT_UNAVAILABLE), TASK_ID, 1L));

        PlannerEnrichmentFinalizationService.FinalizationResult result = service.finalizeRun(
                RUN_ID, REQUEST, terminalAwait(attempted), USER);

        assertThat(result).isEqualTo(new PlannerEnrichmentFinalizationService.FinalizationResult.Created(
                fallbackTask, terminalRun(PlanningRun.Status.FALLBACK_APPLIED,
                        Optional.of(PlanningRun.FallbackReason.MODEL_RESULT_UNAVAILABLE), TASK_ID, 1L)));
        // 过期由数据库 CAS 裁决后，不再尝试消费或创建 enriched task。
        verify(modelCallJobRepository, times(1)).tryConsumeSucceededResult(JOB_ID, USER_ID, 0L, 2L);
    }

    @Test
    void consumptionCasFailureFailsClosedOnUnexplainableDurableStates() {
        ModelCallJob attempted = plannerJob(
                ModelCallJob.ExecutionStatus.SUCCEEDED, ModelCallJob.ConsumptionStatus.NOT_READY, 2L);

        // CONSUMED：跨边界不变量损坏。
        stubPendingWorkflow();
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(attempted))
                .thenReturn(Optional.of(plannerJob(
                        ModelCallJob.ExecutionStatus.SUCCEEDED,
                        ModelCallJob.ConsumptionStatus.CONSUMED, 3L)));
        stubSuccessfulResultAndFailedConsumption();
        assertThatThrownBy(() -> service.finalizeRun(RUN_ID, REQUEST, terminalAwait(attempted), USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planning job was consumed without a matching planning outcome");

        // NOT_READY 但 row version 漂移。
        stubPendingWorkflow();
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(attempted))
                .thenReturn(Optional.of(plannerJob(
                        ModelCallJob.ExecutionStatus.SUCCEEDED,
                        ModelCallJob.ConsumptionStatus.NOT_READY, 3L)));
        stubSuccessfulResultAndFailedConsumption();
        assertThatThrownBy(() -> service.finalizeRun(RUN_ID, REQUEST, terminalAwait(attempted), USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planning job row version changed without a consumption transition");

        // NOT_READY 同版本但 expiry 也失败：无法解释的状态。
        stubPendingWorkflow();
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(attempted))
                .thenReturn(Optional.of(attempted));
        stubSuccessfulResultAndFailedConsumption();
        when(modelCallJobRepository.tryExpireSucceededResult(JOB_ID, USER_ID, 2L))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.finalizeRun(RUN_ID, REQUEST, terminalAwait(attempted), USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planning result transition failed without expiry or a durable state change");

        // Job 消失。
        stubPendingWorkflow();
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(attempted))
                .thenReturn(Optional.empty());
        stubSuccessfulResultAndFailedConsumption();
        assertThatThrownBy(() -> service.finalizeRun(RUN_ID, REQUEST, terminalAwait(attempted), USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planning job disappeared after its consumption transition failed");

        verify(learningTaskRepository, never()).createOwned(any(), any());
    }

    @Test
    void budgetExhaustedFallsBackAtomicallyAndMarksLateSucceededResultStale() {
        ModelCallJob lateSucceeded = plannerJob(
                ModelCallJob.ExecutionStatus.SUCCEEDED, ModelCallJob.ConsumptionStatus.NOT_READY, 2L);
        stubPendingWorkflow();
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(lateSucceeded));
        when(modelCallJobRepository.findTextGenerationResultByJobIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(resultResponse(VALID_ENRICHMENT_JSON)));
        when(modelCallJobRepository.tryMarkSucceededResultStale(JOB_ID, USER_ID, 1L, 2L))
                .thenReturn(Optional.of(plannerJob(
                        ModelCallJob.ExecutionStatus.SUCCEEDED,
                        ModelCallJob.ConsumptionStatus.STALE, 3L)));
        LearningTask fallbackTask = task(CAFE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK, Optional.empty());
        when(learningTaskRepository.createOwned(eq(USER_ID), any(LearningTaskPlan.class)))
                .thenReturn(Optional.of(fallbackTask));
        PlanningRun fallbackRun = terminalRun(PlanningRun.Status.FALLBACK_APPLIED,
                Optional.of(PlanningRun.FallbackReason.WAIT_BUDGET_EXHAUSTED), TASK_ID, 1L);
        when(planningRunRepository.tryFinalizeOwned(
                RUN_ID, USER_ID, PROFILE_ID, PlanningRun.Status.FALLBACK_APPLIED,
                Optional.of(PlanningRun.FallbackReason.WAIT_BUDGET_EXHAUSTED), TASK_ID, 0L))
                .thenReturn(fallbackRun);

        PlannerEnrichmentFinalizationService.FinalizationResult result = service.finalizeRun(
                RUN_ID, REQUEST, new PlannerEnrichmentJobAwaiter.AwaitResult.BudgetExhausted(), USER);

        assertThat(result).isEqualTo(
                new PlannerEnrichmentFinalizationService.FinalizationResult.Created(fallbackTask, fallbackRun));
        // 迟到的成功结果不读取、不消费、不应用；先以 FALLBACK_APPLIED 将推进到的 workflow version
        // 标记 STALE，分类通过后才提交 fallback。
        verify(modelCallJobRepository, never()).findTextGenerationResultByJobIdAndUserId(any(), any());
        verify(modelCallJobRepository, never()).tryConsumeSucceededResult(any(), any(), anyLong(), anyLong());
        verify(modelCallJobRepository).tryMarkSucceededResultStale(JOB_ID, USER_ID, 1L, 2L);
        ArgumentCaptor<LearningTaskPlan> planCaptor =
                ArgumentCaptor.forClass(LearningTaskPlan.class);
        verify(learningTaskRepository).createOwned(eq(USER_ID), planCaptor.capture());
        assertThat(planCaptor.getValue().materialIdentity()).isEqualTo(CAFE);
    }

    @Test
    void budgetExhaustedFailsClosedWhenLateSucceededJobIsAlreadyConsumed() {
        stubPendingWorkflow();
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(plannerJob(
                        ModelCallJob.ExecutionStatus.SUCCEEDED,
                        ModelCallJob.ConsumptionStatus.CONSUMED, 3L)));

        assertThatThrownBy(() -> service.finalizeRun(
                RUN_ID, REQUEST, new PlannerEnrichmentJobAwaiter.AwaitResult.BudgetExhausted(), USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planning job is already consumed while its run is still pending");
        // CONSUMED + PENDING Run 在 fallback 提交前 fail closed：不创建 task、不 finalize。
        verify(learningTaskRepository, never()).createOwned(any(), any());
        verify(planningRunRepository, never())
                .tryFinalizeOwned(any(), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void budgetExhaustedFailsClosedWhenStaleMarkingHasNoDurableTransition() {
        ModelCallJob lateSucceeded = plannerJob(
                ModelCallJob.ExecutionStatus.SUCCEEDED, ModelCallJob.ConsumptionStatus.NOT_READY, 2L);
        stubPendingWorkflow();
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(lateSucceeded));
        when(modelCallJobRepository.tryMarkSucceededResultStale(JOB_ID, USER_ID, 1L, 2L))
                .thenReturn(Optional.empty());
        when(modelCallJobRepository.tryExpireSucceededResult(JOB_ID, USER_ID, 2L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.finalizeRun(
                RUN_ID, REQUEST, new PlannerEnrichmentJobAwaiter.AwaitResult.BudgetExhausted(), USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planning result transition failed without expiry or a durable state change");
        // 重读后仍 NOT_READY、同 row version，且 expiry 也无法解释：未发生任何 durable transition，
        // 整体回滚，不提交 fallback。
        verify(modelCallJobRepository).tryExpireSucceededResult(JOB_ID, USER_ID, 2L);
        verify(learningTaskRepository, never()).createOwned(any(), any());
        verify(planningRunRepository, never())
                .tryFinalizeOwned(any(), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void budgetExhaustedFailsClosedWhenStaleMarkingRereadDriftsOrShowsConsumed() {
        ModelCallJob lateSucceeded = plannerJob(
                ModelCallJob.ExecutionStatus.SUCCEEDED, ModelCallJob.ConsumptionStatus.NOT_READY, 2L);

        // 重读后 row version 漂移但 consumption 未变：无法解释的 CAS 失败。
        stubPendingWorkflow();
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(lateSucceeded))
                .thenReturn(Optional.of(plannerJob(
                        ModelCallJob.ExecutionStatus.SUCCEEDED,
                        ModelCallJob.ConsumptionStatus.NOT_READY, 3L)));
        when(modelCallJobRepository.tryMarkSucceededResultStale(JOB_ID, USER_ID, 1L, 2L))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.finalizeRun(
                RUN_ID, REQUEST, new PlannerEnrichmentJobAwaiter.AwaitResult.BudgetExhausted(), USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planning job row version changed without a consumption transition");

        // 重读后已 CONSUMED：跨边界不变量损坏。
        stubPendingWorkflow();
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(lateSucceeded))
                .thenReturn(Optional.of(plannerJob(
                        ModelCallJob.ExecutionStatus.SUCCEEDED,
                        ModelCallJob.ConsumptionStatus.CONSUMED, 3L)));
        assertThatThrownBy(() -> service.finalizeRun(
                RUN_ID, REQUEST, new PlannerEnrichmentJobAwaiter.AwaitResult.BudgetExhausted(), USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planning job was consumed without a matching planning outcome");

        verify(learningTaskRepository, never()).createOwned(any(), any());
    }

    @Test
    void budgetExhaustedStaleMarkingFailureWithDepletedRereadStillFallsBack() {
        ModelCallJob lateSucceeded = plannerJob(
                ModelCallJob.ExecutionStatus.SUCCEEDED, ModelCallJob.ConsumptionStatus.NOT_READY, 2L);
        stubPendingWorkflow();
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(lateSucceeded))
                .thenReturn(Optional.of(plannerJob(
                        ModelCallJob.ExecutionStatus.SUCCEEDED,
                        ModelCallJob.ConsumptionStatus.EXPIRED, 2L)));
        when(modelCallJobRepository.tryMarkSucceededResultStale(JOB_ID, USER_ID, 1L, 2L))
                .thenReturn(Optional.empty());
        stubFallbackCreation(PlanningRun.FallbackReason.WAIT_BUDGET_EXHAUSTED);

        PlannerEnrichmentFinalizationService.FinalizationResult result = service.finalizeRun(
                RUN_ID, REQUEST, new PlannerEnrichmentJobAwaiter.AwaitResult.BudgetExhausted(), USER);

        // STALE CAS 失败但重读显示已被他人转为 depleted：允许提交 fallback，且不再触碰 Job。
        assertThat(result).isInstanceOf(PlannerEnrichmentFinalizationService.FinalizationResult.Created.class);
        verify(modelCallJobRepository, never()).tryExpireSucceededResult(any(), any(), anyLong());
        verify(modelCallJobRepository, never()).tryConsumeSucceededResult(any(), any(), anyLong(), anyLong());
    }

    @Test
    void budgetExhaustedLeavesNonSucceededOrUnconvertibleJobsUntouched() {
        // 仍在 RUNNING 的 Job：fallback 但不做 STALE 记账。
        ModelCallJob running = plannerJob(
                ModelCallJob.ExecutionStatus.RUNNING, ModelCallJob.ConsumptionStatus.NOT_READY, 1L);
        stubPendingWorkflow();
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(running));
        stubFallbackCreation(PlanningRun.FallbackReason.WAIT_BUDGET_EXHAUSTED);

        PlannerEnrichmentFinalizationService.FinalizationResult runningResult = service.finalizeRun(
                RUN_ID, REQUEST, new PlannerEnrichmentJobAwaiter.AwaitResult.BudgetExhausted(), USER);

        assertThat(runningResult).isInstanceOf(
                PlannerEnrichmentFinalizationService.FinalizationResult.Created.class);
        verify(modelCallJobRepository, never()).tryMarkSucceededResultStale(any(), any(), anyLong(), anyLong());

        // 已 EXPIRED 的成功结果：不可转换，同样不做 STALE 记账。
        ModelCallJob expired = plannerJob(
                ModelCallJob.ExecutionStatus.SUCCEEDED, ModelCallJob.ConsumptionStatus.EXPIRED, 2L);
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(expired));

        PlannerEnrichmentFinalizationService.FinalizationResult expiredResult = service.finalizeRun(
                RUN_ID, REQUEST, new PlannerEnrichmentJobAwaiter.AwaitResult.BudgetExhausted(), USER);

        assertThat(expiredResult).isInstanceOf(
                PlannerEnrichmentFinalizationService.FinalizationResult.Created.class);
        verify(modelCallJobRepository, never()).tryMarkSucceededResultStale(any(), any(), anyLong(), anyLong());
    }

    @Test
    void returnsUnavailableWithoutTouchingJobWhenSnapshotCannotBeReResolved() {
        stubPendingWorkflow();
        catalog.deprecate(GREETING);

        PlannerEnrichmentFinalizationService.FinalizationResult result = service.finalizeRun(
                RUN_ID, REQUEST, new PlannerEnrichmentJobAwaiter.AwaitResult.BudgetExhausted(), USER);

        assertThat(result).isEqualTo(new PlannerEnrichmentFinalizationService.FinalizationResult.Unavailable(
                PlanningResult.UnavailableReason.SELECTED_MATERIAL_UNAVAILABLE));
        verify(modelCallJobRepository, never()).findByIdAndUserId(any(), any());
        verify(learningTaskRepository, never()).createOwned(any(), any());
        verify(planningRunRepository, never())
                .tryFinalizeOwned(any(), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void failsClosedWhenTaskCreationGateRejectsThePlan() {
        ModelCallJob succeeded = plannerJob(
                ModelCallJob.ExecutionStatus.SUCCEEDED, ModelCallJob.ConsumptionStatus.NOT_READY, 2L);
        stubPendingWorkflow();
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(succeeded));
        when(modelCallJobRepository.findTextGenerationResultByJobIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(resultResponse(VALID_ENRICHMENT_JSON)));
        when(modelCallJobRepository.tryConsumeSucceededResult(JOB_ID, USER_ID, 0L, 2L))
                .thenReturn(Optional.of(plannerJob(
                        ModelCallJob.ExecutionStatus.SUCCEEDED,
                        ModelCallJob.ConsumptionStatus.CONSUMED, 3L)));
        when(learningTaskRepository.createOwned(eq(USER_ID), any(LearningTaskPlan.class)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.finalizeRun(RUN_ID, REQUEST, terminalAwait(succeeded), USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("model enriched learning task was not created");
        verify(planningRunRepository, never())
                .tryFinalizeOwned(any(), any(), any(), any(), any(), any(), anyLong());
    }

    private void stubPendingWorkflow() {
        when(planningRunRepository.findOwnedForUpdate(RUN_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(pendingRun()));
        when(planningRunRepository.findOwnedSnapshot(RUN_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(SNAPSHOT));
    }

    private void stubSuccessfulResultAndFailedConsumption() {
        when(modelCallJobRepository.findTextGenerationResultByJobIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(resultResponse(VALID_ENRICHMENT_JSON)));
        when(modelCallJobRepository.tryConsumeSucceededResult(JOB_ID, USER_ID, 0L, 2L))
                .thenReturn(Optional.empty());
    }

    private void stubFallbackCreation(PlanningRun.FallbackReason reason) {
        LearningTask fallbackTask = task(CAFE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK, Optional.empty());
        when(learningTaskRepository.createOwned(eq(USER_ID), any(LearningTaskPlan.class)))
                .thenReturn(Optional.of(fallbackTask));
        when(planningRunRepository.tryFinalizeOwned(
                RUN_ID, USER_ID, PROFILE_ID, PlanningRun.Status.FALLBACK_APPLIED,
                Optional.of(reason), TASK_ID, 0L))
                .thenReturn(terminalRun(PlanningRun.Status.FALLBACK_APPLIED,
                        Optional.of(reason), TASK_ID, 1L));
    }

    private static PlannerEnrichmentJobAwaiter.AwaitResult.Terminal terminalAwait(ModelCallJob job) {
        return new PlannerEnrichmentJobAwaiter.AwaitResult.Terminal(job);
    }

    private static PlanningRun pendingRun() {
        return new PlanningRun(
                RUN_ID, USER_ID, PROFILE_ID, JOB_ID, PlanningRun.Status.PENDING,
                0L, 0L, CREATED_AT, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static PlanningRun terminalRun(
            PlanningRun.Status status,
            Optional<PlanningRun.FallbackReason> fallbackReason,
            UUID learningTaskId,
            long workflowVersion) {
        return new PlanningRun(
                RUN_ID, USER_ID, PROFILE_ID, JOB_ID, status,
                workflowVersion, 1L, CREATED_AT, Optional.of(COMPLETED_AT),
                Optional.of(learningTaskId), fallbackReason);
    }

    private static ModelCallJob plannerJob(
            ModelCallJob.ExecutionStatus executionStatus,
            ModelCallJob.ConsumptionStatus consumptionStatus,
            long rowVersion) {
        return new ModelCallJob(
                JOB_ID,
                USER_ID,
                Optional.of(PROFILE_ID),
                ModelPurpose.PLANNING,
                ModelOperation.TEXT_GENERATION,
                Optional.empty(),
                Optional.empty(),
                RUN_ID,
                PlanningRun.WORKFLOW_STEP_ID,
                PlanningRun.CURRENT_WORKFLOW_VERSION,
                executionStatus,
                consumptionStatus,
                failureFor(executionStatus),
                rowVersion,
                CREATED_AT,
                executionStatus.isTerminal() ? Optional.of(COMPLETED_AT) : Optional.empty(),
                EXPIRES_AT);
    }

    private static Optional<ModelFailure> failureFor(ModelCallJob.ExecutionStatus executionStatus) {
        return switch (executionStatus) {
            case FAILED -> Optional.of(ModelFailure.withoutRoute(ModelFailureKind.PROVIDER_FAILURE));
            case TIMED_OUT -> Optional.of(ModelFailure.withoutRoute(ModelFailureKind.TIMEOUT));
            default -> Optional.empty();
        };
    }

    private static TextGenerationResponse resultResponse(String generatedJson) {
        return new TextGenerationResponse(
                new ProviderId("deepseek"),
                new ModelId("deepseek-chat"),
                generatedJson,
                TextGenerationResponse.FinishReason.COMPLETED,
                Optional.empty());
    }

    private static LearningTask task(
            MaterialIdentity identity,
            LearningTaskPlan.PlanningReason planningReason,
            Optional<String> recommendationReason) {
        return new LearningTask(
                TASK_ID,
                USER_ID,
                PROFILE_ID,
                identity,
                "en",
                "zh-cn",
                MaterialDifficulty.FOUNDATION,
                7,
                "SCENARIO",
                "Goal for SCENARIO",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                planningReason,
                recommendationReason,
                LearningTask.Status.PLANNED,
                CREATED_AT,
                Optional.empty(),
                Optional.empty());
    }

    private static PublishedLearningMaterial material(MaterialIdentity identity, String scenario) {
        return new PublishedLearningMaterial(
                identity,
                new TargetPracticeCore(
                        "en",
                        MaterialDifficulty.FOUNDATION,
                        scenario,
                        "Goal for " + scenario,
                        "Target language text",
                        null,
                        List.of(),
                        "rubric/v1"),
                List.of(scaffold()),
                new MaterialSourceLineage("PROJECT_ORIGINAL", "1", "AGPL-3.0", "sha256:test"));
    }

    private static SupportScaffold scaffold() {
        return new SupportScaffold("zh-cn", "instruction", "explanation", "hint", "note", List.of());
    }

    /** 只服务 findByIdentity 的最小 catalog；listAvailable 不应被 finalization 调用。 */
    private static final class FakeMaterialCatalog implements LearningMaterialCatalog {

        private final Map<MaterialIdentity, MaterialQueryResult> results = new HashMap<>();

        private FakeMaterialCatalog() {
            put(CAFE, "CAFE_SIMPLE_REQUEST");
            put(GREETING, "GREETING_INTRODUCTION");
        }

        private void put(MaterialIdentity identity, String scenario) {
            results.put(identity, new MaterialQueryResult.Available(
                    material(identity, scenario), scaffold()));
        }

        private void deprecate(MaterialIdentity identity) {
            results.put(identity, new MaterialQueryResult.Unavailable(
                    MaterialUnavailableReason.MATERIAL_NOT_PUBLISHED));
        }

        @Override
        public MaterialQueryResult findByIdentity(MaterialIdentity identity, String supportLanguage) {
            return results.getOrDefault(identity, new MaterialQueryResult.Unavailable(
                    MaterialUnavailableReason.MATERIAL_NOT_PUBLISHED));
        }

        @Override
        public List<AvailableMaterialSummary> listAvailable(
                String targetLanguage, String supportLanguage) {
            throw new UnsupportedOperationException("listAvailable must not be used for finalization");
        }
    }
}
