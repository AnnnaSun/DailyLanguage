package com.dailylanguage.planner.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.modelcalljob.application.TextGenerationJobDispatch;
import com.dailylanguage.modelcalljob.application.TextGenerationJobDispatch.DispatchCommand;
import com.dailylanguage.modelcalljob.application.TextGenerationJobSubmission.SubmissionOutcome;
import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextMessage;
import com.dailylanguage.modelgateway.text.TextOutputSpecification;
import com.dailylanguage.planner.config.PlannerEnrichmentProperties;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.planner.domain.PlanningCandidateSet;
import com.dailylanguage.planner.domain.PlanningRequest;
import com.dailylanguage.planner.domain.PlanningRun;
import com.dailylanguage.security.domain.UserContext;

class PlannerEnrichmentDispatchServiceTests {

    private static final UUID USER_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000091");
    private static final UUID PROFILE_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000092");
    private static final UUID RUN_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000093");
    private static final UUID JOB_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000094");
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-14T12:00:30.123Z");
    // 非默认值：证明 TTL 来自注入的 properties，而非硬编码。
    private static final Duration RESULT_TTL = Duration.ofMinutes(2);
    private static final OffsetDateTime RESULT_EXPIRES_AT = OffsetDateTime.parse("2026-09-21T12:00:30.123Z");
    private static final LanguageProfileIdentity PROFILE =
            new LanguageProfileIdentity(PROFILE_ID, USER_ID, "en");
    private static final PlanningRequest PLANNING_REQUEST = new PlanningRequest(
            PROFILE, "zh", MaterialDifficulty.FOUNDATION, 10, java.util.Set.of());
    private static final UserContext USER = new UserContext(USER_ID);
    private static final TransientProviderCredential CREDENTIAL =
            new TransientProviderCredential(new ProviderId("deepseek"), "dispatch-secret");
    // PlanningCandidateSet 是 identity 语义（无 equals），stub/verify 必须共享同一实例。
    private static final PlanningCandidateSet CANDIDATE_SET = new PlanningCandidateSet(
            List.of(deterministicPlan(0), deterministicPlan(1)));
    private static final TextGenerationRequest REQUEST = new TextGenerationRequest(
            ModelPurpose.PLANNING,
            List.of(new TextMessage(TextMessage.Role.USER, "{\"candidates\":[]}")),
            TextOutputSpecification.jsonObject());

    private final PlannerEnrichmentTextRequestFactory requestFactory =
            mock(PlannerEnrichmentTextRequestFactory.class);
    private final PlanningRunCreationService runCreationService =
            mock(PlanningRunCreationService.class);
    private final TextGenerationJobDispatch jobDispatch = mock(TextGenerationJobDispatch.class);
    private final PlannerEnrichmentDispatchService service = new PlannerEnrichmentDispatchService(
            requestFactory, runCreationService, jobDispatch,
            new PlannerEnrichmentProperties(RESULT_TTL));

    @Test
    void nullArgumentsAreProgrammingErrors() {
        assertThatThrownBy(() -> service.dispatch(
                null, CANDIDATE_SET, USER, CREDENTIAL))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("request must not be null");
        assertThatThrownBy(() -> service.dispatch(
                PLANNING_REQUEST, null, USER, CREDENTIAL))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("candidateSet must not be null");
        assertThatThrownBy(() -> service.dispatch(
                PLANNING_REQUEST, CANDIDATE_SET, null, CREDENTIAL))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("userContext must not be null");
        assertThatThrownBy(() -> service.dispatch(
                PLANNING_REQUEST, CANDIDATE_SET, USER, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("credential must not be null");
    }

    @Test
    void usesNeverTransaction() throws NoSuchMethodException {
        Transactional transactional = PlannerEnrichmentDispatchService.class
                .getMethod("dispatch",
                        PlanningRequest.class, PlanningCandidateSet.class,
                        UserContext.class, TransientProviderCredential.class)
                .getAnnotation(Transactional.class);

        // S9C2 的 REQUIRES_NEW 返回后 Run / Job / snapshot 已提交；外层禁止事务，
        // dispatch 不会落进未提交窗口。
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.NEVER);
    }

    @Test
    void doesNotDependOnProviderExecutionBoundaries() {
        // S9D 只经 TextGenerationJobDispatch / Job boundary 提交；结构上禁止直接依赖
        // Gateway 的 route / port / provider adapter 实现。
        List<String> forbiddenTypes = Arrays.stream(
                        PlannerEnrichmentDispatchService.class.getDeclaredFields())
                .map(Field::getType)
                .filter(type -> type.getPackageName()
                        .startsWith("com.dailylanguage.modelgateway.text.execution")
                        || type.getPackageName()
                        .startsWith("com.dailylanguage.modelgateway.text.openaicompatible"))
                .map(Class::getName)
                .toList();

        assertThat(forbiddenTypes).isEmpty();
    }

    @Test
    void rejectsMismatchedUserBeforeAnyInteraction() {
        UserContext otherUser = new UserContext(UUID.randomUUID());

        assertThatThrownBy(() -> service.dispatch(
                PLANNING_REQUEST, CANDIDATE_SET, otherUser, CREDENTIAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("userContext must match the planning request language profile owner");

        verifyNoInteractions(requestFactory, runCreationService, jobDispatch);
    }

    @Test
    void preparesRequestCreatesDurableRunThenDispatchesItsExistingJob() {
        readyRequest();
        when(runCreationService.create(eq(USER), eq(CANDIDATE_SET), any(OffsetDateTime.class)))
                .thenReturn(new PlanningRunCreationService.Created(
                        run(), job(), snapshot()));
        when(jobDispatch.dispatchCreated(any(DispatchCommand.class)))
                .thenReturn(new TextGenerationJobDispatch.DispatchResult(
                        JOB_ID, SubmissionOutcome.ACCEPTED));
        OffsetDateTime before = OffsetDateTime.now().plus(RESULT_TTL);

        PlannerEnrichmentDispatchService.DispatchResult result =
                service.dispatch(PLANNING_REQUEST, CANDIDATE_SET, USER, CREDENTIAL);

        OffsetDateTime after = OffsetDateTime.now().plus(RESULT_TTL);
        assertThat(result).isEqualTo(new PlannerEnrichmentDispatchService.DispatchResult.Created(
                run(), JOB_ID, SubmissionOutcome.ACCEPTED));
        ArgumentCaptor<OffsetDateTime> expiryCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<DispatchCommand> dispatchCaptor = ArgumentCaptor.forClass(DispatchCommand.class);
        InOrder order = inOrder(requestFactory, runCreationService, jobDispatch);
        order.verify(requestFactory).build(PLANNING_REQUEST, CANDIDATE_SET);
        order.verify(runCreationService).create(eq(USER), eq(CANDIDATE_SET), expiryCaptor.capture());
        order.verify(jobDispatch).dispatchCreated(dispatchCaptor.capture());
        assertThat(expiryCaptor.getValue().toInstant())
                .isBetween(before.toInstant(), after.toInstant());
        assertThat(dispatchCaptor.getValue().job()).isEqualTo(job());
        assertThat(dispatchCaptor.getValue().request()).isSameAs(REQUEST);
        assertThat(dispatchCaptor.getValue().credential()).isSameAs(CREDENTIAL);
    }

    @Test
    void unavailableRequestCreatesNoDurableState() {
        when(requestFactory.build(PLANNING_REQUEST, CANDIDATE_SET))
                .thenReturn(new PlannerEnrichmentTextRequestFactory.BuildResult.Unavailable(
                        PlannerEnrichmentTextRequestFactory.UnavailabilityReason.PROMPT_UNAVAILABLE));

        PlannerEnrichmentDispatchService.DispatchResult result =
                service.dispatch(PLANNING_REQUEST, CANDIDATE_SET, USER, CREDENTIAL);

        assertThat(result).isEqualTo(new PlannerEnrichmentDispatchService.DispatchResult.Unavailable(
                PlannerEnrichmentTextRequestFactory.UnavailabilityReason.PROMPT_UNAVAILABLE));
        verifyNoInteractions(runCreationService, jobDispatch);
    }

    @Test
    void capacityOutcomeIsPreservedAfterGenericDispatchPersistsIt() {
        readyRequest();
        when(runCreationService.create(eq(USER), eq(CANDIDATE_SET), any(OffsetDateTime.class)))
                .thenReturn(new PlanningRunCreationService.Created(
                        run(), job(), snapshot()));
        when(jobDispatch.dispatchCreated(any(DispatchCommand.class)))
                .thenReturn(new TextGenerationJobDispatch.DispatchResult(
                        JOB_ID, SubmissionOutcome.CAPACITY_UNAVAILABLE));

        PlannerEnrichmentDispatchService.DispatchResult result =
                service.dispatch(PLANNING_REQUEST, CANDIDATE_SET, USER, CREDENTIAL);

        assertThat(result).isEqualTo(new PlannerEnrichmentDispatchService.DispatchResult.Created(
                run(), JOB_ID, SubmissionOutcome.CAPACITY_UNAVAILABLE));
    }

    @Test
    void unexpectedDispatchFailurePropagates() {
        readyRequest();
        when(runCreationService.create(eq(USER), eq(CANDIDATE_SET), any(OffsetDateTime.class)))
                .thenReturn(new PlanningRunCreationService.Created(
                        run(), job(), snapshot()));
        RuntimeException failure = new RuntimeException("executor state unknown");
        when(jobDispatch.dispatchCreated(any(DispatchCommand.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.dispatch(
                PLANNING_REQUEST, CANDIDATE_SET, USER, CREDENTIAL))
                .isSameAs(failure);
    }

    private void readyRequest() {
        when(requestFactory.build(PLANNING_REQUEST, CANDIDATE_SET))
                .thenReturn(new PlannerEnrichmentTextRequestFactory.BuildResult.ReadyRequest(REQUEST));
    }

    private static LearningTaskPlan deterministicPlan(int sequence) {
        return new LearningTaskPlan(
                PROFILE_ID,
                new MaterialIdentity("builtin:text-practice/material-" + sequence, "2026.03.1"),
                "en",
                "zh",
                MaterialDifficulty.FOUNDATION,
                7,
                "Ordering breakfast at a café",
                "Ask the staff a follow-up question about today's specials",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK);
    }

    private static PlanningRun run() {
        return new PlanningRun(
                RUN_ID, USER_ID, PROFILE_ID, JOB_ID, PlanningRun.Status.PENDING,
                PlanningRun.CURRENT_WORKFLOW_VERSION, 0L, CREATED_AT, Optional.empty());
    }

    private static ModelCallJob job() {
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
                ModelCallJob.ExecutionStatus.CREATED,
                ModelCallJob.ConsumptionStatus.NOT_READY,
                Optional.empty(),
                0L,
                CREATED_AT,
                Optional.empty(),
                RESULT_EXPIRES_AT);
    }

    private static PlanningRun.Snapshot snapshot() {
        return new PlanningRun.Snapshot(
                PROFILE_ID,
                List.of(
                        new PlanningRun.Candidate(0, new MaterialIdentity(
                                "builtin:text-practice/material-0", "2026.03.1")),
                        new PlanningRun.Candidate(1, new MaterialIdentity(
                                "builtin:text-practice/material-1", "2026.03.1"))));
    }
}
