package com.dailylanguage.planner.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.languageprofile.infrastructure.LanguageProfileRepository;
import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.domain.NewModelCallJob;
import com.dailylanguage.modelcalljob.infrastructure.ModelCallJobRepository;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.planner.domain.PlanningCandidateSet;
import com.dailylanguage.planner.domain.PlanningRun;
import com.dailylanguage.planner.infrastructure.PlanningRunRepository;
import com.dailylanguage.security.domain.UserContext;
import com.dailylanguage.user.infrastructure.UserRepository;

/**
 * 用真实 PostgreSQL durable 数据验证 S9C2：PlanningRun、ordered candidate snapshot 与唯一
 * PLANNING ModelCallJob 在一个 REQUIRES_NEW 事务内原子创建；任一环节失败整体回滚，不留 orphan
 * 或 partial snapshot。fixture 与服务提交都发生在测试管理事务之外（服务自身 REQUIRES_NEW），
 * 因此本测试不使用 @Transactional，改在 AfterEach 按 FK 依赖顺序清理。
 */
@SpringBootTest(properties = "app.registration-enabled=true")
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class PlanningRunCreationIntegrationTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LanguageProfileRepository languageProfileRepository;

    @Autowired
    private PlanningRunCreationService service;

    @Autowired
    private PlanningRunRepository planningRunRepository;

    @Autowired
    private ModelCallJobRepository modelCallJobRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanupCreatedUsers() {
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("""
                    DELETE FROM planning_run_candidate WHERE run_id IN (
                        SELECT id FROM planning_run WHERE user_id = ?)""", userId);
            jdbcTemplate.update("DELETE FROM planning_run WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM model_call_job WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM language_profile WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
        createdUserIds.clear();
    }

    @Test
    void createsRunJobAndOrderedCandidatesAtomically() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();
        PlanningCandidateSet candidateSet = candidateSet(profile.id(), 3);
        // 创建与断言必须引用同一 expiry 值。
        OffsetDateTime expiresAt = resultExpiresAt();

        PlanningRunCreationService.Created created =
                service.create(new UserContext(ownerId), candidateSet, expiresAt);

        PlanningRun run = created.run();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1",
                String.class)).isEqualTo("16");
        assertThat(run.id().version()).isEqualTo(7);
        assertThat(run.userId()).isEqualTo(ownerId);
        assertThat(run.languageProfileId()).isEqualTo(profile.id());
        assertThat(run.modelCallJobId()).isEqualTo(created.job().id());
        assertThat(run.status()).isEqualTo(PlanningRun.Status.PENDING);
        assertThat(run.workflowVersion()).isZero();
        assertThat(run.rowVersion()).isZero();
        assertThat(run.completedAt()).isEmpty();

        // Job 保持 route-unresolved 创建期 identity；CREATED / NOT_READY 证明本 slice 未 dispatch。
        ModelCallJob job = modelCallJobRepository
                .findByIdAndUserId(created.job().id(), ownerId)
                .orElseThrow();
        assertThat(job.modelPurpose()).isEqualTo(ModelPurpose.PLANNING);
        assertThat(job.modelOperation()).isEqualTo(ModelOperation.TEXT_GENERATION);
        assertThat(job.providerId()).isEmpty();
        assertThat(job.modelId()).isEmpty();
        assertThat(job.workflowId()).isEqualTo(run.id());
        assertThat(job.workflowStepId()).isEqualTo(PlanningRunCreationService.WORKFLOW_STEP_ID);
        assertThat(job.workflowVersion()).isZero();
        assertThat(job.executionStatus()).isEqualTo(ModelCallJob.ExecutionStatus.CREATED);
        assertThat(job.consumptionStatus()).isEqualTo(ModelCallJob.ConsumptionStatus.NOT_READY);
        assertThat(job.rowVersion()).isZero();
        assertThat(job.expiresAt().toInstant()).isEqualTo(expiresAt.toInstant());
        assertThat(job.completedAt()).isEmpty();

        assertThat(planningRunRepository.findOwned(run.id(), ownerId, profile.id()))
                .contains(run);
        // durable reread 重建 Snapshot：读取侧重新裁决 non-empty、dense ordered、unique。
        List<PlanningRun.Candidate> rereadCandidates = planningRunRepository
                .findOwnedSnapshot(run.id(), ownerId, profile.id())
                .orElseThrow()
                .candidates();
        assertThat(rereadCandidates)
                .extracting(PlanningRun.Candidate::index)
                .containsExactly(0, 1, 2);
        assertThat(rereadCandidates)
                .extracting(PlanningRun.Candidate::identity)
                .containsExactly(identity(0), identity(1), identity(2));
        assertThat(planningRunRepository.findOwnedSnapshot(run.id(), ownerId, profile.id()))
                .contains(created.snapshot());
    }

    @Test
    void concurrentCreationsProduceIndependentRunsWithoutCrossLink() throws Exception {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();
        UserContext user = new UserContext(ownerId);
        PlanningCandidateSet firstSet = candidateSet(profile.id(), 1);
        PlanningCandidateSet secondSet = candidateSet(profile.id(), 2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<PlanningRunCreationService.Created> firstCreation = () -> {
                start.await();
                return service.create(user, firstSet, resultExpiresAt());
            };
            Callable<PlanningRunCreationService.Created> secondCreation = () -> {
                start.await();
                return service.create(user, secondSet, resultExpiresAt());
            };
            Future<PlanningRunCreationService.Created> first = executor.submit(firstCreation);
            Future<PlanningRunCreationService.Created> second = executor.submit(secondCreation);
            start.countDown();
            PlanningRunCreationService.Created firstCreated = first.get(30, TimeUnit.SECONDS);
            PlanningRunCreationService.Created secondCreated = second.get(30, TimeUnit.SECONDS);

            // 同一 Profile 的并发创建产生两个完整且互不交叉的 Run/Job/snapshot；
            // 每个 Job 的 workflowId 都精确绑定自己的 Run。
            assertThat(firstCreated.run().id()).isNotEqualTo(secondCreated.run().id());
            assertThat(firstCreated.job().id()).isNotEqualTo(secondCreated.job().id());
            assertThat(firstCreated.job().workflowId()).isEqualTo(firstCreated.run().id());
            assertThat(secondCreated.job().workflowId()).isEqualTo(secondCreated.run().id());
            assertThat(firstCreated.snapshot().candidates()).hasSize(1);
            assertThat(secondCreated.snapshot().candidates()).hasSize(2);

            assertThat(planningRunRepository.findOwned(firstCreated.run().id(), ownerId, profile.id()))
                    .contains(firstCreated.run());
            assertThat(planningRunRepository.findOwned(secondCreated.run().id(), ownerId, profile.id()))
                    .contains(secondCreated.run());
            assertThat(planningRunRepository.findOwnedSnapshot(
                    firstCreated.run().id(), ownerId, profile.id()))
                    .contains(firstCreated.snapshot());
            assertThat(planningRunRepository.findOwnedSnapshot(
                    secondCreated.run().id(), ownerId, profile.id()))
                    .contains(secondCreated.snapshot());
            assertThat(planningRunCount(ownerId)).isEqualTo(2);
            assertThat(jobCount(ownerId)).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void hidesRunFromOtherUserAndOtherLanguageProfile() {
        UUID ownerId = newUser();
        UUID otherUserId = newUser();
        LanguageProfileIdentity englishProfile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();
        LanguageProfileIdentity japaneseProfile = languageProfileRepository
                .create(ownerId, "ja")
                .orElseThrow();
        PlanningRunCreationService.Created created = service.create(
                new UserContext(ownerId), candidateSet(englishProfile.id(), 1), resultExpiresAt());

        assertThat(planningRunRepository.findOwned(
                created.run().id(), otherUserId, englishProfile.id())).isEmpty();
        assertThat(planningRunRepository.findOwned(
                created.run().id(), ownerId, japaneseProfile.id())).isEmpty();
        assertThat(planningRunRepository.findOwnedSnapshot(
                created.run().id(), otherUserId, englishProfile.id())).isEmpty();
        assertThat(planningRunRepository.findOwnedSnapshot(
                created.run().id(), ownerId, japaneseProfile.id())).isEmpty();
        assertThat(planningRunRepository.findOwned(
                created.run().id(), ownerId, englishProfile.id()))
                .contains(created.run());
    }

    @Test
    void jobInsertFailureRollsBackRunWithNoOrphanRows() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();

        // 违反 ck_model_call_job_timestamps（expires_at > created_at）：Job insert 在数据库内失败。
        OffsetDateTime pastExpiry = OffsetDateTime.parse("2020-01-01T00:00:00Z");
        assertThatThrownBy(() -> service.create(
                new UserContext(ownerId), candidateSet(profile.id(), 1), pastExpiry))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(planningRunCount(ownerId)).isZero();
        assertThat(jobCount(ownerId)).isZero();
    }

    @Test
    void candidateInsertFailureRollsBackRunAndJob() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();
        // 第二个 candidate 携带边界 whitespace 的 materialId：通过 Java domain（非 blank），
        // 但被 ck_planning_run_candidate_material_identity 在数据库层拒绝。
        PlanningCandidateSet invalidSet = new PlanningCandidateSet(List.of(
                planFor(profile.id(), identity(0)),
                planFor(profile.id(),
                        new MaterialIdentity(" builtin:text-practice/material-1", "2026.03.1"))));

        assertThatThrownBy(() -> service.create(
                new UserContext(ownerId), invalidSet, resultExpiresAt()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(planningRunCount(ownerId)).isZero();
        assertThat(candidateCount(ownerId)).isZero();
        assertThat(jobCount(ownerId)).isZero();
    }

    @Test
    void insertGateRejectsJobsWithoutFullPlanningWorkflowIdentity() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();
        UUID otherUserId = newUser();
        LanguageProfileIdentity otherProfile = languageProfileRepository
                .create(otherUserId, "en")
                .orElseThrow();

        // Repository 级直测：数据库 insert gate 必须核对 Job 的完整创建期 identity，
        // 任一偏差零行并 fail closed（与上层 Service 无关）。
        UUID runId = planningRunRepository.nextRunId();
        OffsetDateTime expiry = resultExpiresAt();
        ModelCallJob wrongPurpose = planningJobCommand(ownerId, profile.id(),
                ModelPurpose.EVALUATION, ModelOperation.TEXT_GENERATION,
                runId, PlanningRunCreationService.WORKFLOW_STEP_ID,
                PlanningRunCreationService.WORKFLOW_VERSION, expiry);
        ModelCallJob wrongOperation = planningJobCommand(ownerId, profile.id(),
                ModelPurpose.PLANNING, ModelOperation.EMBEDDING,
                runId, PlanningRunCreationService.WORKFLOW_STEP_ID,
                PlanningRunCreationService.WORKFLOW_VERSION, expiry);
        ModelCallJob wrongWorkflowId = planningJobCommand(ownerId, profile.id(),
                ModelPurpose.PLANNING, ModelOperation.TEXT_GENERATION,
                UUID.randomUUID(), PlanningRunCreationService.WORKFLOW_STEP_ID,
                PlanningRunCreationService.WORKFLOW_VERSION, expiry);
        ModelCallJob wrongStepId = planningJobCommand(ownerId, profile.id(),
                ModelPurpose.PLANNING, ModelOperation.TEXT_GENERATION,
                runId, "OTHER_STEP", PlanningRunCreationService.WORKFLOW_VERSION, expiry);
        ModelCallJob wrongVersion = planningJobCommand(ownerId, profile.id(),
                ModelPurpose.PLANNING, ModelOperation.TEXT_GENERATION,
                runId, PlanningRunCreationService.WORKFLOW_STEP_ID, 1L, expiry);
        ModelCallJob foreignOwnerJob = planningJobCommand(otherUserId, otherProfile.id(),
                ModelPurpose.PLANNING, ModelOperation.TEXT_GENERATION,
                runId, PlanningRunCreationService.WORKFLOW_STEP_ID,
                PlanningRunCreationService.WORKFLOW_VERSION, expiry);
        // route-unresolved 违规：已选定 provider/model 的 routed Job 不能创建 Run。
        ModelCallJob routedJob = modelCallJobRepository.create(new NewModelCallJob(
                ownerId, Optional.of(profile.id()), ModelPurpose.PLANNING, ModelOperation.TEXT_GENERATION,
                Optional.of(new ProviderId("openai")), Optional.of(new ModelId("gpt-5.3")),
                runId, PlanningRunCreationService.WORKFLOW_STEP_ID,
                PlanningRunCreationService.WORKFLOW_VERSION, expiry));
        // 创建态违规：已进入 RUNNING 且 rowVersion=1 的 Job 不能创建 Run。
        ModelCallJob createdJob = planningJobCommand(ownerId, profile.id(),
                ModelPurpose.PLANNING, ModelOperation.TEXT_GENERATION,
                runId, PlanningRunCreationService.WORKFLOW_STEP_ID,
                PlanningRunCreationService.WORKFLOW_VERSION, expiry);
        ModelCallJob startedJob = modelCallJobRepository
                .tryStartExecution(createdJob.id(), ownerId, 0L)
                .orElseThrow();
        assertThat(startedJob.executionStatus()).isEqualTo(ModelCallJob.ExecutionStatus.RUNNING);
        assertThat(startedJob.rowVersion()).isEqualTo(1L);

        for (ModelCallJob job : List.of(
                wrongPurpose, wrongOperation, wrongWorkflowId, wrongStepId, wrongVersion,
                foreignOwnerJob, routedJob, startedJob)) {
            assertThatThrownBy(() -> planningRunRepository.insertOwned(
                    runId, job.id(), ownerId, profile.id(),
                    PlanningRunCreationService.WORKFLOW_VERSION))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("planning run insert requires an owned language profile"
                            + " and a planning workflow job with matching creation identity");
        }

        assertThat(planningRunCount(ownerId)).isZero();
    }

    @Test
    void candidateSnapshotRejectsDuplicateIndexAndIdentityAtDatabaseLevel() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();
        PlanningRunCreationService.Created created = service.create(
                new UserContext(ownerId), candidateSet(profile.id(), 1), resultExpiresAt());
        UUID runId = created.run().id();

        // 同一 exact identity 换 index：违反 UNIQUE(run_id, material identity)。
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO planning_run_candidate (run_id, candidate_index, material_id, published_version)"
                        + " VALUES (?, ?, ?, ?)",
                runId, 1, identity(0).materialId(), identity(0).publishedVersion()))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 同一 index 换 identity：违反 PK(run_id, candidate_index)。
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO planning_run_candidate (run_id, candidate_index, material_id, published_version)"
                        + " VALUES (?, ?, ?, ?)",
                runId, 0, "builtin:text-practice/material-9", "2026.03.1"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(candidateCount(ownerId)).isEqualTo(1);
    }

    /** 每次执行时生成，避免固定日期过期后正常创建违反 expires_at > created_at。 */
    private static OffsetDateTime resultExpiresAt() {
        return OffsetDateTime.now().plusDays(7);
    }

    private UUID newUser() {
        UUID userId = userRepository.create();
        createdUserIds.add(userId);
        return userId;
    }

    private ModelCallJob planningJobCommand(
            UUID userId, UUID profileId, ModelPurpose purpose, ModelOperation operation,
            UUID workflowId, String workflowStepId, long workflowVersion, OffsetDateTime expiry) {
        return modelCallJobRepository.create(new NewModelCallJob(
                userId, Optional.of(profileId), purpose, operation,
                Optional.empty(), Optional.empty(), workflowId, workflowStepId, workflowVersion, expiry));
    }

    private static PlanningCandidateSet candidateSet(UUID profileId, int count) {
        List<LearningTaskPlan> plans = new ArrayList<>(count);
        for (int sequence = 0; sequence < count; sequence++) {
            plans.add(planFor(profileId, identity(sequence)));
        }
        return new PlanningCandidateSet(plans);
    }

    private static LearningTaskPlan planFor(UUID profileId, MaterialIdentity identity) {
        return new LearningTaskPlan(
                profileId,
                identity,
                "en",
                "zh",
                MaterialDifficulty.FOUNDATION,
                7,
                "Ordering breakfast at a café",
                "Ask the staff a follow-up question about today's specials",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK);
    }

    private static MaterialIdentity identity(int sequence) {
        return new MaterialIdentity(
                "builtin:text-practice/material-" + sequence, "2026.03.1");
    }

    private long planningRunCount(UUID ownerId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM planning_run WHERE user_id = ?", Long.class, ownerId);
    }

    private long candidateCount(UUID ownerId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM planning_run_candidate
                WHERE run_id IN (SELECT id FROM planning_run WHERE user_id = ?)
                """, Long.class, ownerId);
    }

    private long jobCount(UUID ownerId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM model_call_job WHERE user_id = ?", Long.class, ownerId);
    }
}
