package com.dailylanguage.planner.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.domain.NewModelCallJob;
import com.dailylanguage.modelcalljob.infrastructure.ModelCallJobRepository;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.planner.domain.PlanningCandidateSet;
import com.dailylanguage.planner.domain.PlanningRun;
import com.dailylanguage.planner.infrastructure.PlanningRunRepository;
import com.dailylanguage.security.domain.UserContext;

class PlanningRunCreationServiceTests {

    private static final UUID USER_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000081");
    private static final UUID PROFILE_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000082");
    private static final UUID RUN_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000083");
    private static final UUID JOB_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000084");
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-14T10:10:30.123Z");
    // 每次加载时生成，避免固定日期过期后违反 expires_at > created_at 的 fixture 不变量。
    private static final OffsetDateTime RESULT_EXPIRES_AT = OffsetDateTime.now().plusDays(7);

    private final ModelCallJobRepository modelCallJobRepository =
            Mockito.mock(ModelCallJobRepository.class);
    private final PlanningRunRepository planningRunRepository =
            Mockito.mock(PlanningRunRepository.class);

    private final PlanningRunCreationService service =
            new PlanningRunCreationService(modelCallJobRepository, planningRunRepository);

    @Test
    void nullArgumentsAreProgrammingErrors() {
        PlanningCandidateSet candidateSet = candidateSet(3);
        UserContext user = new UserContext(USER_ID);

        assertThatThrownBy(() -> service.create(null, candidateSet, RESULT_EXPIRES_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("userContext must not be null");
        assertThatThrownBy(() -> service.create(user, null, RESULT_EXPIRES_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("candidateSet must not be null");
        assertThatThrownBy(() -> service.create(user, candidateSet, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("resultExpiresAt must not be null");
    }

    @Test
    void usesRequiresNewTransaction() throws NoSuchMethodException {
        Transactional transactional = PlanningRunCreationService.class
                .getMethod("create", UserContext.class, PlanningCandidateSet.class, OffsetDateTime.class)
                .getAnnotation(Transactional.class);

        // Run/Job/candidates 必须在独立事务内提交；REQUIRES_NEW 保证返回即 durable，且外层事务
        // 无法把后续 dispatch 拉进未提交窗口。
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void doesNotDependOnJobStartOrSubmissionBoundaries() {
        // S9C2 只创建 durable identity，不触发任何 dispatch；结构上禁止依赖 job start /
        // submission / worker 边界。
        List<String> forbiddenTypes = Arrays.stream(
                        PlanningRunCreationService.class.getDeclaredFields())
                .map(Field::getType)
                .filter(type -> type.getPackageName()
                        .startsWith("com.dailylanguage.modelcalljob.application"))
                .map(Class::getName)
                .toList();

        assertThat(forbiddenTypes).isEmpty();
    }

    @Test
    void createsJobRunAndCandidatesInOrderWithExactIdentity() {
        PlanningCandidateSet candidateSet = candidateSet(3);
        when(planningRunRepository.nextRunId()).thenReturn(RUN_ID);
        when(modelCallJobRepository.create(any(NewModelCallJob.class))).thenReturn(job());
        when(planningRunRepository.insertOwned(RUN_ID, JOB_ID, USER_ID, PROFILE_ID, 0L))
                .thenReturn(run());

        PlanningRunCreationService.Created created =
                service.create(new UserContext(USER_ID), candidateSet, RESULT_EXPIRES_AT);

        ArgumentCaptor<NewModelCallJob> jobCommand =
                ArgumentCaptor.forClass(NewModelCallJob.class);
        ArgumentCaptor<PlanningRun.Snapshot> snapshotCaptor =
                ArgumentCaptor.forClass(PlanningRun.Snapshot.class);
        InOrder inOrder = inOrder(planningRunRepository, modelCallJobRepository);
        inOrder.verify(planningRunRepository).nextRunId();
        inOrder.verify(modelCallJobRepository).create(jobCommand.capture());
        inOrder.verify(planningRunRepository).insertOwned(RUN_ID, JOB_ID, USER_ID, PROFILE_ID, 0L);
        inOrder.verify(planningRunRepository)
                .insertCandidates(any(), any(), any(), snapshotCaptor.capture());

        NewModelCallJob command = jobCommand.getValue();
        assertThat(command.userId()).isEqualTo(USER_ID);
        assertThat(command.languageProfileId()).isEqualTo(Optional.of(PROFILE_ID));
        assertThat(command.modelPurpose()).isEqualTo(ModelPurpose.PLANNING);
        assertThat(command.modelOperation()).isEqualTo(ModelOperation.TEXT_GENERATION);
        assertThat(command.providerId()).isEmpty();
        assertThat(command.modelId()).isEmpty();
        assertThat(command.workflowId()).isEqualTo(RUN_ID);
        assertThat(command.workflowStepId()).isEqualTo("PLANNER_ENRICHMENT");
        assertThat(command.workflowVersion()).isZero();
        assertThat(command.expiresAt()).isEqualTo(RESULT_EXPIRES_AT);

        assertThat(snapshotCaptor.getValue().languageProfileId()).isEqualTo(PROFILE_ID);
        assertThat(snapshotCaptor.getValue().candidates())
                .extracting(PlanningRun.Candidate::index)
                .containsExactly(0, 1, 2);
        assertThat(snapshotCaptor.getValue().candidates())
                .extracting(PlanningRun.Candidate::identity)
                .containsExactly(
                        new MaterialIdentity("builtin:text-practice/material-0", "2026.03.1"),
                        new MaterialIdentity("builtin:text-practice/material-1", "2026.03.1"),
                        new MaterialIdentity("builtin:text-practice/material-2", "2026.03.1"));

        assertThat(created.run()).isEqualTo(run());
        assertThat(created.job()).isEqualTo(job());
        assertThat(created.snapshot()).isEqualTo(snapshotCaptor.getValue());
    }

    @Test
    void rejectsEnrichedCandidateSetBeforeAnyRepositoryInteraction() {
        PlanningCandidateSet enrichedSet = new PlanningCandidateSet(
                List.of(deterministicPlan(0), enrichedPlan()));

        assertThatThrownBy(() ->
                service.create(new UserContext(USER_ID), enrichedSet, RESULT_EXPIRES_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("candidate plans must stay deterministic without recommendationReason");

        verifyNoInteractions(planningRunRepository, modelCallJobRepository);
    }

    @Test
    void jobCreationFailurePropagatesWithoutRunInsert() {
        when(planningRunRepository.nextRunId()).thenReturn(RUN_ID);
        when(modelCallJobRepository.create(any(NewModelCallJob.class)))
                .thenThrow(new IllegalStateException("job insert failed"));

        assertThatThrownBy(() ->
                service.create(new UserContext(USER_ID), candidateSet(1), RESULT_EXPIRES_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("job insert failed");

        verify(planningRunRepository, never()).insertOwned(any(), any(), any(), any(), anyLong());
        verify(planningRunRepository, never()).insertCandidates(any(), any(), any(), any());
    }

    @Test
    void runInsertFailurePropagatesWithoutCandidates() {
        when(planningRunRepository.nextRunId()).thenReturn(RUN_ID);
        when(modelCallJobRepository.create(any(NewModelCallJob.class))).thenReturn(job());
        when(planningRunRepository.insertOwned(any(), any(), any(), any(), anyLong()))
                .thenThrow(new IllegalStateException("run insert failed"));

        assertThatThrownBy(() ->
                service.create(new UserContext(USER_ID), candidateSet(1), RESULT_EXPIRES_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("run insert failed");

        verify(planningRunRepository, never()).insertCandidates(any(), any(), any(), any());
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

    private static LearningTaskPlan enrichedPlan() {
        return new LearningTaskPlan(
                PROFILE_ID,
                new MaterialIdentity("builtin:text-practice/material-enriched", "2026.03.1"),
                "en",
                "zh",
                MaterialDifficulty.FOUNDATION,
                7,
                "Ordering breakfast at a café",
                "Ask the staff a follow-up question about today's specials",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.MODEL_ENRICHED,
                Optional.of("今天在咖啡馆练习点单，并追问今日特供。"));
    }

    private static PlanningCandidateSet candidateSet(int count) {
        return new PlanningCandidateSet(
                java.util.stream.IntStream.range(0, count)
                        .mapToObj(PlanningRunCreationServiceTests::deterministicPlan)
                        .toList());
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
                PlanningRunCreationService.WORKFLOW_STEP_ID,
                PlanningRunCreationService.WORKFLOW_VERSION,
                ModelCallJob.ExecutionStatus.CREATED,
                ModelCallJob.ConsumptionStatus.NOT_READY,
                Optional.empty(),
                0L,
                CREATED_AT,
                Optional.empty(),
                RESULT_EXPIRES_AT);
    }

    private static PlanningRun run() {
        return new PlanningRun(
                RUN_ID,
                USER_ID,
                PROFILE_ID,
                JOB_ID,
                PlanningRun.Status.PENDING,
                PlanningRunCreationService.WORKFLOW_VERSION,
                0L,
                CREATED_AT,
                Optional.empty());
    }
}
