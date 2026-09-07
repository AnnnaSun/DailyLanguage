package com.dailylanguage.evaluator.application;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.content.domain.MaterialSourceLineage;
import com.dailylanguage.content.domain.PublishedLearningMaterial;
import com.dailylanguage.content.domain.SupportScaffold;
import com.dailylanguage.content.domain.TargetPracticeCore;
import com.dailylanguage.content.domain.TextPracticeStep;
import com.dailylanguage.content.domain.TextStepKind;
import com.dailylanguage.evaluator.application.EvaluationRunCreationService.CreationResult;
import com.dailylanguage.evaluator.application.GroundedEvaluationInputResult.Ready;
import com.dailylanguage.evaluator.domain.EvaluationRun;
import com.dailylanguage.evaluator.domain.GroundedEvaluationInput;
import com.dailylanguage.evaluator.infrastructure.EvaluationRunRepository;
import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.languageprofile.infrastructure.LanguageProfileRepository;
import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.domain.NewModelCallJob;
import com.dailylanguage.modelcalljob.infrastructure.ModelCallJobRepository;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.planner.application.LearningTaskPlanningResult;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.Created;
import com.dailylanguage.planner.application.LearningTaskPlanningService;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.practice.application.PracticeSessionApplicationService;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.CompletionResult;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.StartResult;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.SubmitResult;
import com.dailylanguage.practice.domain.DeterministicAssessment;
import com.dailylanguage.practice.domain.DeterministicAssessment.StepKind;
import com.dailylanguage.practice.domain.DeterministicAssessment.StepOutcome;
import com.dailylanguage.practice.domain.DeterministicAssessment.StepResult;
import com.dailylanguage.practice.domain.DeterministicTextAssessmentPolicy;
import com.dailylanguage.practice.domain.PracticeSession;
import com.dailylanguage.practice.domain.PracticeSession.LearnerResponse;
import com.dailylanguage.security.domain.UserContext;
import com.dailylanguage.user.infrastructure.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * 用真实 PostgreSQL durable 数据验证 S8B：EvaluationRun 与唯一 ModelCallJob 在一个 REQUIRES_NEW
 * 事务内原子创建、重复 / 并发请求返回同一关联、失败整体回滚不留 orphan。fixture 数据必须先于
 * 服务调用提交（独立事务），因此本测试不使用测试管理的 @Transactional，改在 AfterEach 按 FK
 * 依赖顺序清理。
 */
@SpringBootTest(properties = "app.registration-enabled=true")
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class EvaluationRunCreationIntegrationTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LanguageProfileRepository languageProfileRepository;

    @Autowired
    private LearningTaskPlanningService planningService;

    @Autowired
    private PracticeSessionApplicationService practiceService;

    @Autowired
    private GroundedEvaluationInputReader reader;

    @Autowired
    private EvaluationRunCreationService service;

    @Autowired
    private ModelCallJobRepository modelCallJobRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SqlSessionTemplate sqlSession;

    // spy 默认委托真实 bean；仅 runInsertFailure 用例局部 stub insertOwned。
    @MockitoSpyBean
    private EvaluationRunRepository evaluationRunRepository;

    private final List<UUID> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanupCreatedUsers() {
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("""
                    DELETE FROM evaluation_run WHERE session_id IN (
                        SELECT session.id FROM practice_session session
                        JOIN learning_task task ON task.id = session.task_id
                        WHERE task.user_id = ?)""", userId);
            jdbcTemplate.update("DELETE FROM model_call_job WHERE user_id = ?", userId);
            jdbcTemplate.update("""
                    DELETE FROM practice_response WHERE session_id IN (
                        SELECT session.id FROM practice_session session
                        JOIN learning_task task ON task.id = session.task_id
                        WHERE task.user_id = ?)""", userId);
            jdbcTemplate.update("""
                    DELETE FROM deterministic_step_assessment WHERE session_id IN (
                        SELECT session.id FROM practice_session session
                        JOIN learning_task task ON task.id = session.task_id
                        WHERE task.user_id = ?)""", userId);
            jdbcTemplate.update("""
                    DELETE FROM deterministic_assessment WHERE session_id IN (
                        SELECT session.id FROM practice_session session
                        JOIN learning_task task ON task.id = session.task_id
                        WHERE task.user_id = ?)""", userId);
            jdbcTemplate.update("""
                    DELETE FROM practice_session WHERE task_id IN (
                        SELECT id FROM learning_task WHERE user_id = ?)""", userId);
            jdbcTemplate.update("DELETE FROM learning_task WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM language_profile WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
        createdUserIds.clear();
    }

    @Test
    void createsRunAndJobAtomicallyForCompletedSession() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "en").orElseThrow();
        UserContext user = new UserContext(ownerId);
        UUID sessionId = completeCafeSession(profile.id(), user);
        Ready ready = readyInput(profile.id(), user, sessionId);
        // 创建与断言必须引用同一 expiry 值。
        OffsetDateTime expiry = resultExpiresAt();

        CreationResult result = service.createForReadyInput(ready, user, expiry);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1",
                String.class)).isEqualTo("12");
        assertThat(result).isInstanceOfSatisfying(CreationResult.Created.class, created -> {
            EvaluationRun run = created.run();
            ModelCallJob job = created.job();
            assertThat(run.id()).isEqualTo(job.workflowId());
            assertThat(run.id().version()).as("evaluation_run.id is UUIDv7").isEqualTo(7);
            assertThat(job.id().version()).as("model_call_job.id is UUIDv7").isEqualTo(7);
            assertThat(run.sessionId()).isEqualTo(sessionId);
            assertThat(run.modelCallJobId()).isEqualTo(job.id());
            assertThat(run.status()).isEqualTo(EvaluationRun.Status.PENDING);
            assertThat(run.workflowVersion()).isZero();
            assertThat(run.rowVersion()).isZero();
            assertThat(run.createdAt()).isNotNull();
            assertThat(run.completedAt()).isEmpty();

            assertThat(job.userId()).isEqualTo(ownerId);
            assertThat(job.languageProfileId()).isEqualTo(Optional.of(profile.id()));
            assertThat(job.modelPurpose().name()).isEqualTo("EVALUATION");
            assertThat(job.modelOperation().name()).isEqualTo("TEXT_GENERATION");
            assertThat(job.providerId()).isEmpty();
            assertThat(job.modelId()).isEmpty();
            assertThat(job.workflowStepId()).isEqualTo("SEMANTIC_EVALUATION");
            assertThat(job.workflowVersion()).isZero();
            assertThat(job.executionStatus()).isEqualTo(ModelCallJob.ExecutionStatus.CREATED);
            assertThat(job.consumptionStatus()).isEqualTo(ModelCallJob.ConsumptionStatus.NOT_READY);
            assertThat(job.expiresAt().toInstant()).isEqualTo(expiry.toInstant());
        });
        // durable reread：事务成功返回即两条记录都已提交。
        assertThat(evaluationRunCount(sessionId)).isEqualTo(1);
        assertThat(jobCount(ownerId)).isEqualTo(1);
    }

    @Test
    void repeatedCallReturnsExistingRunAndJob() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "en").orElseThrow();
        UserContext user = new UserContext(ownerId);
        UUID sessionId = completeCafeSession(profile.id(), user);
        Ready ready = readyInput(profile.id(), user, sessionId);

        CreationResult first = service.createForReadyInput(ready, user, resultExpiresAt());
        CreationResult second = service.createForReadyInput(ready, user, resultExpiresAt());

        assertThat(first).isInstanceOf(CreationResult.Created.class);
        assertThat(second).isInstanceOfSatisfying(CreationResult.Existing.class, existing -> {
            assertThat(existing.run().id()).isEqualTo(((CreationResult.Created) first).run().id());
            assertThat(existing.job().id()).isEqualTo(((CreationResult.Created) first).job().id());
        });
        assertThat(evaluationRunCount(sessionId)).isEqualTo(1);
        assertThat(jobCount(ownerId)).isEqualTo(1);
    }

    @Test
    void concurrentCallsCreateSingleRunAndJob() throws Exception {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "en").orElseThrow();
        UserContext user = new UserContext(ownerId);
        UUID sessionId = completeCafeSession(profile.id(), user);
        Ready ready = readyInput(profile.id(), user, sessionId);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<CreationResult> creation = () -> {
                start.await();
                return service.createForReadyInput(ready, user, resultExpiresAt());
            };
            Future<CreationResult> first = executor.submit(creation);
            Future<CreationResult> second = executor.submit(creation);
            start.countDown();
            CreationResult firstResult = first.get(30, TimeUnit.SECONDS);
            CreationResult secondResult = second.get(30, TimeUnit.SECONDS);

            // 后到者在 Session 行锁后查询到既有关联：恰一个 Created + 一个 Existing，同一 Run/Job。
            CreationResult createdResult =
                    firstResult instanceof CreationResult.Created ? firstResult : secondResult;
            CreationResult existingResult =
                    firstResult instanceof CreationResult.Created ? secondResult : firstResult;
            assertThat(createdResult).isInstanceOf(CreationResult.Created.class);
            assertThat(existingResult).isInstanceOf(CreationResult.Existing.class);
            UUID runId = ((CreationResult.Created) createdResult).run().id();
            UUID jobId = ((CreationResult.Created) createdResult).job().id();
            assertThat(((CreationResult.Existing) existingResult).run().id()).isEqualTo(runId);
            assertThat(((CreationResult.Existing) existingResult).job().id()).isEqualTo(jobId);
            assertThat(evaluationRunCount(sessionId)).isEqualTo(1);
            assertThat(jobCount(ownerId)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void wrongOwnerProfileOrUnknownSessionCannotCreate() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "en").orElseThrow();
        // UNIQUE(user_id, language_code) 下同一用户的另一 Profile 用不同语言表达。
        LanguageProfileIdentity otherProfile =
                languageProfileRepository.create(ownerId, "ja").orElseThrow();
        UserContext user = new UserContext(ownerId);
        UUID sessionId = completeCafeSession(profile.id(), user);
        Ready ready = readyInput(profile.id(), user, sessionId);

        // Ready 与 authenticated identity 不一致：fail closed，不读取任何数据。
        assertThat(service.createForReadyInput(ready, new UserContext(UUID.randomUUID()), resultExpiresAt()))
                .isEqualTo(new CreationResult.InconsistentInput());
        // 同一 user 的另一 Profile：owner/profile 范围内不存在该 Session。
        GroundedEvaluationInput otherProfileInput = new GroundedEvaluationInput(
                ownerId, otherProfile.id(), ready.input().task(), ready.input().session(),
                ready.input().assessment(), ready.input().responses(), ready.input().material());
        assertThat(service.createForReadyInput(new Ready(otherProfileInput), user, resultExpiresAt()))
                .isEqualTo(new CreationResult.NotFound());
        // 不存在的 Session：同样 NotFound。
        GroundedEvaluationInput unknownSessionInput = new GroundedEvaluationInput(
                ownerId, profile.id(), ready.input().task(),
                new PracticeSession(UUID.randomUUID(), ready.input().session().taskId(),
                        PracticeSession.Status.COMPLETED, ready.input().session().startedAt(),
                        ready.input().session().completedAt(), Optional.empty()),
                ready.input().assessment(), ready.input().responses(), ready.input().material());
        assertThat(service.createForReadyInput(new Ready(unknownSessionInput), user, resultExpiresAt()))
                .isEqualTo(new CreationResult.NotFound());

        assertThat(evaluationRunCount(sessionId)).isZero();
        assertThat(jobCount(ownerId)).isZero();
    }

    @Test
    void inProgressSessionReturnsNotCompleted() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "en").orElseThrow();
        UserContext user = new UserContext(ownerId);
        UUID sessionId = startCafeSession(profile.id(), user);

        // Reader 不会为 IN_PROGRESS Session 产出 Ready；用真实 durable taskId 构造输入，
        // 验证 Service 在行锁后仍独立再检查 completion。
        UUID taskId = jdbcTemplate.queryForObject(
                "SELECT task_id FROM practice_session WHERE id = ?", UUID.class, sessionId);
        GroundedEvaluationInput input = new GroundedEvaluationInput(
                ownerId, profile.id(), task(taskId, ownerId, profile.id()),
                new PracticeSession(sessionId, taskId, PracticeSession.Status.IN_PROGRESS,
                        OffsetDateTime.now().minusMinutes(5), Optional.empty(), Optional.empty()),
                assessment(), responses(), material());

        assertThat(service.createForReadyInput(new Ready(input), user, resultExpiresAt()))
                .isEqualTo(new CreationResult.NotCompleted());
        assertThat(evaluationRunCount(sessionId)).isZero();
        assertThat(jobCount(ownerId)).isZero();
    }

    @Test
    void jobInsertFailureRollsBackRunWithNoOrphanRows() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "en").orElseThrow();
        UserContext user = new UserContext(ownerId);
        UUID sessionId = completeCafeSession(profile.id(), user);
        Ready ready = readyInput(profile.id(), user, sessionId);

        // 违反 ck_model_call_job_timestamps（expires_at > created_at）：Job insert 在数据库内失败。
        OffsetDateTime pastExpiry = OffsetDateTime.parse("2020-01-01T00:00:00Z");
        assertThatThrownBy(() -> service.createForReadyInput(ready, user, pastExpiry))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(evaluationRunCount(sessionId)).isZero();
        assertThat(jobCount(ownerId)).isZero();
    }

    @Test
    void runInsertFailureRollsBackCreatedJob() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "en").orElseThrow();
        UserContext user = new UserContext(ownerId);
        UUID sessionId = completeCafeSession(profile.id(), user);
        Ready ready = readyInput(profile.id(), user, sessionId);

        // Job 已在同一事务内插入；Run insert 失败必须整体回滚，不留下 orphan Job。
        doThrow(new IllegalStateException("run insert failed"))
                .when(evaluationRunRepository)
                .insertOwned(any(), any(), any(), any(), any(), anyLong());

        assertThatThrownBy(() -> service.createForReadyInput(ready, user, resultExpiresAt()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("run insert failed");

        assertThat(jobCount(ownerId)).isZero();
        assertThat(evaluationRunCount(sessionId)).isZero();
    }

    /** 每次执行时生成，避免固定日期过期后正常创建违反 expires_at > created_at。 */
    private static OffsetDateTime resultExpiresAt() {
        return OffsetDateTime.now().plusDays(7);
    }

    private ModelCallJob evaluationJobCommand(
            UUID userId, UUID profileId, ModelPurpose purpose, ModelOperation operation,
            UUID workflowId, String workflowStepId, long workflowVersion, OffsetDateTime expiry) {
        return modelCallJobRepository.create(new NewModelCallJob(
                userId, Optional.of(profileId), purpose, operation,
                Optional.empty(), Optional.empty(), workflowId, workflowStepId, workflowVersion, expiry));
    }

    @Test
    void insertGateRejectsJobsWithoutFullEvaluationWorkflowIdentity() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "en").orElseThrow();
        UserContext user = new UserContext(ownerId);
        UUID sessionId = completeCafeSession(profile.id(), user);
        UUID otherUserId = newUser();
        LanguageProfileIdentity otherProfile =
                languageProfileRepository.create(otherUserId, "en").orElseThrow();

        // Repository 级直测：数据库 insert gate 必须核对 Job 的完整创建期 identity，
        // 任一偏差零行并 fail closed（与上层 Service / 行锁无关）。
        UUID runId = evaluationRunRepository.nextRunId();
        OffsetDateTime expiry = resultExpiresAt();
        ModelCallJob wrongPurpose = evaluationJobCommand(ownerId, profile.id(),
                ModelPurpose.PLANNING, ModelOperation.TEXT_GENERATION,
                runId, EvaluationRunCreationService.WORKFLOW_STEP_ID,
                EvaluationRunCreationService.WORKFLOW_VERSION, expiry);
        ModelCallJob wrongOperation = evaluationJobCommand(ownerId, profile.id(),
                ModelPurpose.EVALUATION, ModelOperation.EMBEDDING,
                runId, EvaluationRunCreationService.WORKFLOW_STEP_ID,
                EvaluationRunCreationService.WORKFLOW_VERSION, expiry);
        ModelCallJob wrongWorkflowId = evaluationJobCommand(ownerId, profile.id(),
                ModelPurpose.EVALUATION, ModelOperation.TEXT_GENERATION,
                UUID.randomUUID(), EvaluationRunCreationService.WORKFLOW_STEP_ID,
                EvaluationRunCreationService.WORKFLOW_VERSION, expiry);
        ModelCallJob wrongStepId = evaluationJobCommand(ownerId, profile.id(),
                ModelPurpose.EVALUATION, ModelOperation.TEXT_GENERATION,
                runId, "OTHER_STEP", EvaluationRunCreationService.WORKFLOW_VERSION, expiry);
        ModelCallJob wrongVersion = evaluationJobCommand(ownerId, profile.id(),
                ModelPurpose.EVALUATION, ModelOperation.TEXT_GENERATION,
                runId, EvaluationRunCreationService.WORKFLOW_STEP_ID, 1L, expiry);
        ModelCallJob foreignOwnerJob = evaluationJobCommand(otherUserId, otherProfile.id(),
                ModelPurpose.EVALUATION, ModelOperation.TEXT_GENERATION,
                runId, EvaluationRunCreationService.WORKFLOW_STEP_ID,
                EvaluationRunCreationService.WORKFLOW_VERSION, expiry);

        for (ModelCallJob job : List.of(
                wrongPurpose, wrongOperation, wrongWorkflowId, wrongStepId, wrongVersion, foreignOwnerJob)) {
            assertThatThrownBy(() -> evaluationRunRepository.insertOwned(
                    runId, sessionId, job.id(), ownerId, profile.id(),
                    EvaluationRunCreationService.WORKFLOW_VERSION))
                    .as("job %s must be rejected by the insert gate", job.id())
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(evaluationRunCount(sessionId)).isZero();

        // 正确创建期 identity 的 Job 通过 gate。
        ModelCallJob correctJob = evaluationJobCommand(ownerId, profile.id(),
                ModelPurpose.EVALUATION, ModelOperation.TEXT_GENERATION,
                runId, EvaluationRunCreationService.WORKFLOW_STEP_ID,
                EvaluationRunCreationService.WORKFLOW_VERSION, expiry);
        EvaluationRun run = evaluationRunRepository.insertOwned(
                runId, sessionId, correctJob.id(), ownerId, profile.id(),
                EvaluationRunCreationService.WORKFLOW_VERSION);
        assertThat(run.modelCallJobId()).isEqualTo(correctJob.id());
        assertThat(evaluationRunCount(sessionId)).isEqualTo(1);
    }

    // --- helpers ---

    private UUID newUser() {
        UUID userId = userRepository.create();
        createdUserIds.add(userId);
        return userId;
    }

    private int evaluationRunCount(UUID sessionId) {
        sqlSession.clearCache();
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM evaluation_run WHERE session_id = ?", Integer.class, sessionId);
    }

    private int jobCount(UUID userId) {
        sqlSession.clearCache();
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM model_call_job WHERE user_id = ?", Integer.class, userId);
    }

    /** Reader 只为 completed Session 产出 Ready；这里用真实 durable 流程完成一个 cafe session。 */
    private Ready readyInput(UUID profileId, UserContext user, UUID sessionId) {
        GroundedEvaluationInputResult result = reader.readOwned(profileId, sessionId, user);
        assertThat(result).isInstanceOf(Ready.class);
        return (Ready) result;
    }

    private UUID completeCafeSession(UUID profileId, UserContext user) {
        UUID sessionId = startCafeSession(profileId, user);
        assertThat(practiceService.submit(profileId, sessionId, "ask-price", user, "How much is it?"))
                .isInstanceOf(SubmitResult.Accepted.class);
        assertThat(practiceService.submit(profileId, sessionId, "answer-to-go", user,
                "To go, please. Thank you!")).isInstanceOf(SubmitResult.Accepted.class);
        assertThat(practiceService.complete(profileId, sessionId, user))
                .isInstanceOf(CompletionResult.Created.class);
        return sessionId;
    }

    private UUID startCafeSession(UUID profileId, UserContext user) {
        LearningTaskPlanningResult planningResult = planningService.plan(
                profileId, user,
                new LearningTaskPlanningService.PlanningCommand("zh-cn", "FOUNDATION", 10));
        assertThat(planningResult).isInstanceOf(Created.class);
        LearningTask task = ((Created) planningResult).task();

        StartResult startResult = practiceService.start(profileId, task.id(), user);
        assertThat(startResult).isInstanceOf(StartResult.Created.class);
        UUID sessionId = ((StartResult.Created) startResult).session().id();

        assertThat(practiceService.submit(profileId, sessionId, "order-drink", user,
                "Could I have a medium coffee, please?")).isInstanceOf(SubmitResult.Accepted.class);
        return sessionId;
    }

    // 以下 fixture 只为满足 GroundedEvaluationInput 的非空构造约束；Service 只读取
    // userId / languageProfileId / session.id / task.id，不使用这些对象的内容。
    private static LearningTask task(UUID taskId, UUID userId, UUID profileId) {
        return new LearningTask(
                taskId, userId, profileId,
                new MaterialIdentity("en-builtin-cafe-request", "v1"),
                "en", "zh-cn", MaterialDifficulty.FOUNDATION, 10,
                "CAFE_SIMPLE_REQUEST", "objective",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK,
                LearningTask.Status.COMPLETED,
                OffsetDateTime.parse("2026-09-07T10:10:30Z"),
                Optional.of(OffsetDateTime.parse("2026-09-07T10:15:30Z")),
                Optional.of(OffsetDateTime.parse("2026-09-07T10:22:00Z")));
    }

    private static DeterministicAssessment assessment() {
        return new DeterministicAssessment(
                UUID.randomUUID(), DeterministicTextAssessmentPolicy.ASSESSMENT_POLICY_VERSION,
                390L, OffsetDateTime.parse("2026-09-07T10:22:00Z"),
                List.of(new StepResult("order-drink", StepKind.EXACT, StepOutcome.MATCHED)));
    }

    private static List<LearnerResponse> responses() {
        return List.of(new LearnerResponse(
                UUID.randomUUID(), "order-drink", "Could I have a medium coffee, please?",
                OffsetDateTime.parse("2026-09-07T10:18:00Z")));
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
