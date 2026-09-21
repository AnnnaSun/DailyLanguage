package com.dailylanguage.planner.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

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
import com.dailylanguage.languageprofile.application.LanguageProfileAccessService;
import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.modelcalljob.application.TextGenerationJobSubmission.SubmissionOutcome;
import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.result.ModelFailure;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ModelRouteKey;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.execution.FixedTextGenerationRoutes;
import com.dailylanguage.modelgateway.text.execution.TextGenerationProviderAdapter;
import com.dailylanguage.modelgateway.text.execution.TextGenerationRoute;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.Created;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.InvalidProviderCredential;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.InvalidProviderId;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.InvalidRequest;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.LanguageProfileNotFound;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.ProviderMismatch;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.Unavailable;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.planner.domain.PlanningCandidateSet;
import com.dailylanguage.planner.domain.PlanningRequest;
import com.dailylanguage.planner.domain.PlanningResult;
import com.dailylanguage.planner.domain.PlanningRun;
import com.dailylanguage.planner.infrastructure.LearningTaskRepository;
import com.dailylanguage.security.domain.UserContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LearningTaskPlanningServiceTests {

    private static final UUID USER_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000011");
    private static final UserContext USER_CONTEXT = new UserContext(USER_ID);
    private static final UUID PROFILE_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000012");
    private static final LanguageProfileIdentity ENGLISH_PROFILE =
            new LanguageProfileIdentity(PROFILE_ID, USER_ID, "en");
    private static final UUID RUN_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000013");
    private static final UUID JOB_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000014");
    private static final UUID TASK_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000015");
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-15T08:00:00.123Z");
    private static final OffsetDateTime COMPLETED_AT = OffsetDateTime.parse("2026-09-15T08:00:12.456Z");
    private static final OffsetDateTime EXPIRES_AT = OffsetDateTime.parse("2026-09-15T08:05:00.123Z");
    private static final MaterialIdentity CAFE = new MaterialIdentity("en-builtin-cafe-request", "v1");
    private static final MaterialIdentity GREETING =
            new MaterialIdentity("en-builtin-greeting-intro", "v1");
    private static final String ROUTE_PROVIDER = "deepseek";
    private static final String ENRICHED_REASON = "今天在咖啡馆练习点单，并追问今日特供。";

    private final LanguageProfileAccessService languageProfileAccessService =
            Mockito.mock(LanguageProfileAccessService.class);
    private final PlannerEnrichmentDispatchService dispatchService =
            Mockito.mock(PlannerEnrichmentDispatchService.class);
    private final PlannerEnrichmentJobAwaiter awaiter = Mockito.mock(PlannerEnrichmentJobAwaiter.class);
    private final PlannerEnrichmentFinalizationService finalizationService =
            Mockito.mock(PlannerEnrichmentFinalizationService.class);
    private final LearningTaskRepository learningTaskRepository =
            Mockito.mock(LearningTaskRepository.class);
    private final FakeMaterialCatalog catalog = new FakeMaterialCatalog();

    private final LearningTaskPlanningService service = new LearningTaskPlanningService(
            languageProfileAccessService,
            new EligibleLearningTaskCandidateReader(catalog),
            configuredRoutes(ROUTE_PROVIDER),
            dispatchService,
            awaiter,
            finalizationService,
            learningTaskRepository);

    @BeforeEach
    void ownedProfileIsVisibleToTheAuthenticatedUser() {
        when(languageProfileAccessService.findProfileOwnedByUser(PROFILE_ID, USER_CONTEXT))
                .thenReturn(Optional.of(ENGLISH_PROFILE));
    }

    @Test
    void createsDurableDeterministicTaskWithoutEnrichmentParameters() {
        LearningTask durableTask = deterministicTask();
        when(learningTaskRepository.createOwned(eq(USER_ID), any(LearningTaskPlan.class)))
                .thenReturn(Optional.of(durableTask));

        LearningTaskPlanningResult result = service.plan(
                PROFILE_ID, USER_CONTEXT, command(" ZH-CN ", "FOUNDATION", 30, null, null));

        assertThat(result).isEqualTo(new Created(durableTask));
        ArgumentCaptor<LearningTaskPlan> planCaptor = ArgumentCaptor.forClass(LearningTaskPlan.class);
        verify(learningTaskRepository).createOwned(eq(USER_ID), planCaptor.capture());
        LearningTaskPlan persistedPlan = planCaptor.getValue();
        // 无 enrichment 参数时 index 0 是唯一 deterministic fallback；support language 已规范化。
        assertThat(persistedPlan.materialIdentity()).isEqualTo(CAFE);
        assertThat(persistedPlan.supportLanguage()).isEqualTo("zh-cn");
        assertThat(persistedPlan.estimatedDurationMinutes()).isEqualTo(10);
        assertThat(persistedPlan.reason())
                .isEqualTo(LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK);
        assertThat(persistedPlan.recommendationReason()).isEmpty();
        assertThat(catalog.requestedSupportLanguage).isEqualTo("zh-cn");
        // deterministic 路径不触碰 route / dispatch / wait / finalization。
        verifyNoInteractions(dispatchService, awaiter, finalizationService);
        verify(languageProfileAccessService).findProfileOwnedByUser(PROFILE_ID, USER_CONTEXT);
    }

    @Test
    void rejectsProfileNotAccessibleToTheCallerBeforeCandidateRead() {
        when(languageProfileAccessService.findProfileOwnedByUser(PROFILE_ID, USER_CONTEXT))
                .thenReturn(Optional.empty());

        assertThat(service.plan(PROFILE_ID, USER_CONTEXT, command("zh-cn", "FOUNDATION", 10, null, null)))
                .isEqualTo(new LanguageProfileNotFound());

        assertThat(catalog.listCalls).isZero();
        verifyNoInteractions(learningTaskRepository, dispatchService, awaiter, finalizationService);
    }

    @ParameterizedTest
    @EnumSource(PlanningResult.UnavailableReason.class)
    void candidateUnavailableNeverPersists(PlanningResult.UnavailableReason reason) {
        if (reason == PlanningResult.UnavailableReason.AVAILABLE_TIME_TOO_SHORT) {
            assertThat(service.plan(
                    PROFILE_ID, USER_CONTEXT, command("zh-cn", "FOUNDATION", 4, null, null)))
                    .isEqualTo(new Unavailable(reason));
            assertThat(catalog.listCalls).isZero();
        } else if (reason == PlanningResult.UnavailableReason.NO_ELIGIBLE_MATERIAL) {
            LearningTaskPlanningService emptyCatalogService =
                    serviceWithCatalog(new FakeMaterialCatalog(false));
            assertThat(emptyCatalogService.plan(
                    PROFILE_ID, USER_CONTEXT, command("zh-cn", "FOUNDATION", 10, null, null)))
                    .isEqualTo(new Unavailable(reason));
        } else {
            FakeMaterialCatalog driftCatalog = new FakeMaterialCatalog();
            driftCatalog.deprecate(GREETING);
            assertThat(serviceWithCatalog(driftCatalog).plan(
                    PROFILE_ID, USER_CONTEXT, command("zh-cn", "FOUNDATION", 10, null, null)))
                    .isEqualTo(new Unavailable(reason));
        }

        verify(learningTaskRepository, never()).createOwned(any(UUID.class), any(LearningTaskPlan.class));
        verifyNoInteractions(dispatchService, awaiter, finalizationService);
    }

    @Test
    void failsClosedWhenDurableCreateGateRejectsThePlan() {
        when(learningTaskRepository.createOwned(eq(USER_ID), any(LearningTaskPlan.class)))
                .thenReturn(Optional.empty());

        assertThat(service.plan(PROFILE_ID, USER_CONTEXT, command("zh-cn", "FOUNDATION", 10, null, null)))
                .isEqualTo(new LanguageProfileNotFound());
    }

    @ParameterizedTest
    @MethodSource("invalidProviderPairs")
    void invalidProviderPairIsRejectedBeforeProfileLookup(
            String providerId, String credentialSecret, Object expectedResult) {
        assertThat(service.plan(
                PROFILE_ID, USER_CONTEXT, command("zh-cn", "FOUNDATION", 10, providerId, credentialSecret)))
                .isEqualTo(expectedResult);

        // pair 非法在读取 Profile 与 Catalog 前裁决：零数据库交互、零 Provider 调用。
        verifyNoInteractions(languageProfileAccessService, learningTaskRepository, dispatchService);
        assertThat(catalog.listCalls).isZero();
    }

    static Stream<Object[]> invalidProviderPairs() {
        return Stream.of(
                // blank 值是"出现"而非"缺失"：任一 blank 单独或成对出现都不得绕过 pair 校验。
                new Object[]{"", null, new InvalidProviderId()},
                new Object[]{"", "", new InvalidProviderId()},
                new Object[]{" ", null, new InvalidProviderId()},
                new Object[]{null, "", new InvalidProviderId()},
                new Object[]{null, " ", new InvalidProviderId()},
                new Object[]{null, "secret", new InvalidProviderId()},
                new Object[]{" deepseek", "secret", new InvalidProviderId()},
                new Object[]{"deepseek ", "secret", new InvalidProviderId()},
                new Object[]{"deepseek", null, new InvalidProviderCredential()},
                new Object[]{"deepseek", "", new InvalidProviderCredential()},
                new Object[]{"deepseek", " ", new InvalidProviderCredential()},
                new Object[]{"deepseek", "\t", new InvalidProviderCredential()});
    }

    @Test
    void providerMismatchFailsClosedWithZeroProviderCallsAndZeroTasks() {
        assertThat(service.plan(
                PROFILE_ID, USER_CONTEXT, command("zh-cn", "FOUNDATION", 10, "openai", "secret")))
                .isEqualTo(new ProviderMismatch());

        // requested provider 与 fixed PLANNING route 不一致：candidate 已读取，但零 Provider 调用、零 task。
        assertThat(catalog.listCalls).isEqualTo(1);
        verifyNoInteractions(dispatchService, awaiter, finalizationService, learningTaskRepository);
    }

    @Test
    void missingPlanningRouteFallsBackToDeterministicTask() {
        LearningTaskPlanningService routelessService = new LearningTaskPlanningService(
                languageProfileAccessService,
                new EligibleLearningTaskCandidateReader(catalog),
                new FixedTextGenerationRoutes(Map.of()),
                dispatchService,
                awaiter,
                finalizationService,
                learningTaskRepository);
        LearningTask durableTask = deterministicTask();
        when(learningTaskRepository.createOwned(eq(USER_ID), any(LearningTaskPlan.class)))
                .thenReturn(Optional.of(durableTask));

        LearningTaskPlanningResult result = routelessService.plan(
                PROFILE_ID, USER_CONTEXT, command("zh-cn", "FOUNDATION", 10, ROUTE_PROVIDER, "secret"));

        // fixed PLANNING route 未配置：enrichment 不可用，不创建 Run / Job，落 candidate index 0。
        assertThat(result).isEqualTo(new Created(durableTask));
        verify(learningTaskRepository).createOwned(eq(USER_ID), any(LearningTaskPlan.class));
        verifyNoInteractions(dispatchService, awaiter, finalizationService);
    }

    @Test
    void dispatchRouteOrPromptUnavailableFallsBackWithoutRunOrJob() {
        for (PlannerEnrichmentTextRequestFactory.UnavailabilityReason reason : List.of(
                PlannerEnrichmentTextRequestFactory.UnavailabilityReason.INVALID_CANDIDATE_SET,
                PlannerEnrichmentTextRequestFactory.UnavailabilityReason.PROMPT_UNAVAILABLE)) {
            when(dispatchService.dispatch(
                    any(PlanningRequest.class), any(PlanningCandidateSet.class), eq(USER_CONTEXT), any()))
                    .thenReturn(new PlannerEnrichmentDispatchService.DispatchResult.Unavailable(reason));
            LearningTask durableTask = deterministicTask();
            when(learningTaskRepository.createOwned(eq(USER_ID), any(LearningTaskPlan.class)))
                    .thenReturn(Optional.of(durableTask));

            LearningTaskPlanningResult result = service.plan(
                    PROFILE_ID, USER_CONTEXT, command("zh-cn", "FOUNDATION", 10, ROUTE_PROVIDER, "secret"));

            assertThat(result).isEqualTo(new Created(durableTask));
        }
        verifyNoInteractions(awaiter, finalizationService);
    }

    @Test
    void enrichesThroughDispatchAwaitAndAtomicFinalization() {
        PlanningRun pendingRun = pendingRun();
        when(dispatchService.dispatch(
                any(PlanningRequest.class), any(PlanningCandidateSet.class), eq(USER_CONTEXT), any()))
                .thenReturn(new PlannerEnrichmentDispatchService.DispatchResult.Created(
                        pendingRun, JOB_ID, SubmissionOutcome.ACCEPTED));
        ModelCallJob succeededJob = plannerJob(ModelCallJob.ExecutionStatus.SUCCEEDED,
                ModelCallJob.ConsumptionStatus.NOT_READY, 2L);
        PlannerEnrichmentJobAwaiter.AwaitResult.Terminal terminal =
                new PlannerEnrichmentJobAwaiter.AwaitResult.Terminal(succeededJob);
        when(awaiter.await(JOB_ID, USER_CONTEXT)).thenReturn(terminal);
        LearningTask enrichedTask = enrichedTask();
        when(finalizationService.finalizeRun(
                eq(RUN_ID), any(PlanningRequest.class), eq(terminal), eq(USER_CONTEXT)))
                .thenReturn(new PlannerEnrichmentFinalizationService.FinalizationResult.Created(
                        enrichedTask, modelAppliedRun()));

        LearningTaskPlanningResult result = service.plan(
                PROFILE_ID, USER_CONTEXT, command("zh-cn", "FOUNDATION", 10, ROUTE_PROVIDER, "secret"));

        assertThat(result).isEqualTo(new Created(enrichedTask));
        ArgumentCaptor<PlanningRequest> dispatchRequestCaptor =
                ArgumentCaptor.forClass(PlanningRequest.class);
        ArgumentCaptor<TransientProviderCredential> credentialCaptor =
                ArgumentCaptor.forClass(TransientProviderCredential.class);
        // Credential 绑定 fixed route 的 provider，只在当前调用链内传播。
        verify(dispatchService).dispatch(dispatchRequestCaptor.capture(),
                any(PlanningCandidateSet.class), eq(USER_CONTEXT), credentialCaptor.capture());
        assertThat(credentialCaptor.getValue().providerId()).isEqualTo(new ProviderId(ROUTE_PROVIDER));
        assertThat(credentialCaptor.getValue().secret()).isEqualTo("secret");
        verify(awaiter).await(JOB_ID, USER_CONTEXT);
        ArgumentCaptor<PlanningRequest> finalizeRequestCaptor =
                ArgumentCaptor.forClass(PlanningRequest.class);
        verify(finalizationService).finalizeRun(
                eq(RUN_ID), finalizeRequestCaptor.capture(), eq(terminal), eq(USER_CONTEXT));
        // dispatch 与 finalization 必须共享同一个 PlanningRequest 实例：snapshot re-resolution
        // 的 binding 不允许在两段之间被重建。
        assertThat(finalizeRequestCaptor.getValue()).isSameAs(dispatchRequestCaptor.getValue());
    }

    @Test
    void capacityRejectionStillFinalizesThroughTheDurableChain() {
        when(dispatchService.dispatch(
                any(PlanningRequest.class), any(PlanningCandidateSet.class), eq(USER_CONTEXT), any()))
                .thenReturn(new PlannerEnrichmentDispatchService.DispatchResult.Created(
                        pendingRun(), JOB_ID, SubmissionOutcome.CAPACITY_UNAVAILABLE));
        ModelCallJob rejectedJob = plannerJob(ModelCallJob.ExecutionStatus.SUBMISSION_REJECTED,
                ModelCallJob.ConsumptionStatus.NOT_READY, 1L);
        PlannerEnrichmentJobAwaiter.AwaitResult.Terminal terminal =
                new PlannerEnrichmentJobAwaiter.AwaitResult.Terminal(rejectedJob);
        when(awaiter.await(JOB_ID, USER_CONTEXT)).thenReturn(terminal);
        LearningTask fallbackTask = deterministicTask();
        when(finalizationService.finalizeRun(
                eq(RUN_ID), any(PlanningRequest.class), eq(terminal), eq(USER_CONTEXT)))
                .thenReturn(new PlannerEnrichmentFinalizationService.FinalizationResult.Created(
                        fallbackTask, fallbackAppliedRun()));

        // capacity rejection 由 durable SUBMISSION_REJECTED job 承载：service 不按 submission
        // outcome 分支，统一交给 await + finalizer 裁决为 fallback task。
        assertThat(service.plan(
                PROFILE_ID, USER_CONTEXT, command("zh-cn", "FOUNDATION", 10, ROUTE_PROVIDER, "secret")))
                .isEqualTo(new Created(fallbackTask));
    }

    @Test
    void finalizerExistingOutcomeReplaysTheBoundTask() {
        stubDispatchedRun();
        LearningTask boundTask = enrichedTask();
        when(finalizationService.finalizeRun(
                eq(RUN_ID), any(PlanningRequest.class), any(), eq(USER_CONTEXT)))
                .thenReturn(new PlannerEnrichmentFinalizationService.FinalizationResult.Existing(
                        boundTask, modelAppliedRun()));

        assertThat(service.plan(
                PROFILE_ID, USER_CONTEXT, command("zh-cn", "FOUNDATION", 10, ROUTE_PROVIDER, "secret")))
                .isEqualTo(new Created(boundTask));
        // terminal replay 不重复创建 task。
        verify(learningTaskRepository, never()).createOwned(any(UUID.class), any(LearningTaskPlan.class));
    }

    @Test
    void finalizerNotFoundFailsClosed() {
        stubDispatchedRun();
        when(finalizationService.finalizeRun(
                eq(RUN_ID), any(PlanningRequest.class), any(), eq(USER_CONTEXT)))
                .thenReturn(new PlannerEnrichmentFinalizationService.FinalizationResult.NotFound());

        assertThatThrownBy(() -> service.plan(
                PROFILE_ID, USER_CONTEXT, command("zh-cn", "FOUNDATION", 10, ROUTE_PROVIDER, "secret")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("dispatched planning run cannot be found");
    }

    @Test
    void finalizerUnavailableMapsToTypedUnavailable() {
        stubDispatchedRun();
        when(finalizationService.finalizeRun(
                eq(RUN_ID), any(PlanningRequest.class), any(), eq(USER_CONTEXT)))
                .thenReturn(new PlannerEnrichmentFinalizationService.FinalizationResult.Unavailable(
                        PlanningResult.UnavailableReason.SELECTED_MATERIAL_UNAVAILABLE));

        assertThat(service.plan(
                PROFILE_ID, USER_CONTEXT, command("zh-cn", "FOUNDATION", 10, ROUTE_PROVIDER, "secret")))
                .isEqualTo(new Unavailable(PlanningResult.UnavailableReason.SELECTED_MATERIAL_UNAVAILABLE));
    }

    @ParameterizedTest
    @MethodSource("invalidCommands")
    void invalidRequestIsRejectedBeforeProfileLookup(
            LearningTaskPlanningService.PlanningCommand invalidCommand) {
        assertThat(service.plan(PROFILE_ID, USER_CONTEXT, invalidCommand))
                .isEqualTo(new InvalidRequest());

        verifyNoInteractions(languageProfileAccessService, learningTaskRepository, dispatchService);
        assertThat(catalog.listCalls).isZero();
    }

    static Stream<LearningTaskPlanningService.PlanningCommand> invalidCommands() {
        return Stream.of(
                command(null, "FOUNDATION", 10, null, null),
                command(" ", "FOUNDATION", 10, null, null),
                command("not a tag", "FOUNDATION", 10, null, null),
                command("z".repeat(36), "FOUNDATION", 10, null, null),
                command("zh-cn", null, 10, null, null),
                command("zh-cn", "INTERMEDIATE", 10, null, null),
                command("zh-cn", "FOUNDATION", null, null, null),
                command("zh-cn", "FOUNDATION", 0, null, null),
                command("zh-cn", "FOUNDATION", -3, null, null));
    }

    @Test
    void rejectsMissingMandatoryArguments() {
        assertThatThrownBy(() -> service.plan(null, USER_CONTEXT, command("zh-cn", "FOUNDATION", 10, null, null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("languageProfileId must not be null");
        assertThatThrownBy(() -> service.plan(PROFILE_ID, null, command("zh-cn", "FOUNDATION", 10, null, null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("userContext must not be null");
        assertThatThrownBy(() -> service.plan(PROFILE_ID, USER_CONTEXT, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("command must not be null");
    }

    private LearningTaskPlanningService serviceWithCatalog(FakeMaterialCatalog catalog) {
        return new LearningTaskPlanningService(
                languageProfileAccessService,
                new EligibleLearningTaskCandidateReader(catalog),
                configuredRoutes(ROUTE_PROVIDER),
                dispatchService,
                awaiter,
                finalizationService,
                learningTaskRepository);
    }

    private void stubDispatchedRun() {
        when(dispatchService.dispatch(
                any(PlanningRequest.class), any(PlanningCandidateSet.class), eq(USER_CONTEXT), any()))
                .thenReturn(new PlannerEnrichmentDispatchService.DispatchResult.Created(
                        pendingRun(), JOB_ID, SubmissionOutcome.ACCEPTED));
        when(awaiter.await(JOB_ID, USER_CONTEXT)).thenReturn(
                new PlannerEnrichmentJobAwaiter.AwaitResult.Terminal(
                        plannerJob(ModelCallJob.ExecutionStatus.SUCCEEDED,
                                ModelCallJob.ConsumptionStatus.NOT_READY, 2L)));
    }

    private static LearningTaskPlanningService.PlanningCommand command(
            String supportLanguage,
            String requestedDifficulty,
            Integer availableMinutes,
            String providerId,
            String providerCredentialSecret) {
        return new LearningTaskPlanningService.PlanningCommand(
                supportLanguage, requestedDifficulty, availableMinutes, providerId, providerCredentialSecret);
    }

    private static FixedTextGenerationRoutes configuredRoutes(String providerValue) {
        TextGenerationRoute route = new TextGenerationRoute(
                new ProviderId(providerValue),
                new ModelId("deepseek-chat"),
                Mockito.mock(TextGenerationProviderAdapter.class),
                Duration.ofSeconds(30));
        return new FixedTextGenerationRoutes(Map.of(
                new ModelRouteKey(ModelPurpose.PLANNING, ModelOperation.TEXT_GENERATION), route));
    }

    private static PlanningRun pendingRun() {
        return new PlanningRun(
                RUN_ID, USER_ID, PROFILE_ID, JOB_ID, PlanningRun.Status.PENDING,
                0L, 0L, CREATED_AT, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static PlanningRun modelAppliedRun() {
        return new PlanningRun(
                RUN_ID, USER_ID, PROFILE_ID, JOB_ID, PlanningRun.Status.MODEL_APPLIED,
                0L, 1L, CREATED_AT, Optional.of(COMPLETED_AT), Optional.of(TASK_ID), Optional.empty());
    }

    private static PlanningRun fallbackAppliedRun() {
        return new PlanningRun(
                RUN_ID, USER_ID, PROFILE_ID, JOB_ID, PlanningRun.Status.FALLBACK_APPLIED,
                1L, 1L, CREATED_AT, Optional.of(COMPLETED_AT), Optional.of(TASK_ID),
                Optional.of(PlanningRun.FallbackReason.MODEL_CALL_FAILED));
    }

    private static ModelCallJob plannerJob(
            ModelCallJob.ExecutionStatus executionStatus,
            ModelCallJob.ConsumptionStatus consumptionStatus,
            long rowVersion) {
        Optional<ModelFailure> failure = switch (executionStatus) {
            case FAILED -> Optional.of(ModelFailure.withoutRoute(ModelFailureKind.PROVIDER_FAILURE));
            case TIMED_OUT -> Optional.of(ModelFailure.withoutRoute(ModelFailureKind.TIMEOUT));
            default -> Optional.empty();
        };
        return new ModelCallJob(
                JOB_ID, USER_ID, Optional.of(PROFILE_ID),
                ModelPurpose.PLANNING, ModelOperation.TEXT_GENERATION,
                Optional.empty(), Optional.empty(),
                RUN_ID, PlanningRun.WORKFLOW_STEP_ID, PlanningRun.CURRENT_WORKFLOW_VERSION,
                executionStatus, consumptionStatus, failure,
                rowVersion, CREATED_AT,
                executionStatus.isTerminal() ? Optional.of(COMPLETED_AT) : Optional.empty(),
                EXPIRES_AT);
    }

    private static LearningTask deterministicTask() {
        return task(CAFE, LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK, Optional.empty());
    }

    private static LearningTask enrichedTask() {
        return task(GREETING, LearningTaskPlan.PlanningReason.MODEL_ENRICHED, Optional.of(ENRICHED_REASON));
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
                10,
                "SCENARIO",
                "Goal for SCENARIO",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                planningReason,
                recommendationReason,
                LearningTask.Status.PLANNED,
                OffsetDateTime.parse("2026-09-15T08:00:00.123Z"),
                Optional.empty(),
                Optional.empty());
    }

    /** stable order 下 CAFE 是 index 0（唯一 deterministic fallback），GREETING 是 index 1。 */
    private static final class FakeMaterialCatalog implements LearningMaterialCatalog {

        private final Map<MaterialIdentity, MaterialQueryResult> results = new HashMap<>();
        private final boolean publishesMaterials;
        private int listCalls;
        private String requestedSupportLanguage;

        private FakeMaterialCatalog() {
            this(true);
        }

        private FakeMaterialCatalog(boolean publishesMaterials) {
            this.publishesMaterials = publishesMaterials;
            results.put(CAFE, available(CAFE));
            results.put(GREETING, available(GREETING));
        }

        private void deprecate(MaterialIdentity identity) {
            results.put(identity, new MaterialQueryResult.Unavailable(
                    MaterialUnavailableReason.MATERIAL_NOT_PUBLISHED));
        }

        private MaterialQueryResult available(MaterialIdentity identity) {
            return new MaterialQueryResult.Available(
                    new PublishedLearningMaterial(
                            identity,
                            new TargetPracticeCore(
                                    "en",
                                    MaterialDifficulty.FOUNDATION,
                                    "SCENARIO_" + identity.materialId(),
                                    "Goal for " + identity.materialId(),
                                    "Target language text",
                                    null,
                                    List.of(),
                                    "rubric/v1"),
                            List.of(new SupportScaffold("zh-cn", "i", "e", "h", "n", List.of())),
                            new MaterialSourceLineage("PROJECT_ORIGINAL", "1", "AGPL-3.0", "sha256:test")),
                    new SupportScaffold("zh-cn", "i", "e", "h", "n", List.of()));
        }

        @Override
        public MaterialQueryResult findByIdentity(MaterialIdentity identity, String supportLanguage) {
            return results.getOrDefault(identity, new MaterialQueryResult.Unavailable(
                    MaterialUnavailableReason.MATERIAL_NOT_PUBLISHED));
        }

        @Override
        public List<AvailableMaterialSummary> listAvailable(String targetLanguage, String supportLanguage) {
            listCalls++;
            requestedSupportLanguage = supportLanguage;
            if (!publishesMaterials) {
                return List.of();
            }
            return List.of(
                    new AvailableMaterialSummary(CAFE, "en", MaterialDifficulty.FOUNDATION,
                            "SCENARIO_CA", List.of("zh-cn")),
                    new AvailableMaterialSummary(GREETING, "en", MaterialDifficulty.FOUNDATION,
                            "SCENARIO_GR", List.of("zh-cn")));
        }
    }
}
