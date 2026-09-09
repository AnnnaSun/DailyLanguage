package com.dailylanguage.evaluator.application;

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
import com.dailylanguage.content.domain.MaterialSourceLineage;
import com.dailylanguage.content.domain.PublishedLearningMaterial;
import com.dailylanguage.content.domain.SupportScaffold;
import com.dailylanguage.content.domain.TargetPracticeCore;
import com.dailylanguage.content.domain.TextLearningPurpose;
import com.dailylanguage.content.domain.TextPracticeStep;
import com.dailylanguage.content.domain.TextStepKind;
import com.dailylanguage.evaluator.application.EvaluationRunCreationService.CreationResult;
import com.dailylanguage.evaluator.application.GroundedEvaluationInputResult.Ready;
import com.dailylanguage.evaluator.domain.EvaluationRun;
import com.dailylanguage.evaluator.domain.GroundedEvaluationInput;
import com.dailylanguage.evaluator.infrastructure.EvaluationRunRepository;
import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.domain.NewModelCallJob;
import com.dailylanguage.modelcalljob.infrastructure.ModelCallJobRepository;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.practice.domain.DeterministicAssessment;
import com.dailylanguage.practice.domain.DeterministicAssessment.StepKind;
import com.dailylanguage.practice.domain.DeterministicAssessment.StepOutcome;
import com.dailylanguage.practice.domain.DeterministicAssessment.StepResult;
import com.dailylanguage.practice.domain.DeterministicTextAssessmentPolicy;
import com.dailylanguage.practice.domain.PracticeSession;
import com.dailylanguage.practice.domain.PracticeSession.LearnerResponse;
import com.dailylanguage.practice.infrastructure.PracticeSessionRepository;
import com.dailylanguage.security.domain.UserContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EvaluationRunCreationServiceTests {

    private static final UUID USER_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000071");
    private static final UUID OTHER_USER_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000072");
    private static final UUID PROFILE_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000073");
    private static final UUID OTHER_PROFILE_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000074");
    private static final UUID TASK_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000075");
    private static final UUID SESSION_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000076");
    private static final UUID RUN_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000077");
    private static final UUID JOB_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000078");
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-07T10:10:30.123Z");
    private static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-09-07T10:15:30.123Z");
    private static final OffsetDateTime COMPLETED_AT = OffsetDateTime.parse("2026-09-07T10:22:00.123Z");
    // 每次加载时生成，避免固定日期过期后违反 expires_at > created_at 的 fixture 不变量。
    private static final OffsetDateTime RESULT_EXPIRES_AT = OffsetDateTime.now().plusDays(7);

    private final PracticeSessionRepository practiceSessionRepository =
            Mockito.mock(PracticeSessionRepository.class);
    private final ModelCallJobRepository modelCallJobRepository =
            Mockito.mock(ModelCallJobRepository.class);
    private final EvaluationRunRepository evaluationRunRepository =
            Mockito.mock(EvaluationRunRepository.class);

    private final EvaluationRunCreationService service = new EvaluationRunCreationService(
            practiceSessionRepository, modelCallJobRepository, evaluationRunRepository);

    // --- Created：精确 Job command 与固定顺序 ---

    @Test
    void createsExactEvaluationJobCommandForMatchingReadyInput() {
        stubCreationPath();

        CreationResult result = service.createForReadyInput(
                ready(), new UserContext(USER_ID), RESULT_EXPIRES_AT);

        assertThat(result).isInstanceOfSatisfying(CreationResult.Created.class, created -> {
            assertThat(created.run()).isEqualTo(run());
            assertThat(created.job()).isEqualTo(evaluationJob());
        });
        ArgumentCaptor<NewModelCallJob> jobCaptor = ArgumentCaptor.forClass(NewModelCallJob.class);
        verify(modelCallJobRepository).create(jobCaptor.capture());
        NewModelCallJob command = jobCaptor.getValue();
        assertThat(command.userId()).isEqualTo(USER_ID);
        assertThat(command.languageProfileId()).isEqualTo(Optional.of(PROFILE_ID));
        assertThat(command.modelPurpose()).isEqualTo(ModelPurpose.EVALUATION);
        assertThat(command.modelOperation()).isEqualTo(ModelOperation.TEXT_GENERATION);
        assertThat(command.providerId()).isEmpty();
        assertThat(command.modelId()).isEmpty();
        assertThat(command.workflowId()).isEqualTo(RUN_ID);
        assertThat(command.workflowStepId()).isEqualTo(EvaluationRunCreationService.WORKFLOW_STEP_ID);
        assertThat(command.workflowVersion()).isEqualTo(EvaluationRunCreationService.WORKFLOW_VERSION);
        assertThat(command.expiresAt()).isEqualTo(RESULT_EXPIRES_AT);
        verify(evaluationRunRepository).insertOwned(
                RUN_ID, SESSION_ID, JOB_ID, USER_ID, PROFILE_ID,
                EvaluationRunCreationService.WORKFLOW_VERSION);
    }

    @Test
    void followsContractedOrderLockLookupIdJobRun() {
        stubCreationPath();

        service.createForReadyInput(ready(), new UserContext(USER_ID), RESULT_EXPIRES_AT);

        InOrder order = inOrder(practiceSessionRepository, evaluationRunRepository, modelCallJobRepository);
        order.verify(practiceSessionRepository).findOwnedForUpdate(SESSION_ID, USER_ID, PROFILE_ID);
        order.verify(evaluationRunRepository).findOwnedBySessionId(SESSION_ID, USER_ID, PROFILE_ID);
        order.verify(evaluationRunRepository).nextRunId();
        order.verify(modelCallJobRepository).create(any(NewModelCallJob.class));
        order.verify(evaluationRunRepository)
                .insertOwned(RUN_ID, SESSION_ID, JOB_ID, USER_ID, PROFILE_ID,
                        EvaluationRunCreationService.WORKFLOW_VERSION);
    }

    // --- fail-closed 业务分支 ---

    @Test
    void mismatchedUserContextReturnsInconsistentInputWithoutAnyRead() {
        CreationResult result = service.createForReadyInput(
                ready(), new UserContext(OTHER_USER_ID), RESULT_EXPIRES_AT);

        assertThat(result).isEqualTo(new CreationResult.InconsistentInput());
        verifyNoInteractions(practiceSessionRepository, modelCallJobRepository, evaluationRunRepository);
    }

    @Test
    void unknownSessionReturnsNotFound() {
        when(practiceSessionRepository.findOwnedForUpdate(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.empty());

        assertThat(service.createForReadyInput(ready(), new UserContext(USER_ID), RESULT_EXPIRES_AT))
                .isEqualTo(new CreationResult.NotFound());
        verifyNoInteractions(evaluationRunRepository, modelCallJobRepository);
    }

    @Test
    void nonCompletedSessionReturnsNotCompleted() {
        when(practiceSessionRepository.findOwnedForUpdate(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(new PracticeSession(
                        SESSION_ID, TASK_ID, PracticeSession.Status.IN_PROGRESS,
                        STARTED_AT, Optional.empty(), Optional.empty())));

        assertThat(service.createForReadyInput(ready(), new UserContext(USER_ID), RESULT_EXPIRES_AT))
                .isEqualTo(new CreationResult.NotCompleted());
        verifyNoInteractions(evaluationRunRepository, modelCallJobRepository);
    }

    @Test
    void lockedTaskMismatchReturnsInconsistentInput() {
        when(practiceSessionRepository.findOwnedForUpdate(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(new PracticeSession(
                        SESSION_ID, UUID.randomUUID(), PracticeSession.Status.COMPLETED,
                        STARTED_AT, Optional.of(COMPLETED_AT), Optional.empty())));

        assertThat(service.createForReadyInput(ready(), new UserContext(USER_ID), RESULT_EXPIRES_AT))
                .isEqualTo(new CreationResult.InconsistentInput());
        verifyNoInteractions(evaluationRunRepository, modelCallJobRepository);
    }

    // --- Existing：幂等返回，不创建新 ID / Job / Run ---

    @Test
    void existingRunReturnsExistingWithoutNewIdJobOrRunInsert() {
        when(practiceSessionRepository.findOwnedForUpdate(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(completedSession()));
        when(evaluationRunRepository.findOwnedBySessionId(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(run()));
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(evaluationJob()));

        CreationResult result = service.createForReadyInput(
                ready(), new UserContext(USER_ID), RESULT_EXPIRES_AT);

        assertThat(result).isInstanceOfSatisfying(CreationResult.Existing.class, existing -> {
            assertThat(existing.run()).isEqualTo(run());
            assertThat(existing.job()).isEqualTo(evaluationJob());
        });
        verify(evaluationRunRepository, never()).nextRunId();
        verify(modelCallJobRepository, never()).create(any());
        verify(evaluationRunRepository, never())
                .insertOwned(any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void existingRunWithMissingJobFailsClosedAsInvariantDamage() {
        when(practiceSessionRepository.findOwnedForUpdate(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(completedSession()));
        when(evaluationRunRepository.findOwnedBySessionId(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(run()));
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.empty());

        // 持久化不变量损坏不是 caller input 问题：以异常 fail closed，不创建替代 Job。
        assertThatThrownBy(() ->
                service.createForReadyInput(ready(), new UserContext(USER_ID), RESULT_EXPIRES_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("existing evaluation run has a missing or inconsistent evaluation job");
        verify(modelCallJobRepository, never()).create(any());
        verify(evaluationRunRepository, never()).insertOwned(any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void existingRunWithMismatchedWorkflowIdentityFailsClosedAsInvariantDamage() {
        // 六类创建期 identity 偏差都必须以不变量损坏 fail closed，不创建替代 Job。
        ModelCallJob wrongWorkflowId = evaluationJob(UUID.randomUUID(),
                EvaluationRunCreationService.WORKFLOW_STEP_ID,
                EvaluationRunCreationService.WORKFLOW_VERSION, ModelPurpose.EVALUATION,
                ModelOperation.TEXT_GENERATION, PROFILE_ID);
        ModelCallJob wrongStepId = evaluationJob(RUN_ID, "OTHER_STEP",
                EvaluationRunCreationService.WORKFLOW_VERSION, ModelPurpose.EVALUATION,
                ModelOperation.TEXT_GENERATION, PROFILE_ID);
        ModelCallJob wrongVersion = evaluationJob(RUN_ID,
                EvaluationRunCreationService.WORKFLOW_STEP_ID, 1L, ModelPurpose.EVALUATION,
                ModelOperation.TEXT_GENERATION, PROFILE_ID);
        ModelCallJob wrongPurpose = evaluationJob(RUN_ID,
                EvaluationRunCreationService.WORKFLOW_STEP_ID,
                EvaluationRunCreationService.WORKFLOW_VERSION, ModelPurpose.PLANNING,
                ModelOperation.TEXT_GENERATION, PROFILE_ID);
        ModelCallJob wrongOperation = evaluationJob(RUN_ID,
                EvaluationRunCreationService.WORKFLOW_STEP_ID,
                EvaluationRunCreationService.WORKFLOW_VERSION, ModelPurpose.EVALUATION,
                ModelOperation.EMBEDDING, PROFILE_ID);
        ModelCallJob wrongProfile = evaluationJob(RUN_ID,
                EvaluationRunCreationService.WORKFLOW_STEP_ID,
                EvaluationRunCreationService.WORKFLOW_VERSION, ModelPurpose.EVALUATION,
                ModelOperation.TEXT_GENERATION, OTHER_PROFILE_ID);
        List<ModelCallJob> brokenJobs = List.of(
                wrongWorkflowId, wrongStepId, wrongVersion, wrongPurpose, wrongOperation, wrongProfile);

        for (ModelCallJob brokenJob : brokenJobs) {
            Mockito.reset(practiceSessionRepository, modelCallJobRepository, evaluationRunRepository);
            when(practiceSessionRepository.findOwnedForUpdate(SESSION_ID, USER_ID, PROFILE_ID))
                    .thenReturn(Optional.of(completedSession()));
            when(evaluationRunRepository.findOwnedBySessionId(SESSION_ID, USER_ID, PROFILE_ID))
                    .thenReturn(Optional.of(run()));
            when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                    .thenReturn(Optional.of(brokenJob));

            assertThatThrownBy(() ->
                    service.createForReadyInput(ready(), new UserContext(USER_ID), RESULT_EXPIRES_AT))
                    .as("job %s must fail closed", brokenJob)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("existing evaluation run has a missing or inconsistent evaluation job");
            verify(modelCallJobRepository, never()).create(any());
            verify(evaluationRunRepository, never())
                    .insertOwned(any(), any(), any(), any(), any(), anyLong());
        }
    }

    // --- 原子性：任一失败即整体回滚，异常传播 ---

    @Test
    void jobCreationFailurePropagatesWithoutRunInsert() {
        when(practiceSessionRepository.findOwnedForUpdate(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(completedSession()));
        when(evaluationRunRepository.findOwnedBySessionId(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.empty());
        when(evaluationRunRepository.nextRunId()).thenReturn(RUN_ID);
        RuntimeException failure = new RuntimeException("job insert failed");
        when(modelCallJobRepository.create(any(NewModelCallJob.class))).thenThrow(failure);

        assertThatThrownBy(() ->
                service.createForReadyInput(ready(), new UserContext(USER_ID), RESULT_EXPIRES_AT))
                .isSameAs(failure);
        verify(evaluationRunRepository, never()).insertOwned(any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void runInsertFailurePropagates() {
        when(practiceSessionRepository.findOwnedForUpdate(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(completedSession()));
        when(evaluationRunRepository.findOwnedBySessionId(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.empty());
        when(evaluationRunRepository.nextRunId()).thenReturn(RUN_ID);
        when(modelCallJobRepository.create(any(NewModelCallJob.class))).thenReturn(evaluationJob());
        RuntimeException failure = new RuntimeException("run insert failed");
        when(evaluationRunRepository.insertOwned(
                RUN_ID, SESSION_ID, JOB_ID, USER_ID, PROFILE_ID,
                EvaluationRunCreationService.WORKFLOW_VERSION)).thenThrow(failure);

        assertThatThrownBy(() ->
                service.createForReadyInput(ready(), new UserContext(USER_ID), RESULT_EXPIRES_AT))
                .isSameAs(failure);
    }

    // --- 编程错误与架构契约 ---

    @Test
    void nullArgumentsAreProgrammingErrors() {
        assertThatThrownBy(() -> service.createForReadyInput(null, new UserContext(USER_ID), RESULT_EXPIRES_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("ready must not be null");
        assertThatThrownBy(() -> service.createForReadyInput(ready(), null, RESULT_EXPIRES_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("userContext must not be null");
        assertThatThrownBy(() -> service.createForReadyInput(ready(), new UserContext(USER_ID), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("resultExpiresAt must not be null");
    }

    @Test
    void usesRequiresNewTransaction() throws NoSuchMethodException {
        Transactional transactional = EvaluationRunCreationService.class
                .getMethod("createForReadyInput",
                        Ready.class, UserContext.class, OffsetDateTime.class)
                .getAnnotation(Transactional.class);

        // Run/Job 必须在独立事务内提交；REQUIRES_NEW 保证返回即 durable，且外层事务无法把
        // dispatch 拉进未提交窗口。
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void doesNotDependOnJobStartOrSubmissionBoundaries() {
        // S8B 只创建 durable identity，不触发任何 dispatch；结构上禁止依赖 job start /
        // submission / worker 边界。
        List<String> forbiddenTypes = Arrays.stream(
                        EvaluationRunCreationService.class.getDeclaredFields())
                .map(Field::getType)
                .filter(type -> type.getPackageName()
                        .startsWith("com.dailylanguage.modelcalljob.application"))
                .map(Class::getName)
                .toList();
        assertThat(forbiddenTypes).isEmpty();
    }

    // --- stubs 与 fixtures ---

    private void stubCreationPath() {
        when(practiceSessionRepository.findOwnedForUpdate(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(completedSession()));
        when(evaluationRunRepository.findOwnedBySessionId(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.empty());
        when(evaluationRunRepository.nextRunId()).thenReturn(RUN_ID);
        when(modelCallJobRepository.create(any(NewModelCallJob.class))).thenReturn(evaluationJob());
        when(evaluationRunRepository.insertOwned(
                RUN_ID, SESSION_ID, JOB_ID, USER_ID, PROFILE_ID,
                EvaluationRunCreationService.WORKFLOW_VERSION)).thenReturn(run());
    }

    private static Ready ready() {
        return new Ready(new GroundedEvaluationInput(
                USER_ID, PROFILE_ID, completedTask(), completedSession(), assessment(),
                List.of(new LearnerResponse(SESSION_ID, "order-drink", "Could I have a medium coffee, please?", STARTED_AT)),
                material()));
    }

    private static LearningTask completedTask() {
        return new LearningTask(
                TASK_ID, USER_ID, PROFILE_ID,
                new MaterialIdentity("en-builtin-cafe-request", "v1"),
                "en", "zh-cn", MaterialDifficulty.FOUNDATION, 10,
                "CAFE_SIMPLE_REQUEST",
                "Make a polite request, ask about price, and answer a follow-up question in a coffee shop.",
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

    private static PublishedLearningMaterial material() {
        return new PublishedLearningMaterial(
                new MaterialIdentity("en-builtin-cafe-request", "v1"),
                new TargetPracticeCore(
                        "en", MaterialDifficulty.FOUNDATION, "CAFE_SIMPLE_REQUEST", "objective",
                        "target text", null,
                        List.of(new TextPracticeStep("order-drink", TextStepKind.EXACT, TextLearningPurpose.PRACTICE,
                                "prompt", List.of("answer"))),
                        "builtin-text-communication-rubric/v1"),
                List.of(new SupportScaffold("zh-cn", "instruction", "explanation", "hint", null, List.of())),
                new MaterialSourceLineage("PROJECT_ORIGINAL", "1", "AGPL-3.0",
                        "sha256:" + "0".repeat(64)));
    }

    private static EvaluationRun run() {
        return new EvaluationRun(
                RUN_ID, SESSION_ID, JOB_ID, EvaluationRun.Status.PENDING,
                EvaluationRunCreationService.WORKFLOW_VERSION, 0L, COMPLETED_AT,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ModelCallJob evaluationJob() {
        return evaluationJob(RUN_ID, EvaluationRunCreationService.WORKFLOW_STEP_ID,
                EvaluationRunCreationService.WORKFLOW_VERSION, ModelPurpose.EVALUATION,
                ModelOperation.TEXT_GENERATION, PROFILE_ID);
    }

    private static ModelCallJob evaluationJob(
            UUID workflowId, String workflowStepId, long workflowVersion,
            ModelPurpose modelPurpose, ModelOperation modelOperation, UUID languageProfileId) {
        return new ModelCallJob(
                JOB_ID, USER_ID, Optional.of(languageProfileId), modelPurpose,
                modelOperation, Optional.empty(), Optional.empty(),
                workflowId, workflowStepId, workflowVersion,
                ModelCallJob.ExecutionStatus.CREATED, ModelCallJob.ConsumptionStatus.NOT_READY,
                Optional.empty(), 0L, CREATED_AT, Optional.empty(), RESULT_EXPIRES_AT);
    }
}
