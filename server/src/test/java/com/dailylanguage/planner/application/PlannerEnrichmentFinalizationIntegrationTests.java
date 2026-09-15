package com.dailylanguage.planner.application;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.languageprofile.infrastructure.LanguageProfileRepository;
import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.infrastructure.ModelCallJobRepository;
import com.dailylanguage.modelgateway.result.ModelFailure;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.planner.domain.PlanningCandidateSet;
import com.dailylanguage.planner.domain.PlanningRequest;
import com.dailylanguage.planner.domain.PlanningResult;
import com.dailylanguage.planner.domain.PlanningRun;
import com.dailylanguage.planner.infrastructure.LearningTaskRepository;
import com.dailylanguage.security.domain.UserContext;
import com.dailylanguage.user.infrastructure.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * 用真实 PostgreSQL durable 数据验证 S9E2B：绑定 Job 的 durable 结果在单一事务、Run 行锁下被
 * 裁决为 exactly-one LearningTask 与 terminal Run outcome——Job consumption、task 创建与 Run
 * finalize 三者原子提交或回滚；四类 fallback、迟到成功 STALE 记账、terminal replay 与并发竞争
 * 都以数据库行状态为准断言。fixture 数据先于服务调用提交，AfterEach 按 FK 依赖顺序清理。
 */
@SpringBootTest(properties = "app.registration-enabled=true")
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class PlannerEnrichmentFinalizationIntegrationTests {

    /** snapshot index 0 = deterministic fallback（真实 builtin material）；index 1 = enrichment 目标。 */
    private static final MaterialIdentity CAFE_V2 =
            new MaterialIdentity("en-builtin-cafe-request", "v2");
    private static final MaterialIdentity GREETING_V1 =
            new MaterialIdentity("en-builtin-greeting-intro", "v1");
    private static final String ENRICHED_REASON = "今天先练习问候与自我介绍。";
    private static final String VALID_ENRICHMENT_JSON = """
            {"materialId":"en-builtin-greeting-intro","publishedVersion":"v1","recommendationReason":"%s"}"""
            .formatted(ENRICHED_REASON);
    private static final String UNKNOWN_CANDIDATE_JSON = """
            {"materialId":"en-builtin-not-offered","publishedVersion":"v1","recommendationReason":"任意理由"}""";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LanguageProfileRepository languageProfileRepository;

    @Autowired
    private PlanningRunCreationService planningRunCreationService;

    @Autowired
    private PlannerEnrichmentFinalizationService finalizationService;

    @Autowired
    private ModelCallJobRepository modelCallJobRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SqlSessionTemplate sqlSession;

    @MockitoSpyBean
    private LearningTaskRepository learningTaskRepository;

    private final List<UUID> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanupCreatedUsers() {
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("""
                    DELETE FROM planning_run_candidate WHERE run_id IN (
                        SELECT id FROM planning_run WHERE user_id = ?)""", userId);
            jdbcTemplate.update("DELETE FROM planning_run WHERE user_id = ?", userId);
            jdbcTemplate.update("""
                    DELETE FROM model_call_text_generation_result WHERE job_id IN (
                        SELECT id FROM model_call_job WHERE user_id = ?)""", userId);
            jdbcTemplate.update("DELETE FROM model_call_job WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM learning_task WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM language_profile WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
        createdUserIds.clear();
    }

    @Test
    void appliesValidSucceededResultAsEnrichedTaskAtomically() {
        Prepared prepared = prepareSucceededRun(VALID_ENRICHMENT_JSON);

        PlannerEnrichmentFinalizationService.FinalizationResult result = finalizationService.finalizeRun(
                prepared.runId(), prepared.request(), terminalAwait(prepared), prepared.user());

        assertThat(result).isInstanceOfSatisfying(
                PlannerEnrichmentFinalizationService.FinalizationResult.Created.class, created -> {
                    LearningTask task = created.task();
                    assertThat(task.planningReason())
                            .isEqualTo(LearningTaskPlan.PlanningReason.MODEL_ENRICHED);
                    assertThat(task.materialIdentity()).isEqualTo(GREETING_V1);
                    assertThat(task.recommendationReason()).contains(ENRICHED_REASON);
                    assertThat(task.supportLanguage()).isEqualTo("zh-cn");
                    assertThat(task.estimatedDurationMinutes()).isEqualTo(7);
                    assertThat(task.status()).isEqualTo(LearningTask.Status.PLANNED);
                    assertThat(created.run().status()).isEqualTo(PlanningRun.Status.MODEL_APPLIED);
                });
        // Job、Task、Run 三者原子配对：CONSUMED + MODEL_APPLIED + exactly-one task。
        assertThat(consumptionStatus(prepared.jobId())).isEqualTo("CONSUMED");
        Map<String, Object> runRow = runRow(prepared.runId());
        assertThat(runRow.get("status")).isEqualTo("MODEL_APPLIED");
        assertThat(runRow.get("fallback_reason")).isNull();
        assertThat(runRow.get("workflow_version")).isEqualTo(0L);
        assertThat(taskCount(prepared.ownerId())).isEqualTo(1);
        assertThat(taskRow(runTaskId(prepared.runId())).get("planning_reason"))
                .isEqualTo("MODEL_ENRICHED");
    }

    @Test
    void terminalModelFailureFallsBackToDeterministicCandidate() {
        Prepared prepared = preparePendingRun();
        failBoundJob(prepared);

        PlannerEnrichmentFinalizationService.FinalizationResult result = finalizationService.finalizeRun(
                prepared.runId(), prepared.request(), terminalAwait(prepared), prepared.user());

        assertThat(result).isInstanceOfSatisfying(
                PlannerEnrichmentFinalizationService.FinalizationResult.Created.class, created -> {
                    assertThat(created.task().planningReason())
                            .isEqualTo(LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK);
                    assertThat(created.task().materialIdentity()).isEqualTo(CAFE_V2);
                    assertThat(created.task().recommendationReason()).isEmpty();
                    assertThat(created.run().status()).isEqualTo(PlanningRun.Status.FALLBACK_APPLIED);
                });
        Map<String, Object> runRow = runRow(prepared.runId());
        assertThat(runRow.get("status")).isEqualTo("FALLBACK_APPLIED");
        assertThat(runRow.get("fallback_reason")).isEqualTo("MODEL_CALL_FAILED");
        assertThat(runRow.get("workflow_version")).isEqualTo(1L);
        assertThat(taskRow(runTaskId(prepared.runId())).get("material_id"))
                .isEqualTo("en-builtin-cafe-request");
        assertThat(consumptionStatus(prepared.jobId())).isEqualTo("NOT_READY");
        assertThat(taskCount(prepared.ownerId())).isEqualTo(1);
    }

    @Test
    void rejectedOutputConsumesJobAndFallsBack() {
        Prepared prepared = prepareSucceededRun(UNKNOWN_CANDIDATE_JSON);

        PlannerEnrichmentFinalizationService.FinalizationResult result = finalizationService.finalizeRun(
                prepared.runId(), prepared.request(), terminalAwait(prepared), prepared.user());

        assertThat(result).isInstanceOfSatisfying(
                PlannerEnrichmentFinalizationService.FinalizationResult.Created.class, created ->
                        assertThat(created.run().status()).isEqualTo(PlanningRun.Status.FALLBACK_APPLIED));
        Map<String, Object> runRow = runRow(prepared.runId());
        assertThat(runRow.get("fallback_reason")).isEqualTo("MODEL_OUTPUT_REJECTED");
        assertThat(runRow.get("workflow_version")).isEqualTo(1L);
        // rejected output 的 Job 仍被消费，不允许同一 result 再次进入裁决。
        assertThat(consumptionStatus(prepared.jobId())).isEqualTo("CONSUMED");
        assertThat(taskRow(runTaskId(prepared.runId())).get("planning_reason"))
                .isEqualTo("DETERMINISTIC_BUILT_IN_FALLBACK");
        assertThat(taskCount(prepared.ownerId())).isEqualTo(1);
    }

    @Test
    void expiredSucceededResultIsMarkedExpiredAndFallsBack() {
        Prepared prepared = prepareSucceededRun(VALID_ENRICHMENT_JSON);
        jdbcTemplate.update("""
                UPDATE model_call_job
                SET created_at = CURRENT_TIMESTAMP - INTERVAL '3 seconds',
                    completed_at = CURRENT_TIMESTAMP - INTERVAL '2 seconds',
                    expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """, prepared.jobId());
        sqlSession.clearCache();

        PlannerEnrichmentFinalizationService.FinalizationResult result = finalizationService.finalizeRun(
                prepared.runId(), prepared.request(), terminalAwait(prepared), prepared.user());

        assertThat(result).isInstanceOfSatisfying(
                PlannerEnrichmentFinalizationService.FinalizationResult.Created.class, created ->
                        assertThat(created.run().status()).isEqualTo(PlanningRun.Status.FALLBACK_APPLIED));
        assertThat(runRow(prepared.runId()).get("fallback_reason")).isEqualTo("MODEL_RESULT_UNAVAILABLE");
        // 过期由数据库 CURRENT_TIMESTAMP 裁决：Job 被标记 EXPIRED，不创建 enriched task。
        assertThat(consumptionStatus(prepared.jobId())).isEqualTo("EXPIRED");
        assertThat(taskRow(runTaskId(prepared.runId())).get("planning_reason"))
                .isEqualTo("DETERMINISTIC_BUILT_IN_FALLBACK");
        assertThat(taskCount(prepared.ownerId())).isEqualTo(1);
    }

    @Test
    void budgetExhaustedWithLateSuccessFallsBackAndMarksJobStale() {
        Prepared prepared = prepareSucceededRun(VALID_ENRICHMENT_JSON);

        PlannerEnrichmentFinalizationService.FinalizationResult result = finalizationService.finalizeRun(
                prepared.runId(), prepared.request(),
                new PlannerEnrichmentJobAwaiter.AwaitResult.BudgetExhausted(), prepared.user());

        assertThat(result).isInstanceOfSatisfying(
                PlannerEnrichmentFinalizationService.FinalizationResult.Created.class, created -> {
                    assertThat(created.task().materialIdentity()).isEqualTo(CAFE_V2);
                    assertThat(created.run().status()).isEqualTo(PlanningRun.Status.FALLBACK_APPLIED);
                });
        Map<String, Object> runRow = runRow(prepared.runId());
        assertThat(runRow.get("fallback_reason")).isEqualTo("WAIT_BUDGET_EXHAUSTED");
        assertThat(runRow.get("workflow_version")).isEqualTo(1L);
        // 迟到的成功结果不被应用：Job 被推进后的 workflow version 标记 STALE。
        assertThat(consumptionStatus(prepared.jobId())).isEqualTo("STALE");
        assertThat(taskRow(runTaskId(prepared.runId())).get("planning_reason"))
                .isEqualTo("DETERMINISTIC_BUILT_IN_FALLBACK");
        assertThat(taskCount(prepared.ownerId())).isEqualTo(1);
    }

    @Test
    void budgetExhaustedWhileJobStillRunningFallsBackWithoutJobMutation() {
        Prepared prepared = preparePendingRun();

        PlannerEnrichmentFinalizationService.FinalizationResult result = finalizationService.finalizeRun(
                prepared.runId(), prepared.request(),
                new PlannerEnrichmentJobAwaiter.AwaitResult.BudgetExhausted(), prepared.user());

        assertThat(result).isInstanceOfSatisfying(
                PlannerEnrichmentFinalizationService.FinalizationResult.Created.class, created ->
                        assertThat(created.run().status()).isEqualTo(PlanningRun.Status.FALLBACK_APPLIED));
        assertThat(runRow(prepared.runId()).get("fallback_reason")).isEqualTo("WAIT_BUDGET_EXHAUSTED");
        // 仍在执行的 Job 不做任何 consumption 记账。
        assertThat(consumptionStatus(prepared.jobId())).isEqualTo("NOT_READY");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT execution_status FROM model_call_job WHERE id = ?", String.class, prepared.jobId()))
                .isEqualTo("CREATED");
        assertThat(taskCount(prepared.ownerId())).isEqualTo(1);
    }

    @Test
    void replayOfTerminalRunReturnsExistingBoundTaskWithoutNewWrites() {
        Prepared prepared = prepareSucceededRun(VALID_ENRICHMENT_JSON);
        PlannerEnrichmentFinalizationService.FinalizationResult first = finalizationService.finalizeRun(
                prepared.runId(), prepared.request(), terminalAwait(prepared), prepared.user());
        UUID boundTaskId = ((PlannerEnrichmentFinalizationService.FinalizationResult.Created) first)
                .task().id();
        long runRowVersionAfterFirst = (long) runRow(prepared.runId()).get("row_version");

        PlannerEnrichmentFinalizationService.FinalizationResult replay = finalizationService.finalizeRun(
                prepared.runId(), prepared.request(),
                new PlannerEnrichmentJobAwaiter.AwaitResult.BudgetExhausted(), prepared.user());

        assertThat(replay).isInstanceOfSatisfying(
                PlannerEnrichmentFinalizationService.FinalizationResult.Existing.class, existing -> {
                    assertThat(existing.task().id()).isEqualTo(boundTaskId);
                    assertThat(existing.run().status()).isEqualTo(PlanningRun.Status.MODEL_APPLIED);
                });
        assertThat(taskCount(prepared.ownerId())).isEqualTo(1);
        assertThat((long) runRow(prepared.runId()).get("row_version")).isEqualTo(runRowVersionAfterFirst);
        assertThat(consumptionStatus(prepared.jobId())).isEqualTo("CONSUMED");
    }

    @Test
    void concurrentFinalizationCreatesExactlyOneTask() throws Exception {
        Prepared prepared = prepareSucceededRun(VALID_ENRICHMENT_JSON);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<PlannerEnrichmentFinalizationService.FinalizationResult> finalization = () -> {
                start.await();
                return finalizationService.finalizeRun(
                        prepared.runId(), prepared.request(), terminalAwait(prepared), prepared.user());
            };
            Future<PlannerEnrichmentFinalizationService.FinalizationResult> first =
                    executor.submit(finalization);
            Future<PlannerEnrichmentFinalizationService.FinalizationResult> second =
                    executor.submit(finalization);
            start.countDown();
            PlannerEnrichmentFinalizationService.FinalizationResult firstResult =
                    first.get(30, TimeUnit.SECONDS);
            PlannerEnrichmentFinalizationService.FinalizationResult secondResult =
                    second.get(30, TimeUnit.SECONDS);

            // Run 行锁串行化：恰一个 Created + 一个 Existing，且绑定同一 durable task。
            PlannerEnrichmentFinalizationService.FinalizationResult.Created created =
                    firstResult instanceof PlannerEnrichmentFinalizationService.FinalizationResult.Created
                            ? (PlannerEnrichmentFinalizationService.FinalizationResult.Created) firstResult
                            : (PlannerEnrichmentFinalizationService.FinalizationResult.Created) secondResult;
            PlannerEnrichmentFinalizationService.FinalizationResult.Existing existing =
                    firstResult instanceof PlannerEnrichmentFinalizationService.FinalizationResult.Created
                            ? (PlannerEnrichmentFinalizationService.FinalizationResult.Existing) secondResult
                            : (PlannerEnrichmentFinalizationService.FinalizationResult.Existing) firstResult;
            assertThat(created.task().id()).isEqualTo(existing.task().id());
            assertThat(taskCount(prepared.ownerId())).isEqualTo(1);
            assertThat(runRow(prepared.runId()).get("status")).isEqualTo("MODEL_APPLIED");
            assertThat(consumptionStatus(prepared.jobId())).isEqualTo("CONSUMED");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void consumedJobWithPendingRunFailsClosed() {
        Prepared prepared = prepareSucceededRun(VALID_ENRICHMENT_JSON);
        jdbcTemplate.update(
                "UPDATE model_call_job SET consumption_status = 'CONSUMED', row_version = row_version + 1"
                        + " WHERE id = ?", prepared.jobId());
        sqlSession.clearCache();

        assertThatThrownBy(() -> finalizationService.finalizeRun(
                prepared.runId(), prepared.request(), terminalAwait(prepared), prepared.user()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planning job is already consumed while its run is still pending");
        assertThat(runRow(prepared.runId()).get("status")).isEqualTo("PENDING");
        assertThat(taskCount(prepared.ownerId())).isZero();
    }

    @Test
    void taskCreationFailureRollsBackJobConsumption() {
        Prepared prepared = prepareSucceededRun(VALID_ENRICHMENT_JSON);
        doThrow(new IllegalStateException("task insert failed"))
                .when(learningTaskRepository)
                .createOwned(any(), any());

        assertThatThrownBy(() -> finalizationService.finalizeRun(
                prepared.runId(), prepared.request(), terminalAwait(prepared), prepared.user()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("task insert failed");

        // 核心保证：Job 的 CONSUMED 与 task / Run outcome 原子提交——task 写入失败时消费一并回滚。
        sqlSession.clearCache();
        assertThat(consumptionStatus(prepared.jobId())).isEqualTo("NOT_READY");
        assertThat(runRow(prepared.runId()).get("status")).isEqualTo("PENDING");
        assertThat(taskCount(prepared.ownerId())).isZero();
    }

    @Test
    void wrongOwnerOrForeignProfileOrUnknownRunIsNotFoundOrRejected() {
        Prepared prepared = preparePendingRun();

        // 非绑定 profile owner 的 caller：请求与 UserContext 不一致属于 programming error。
        assertThatThrownBy(() -> finalizationService.finalizeRun(
                prepared.runId(), prepared.request(), terminalAwait(prepared),
                new UserContext(UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("userContext must match the planning request language profile owner");
        // 同一 user 的另一 profile：Run 对该 owner/profile 组合不可见。
        LanguageProfileIdentity otherProfile =
                languageProfileRepository.create(prepared.ownerId(), "ja").orElseThrow();
        PlanningRequest foreignRequest = new PlanningRequest(
                otherProfile, "zh-cn", MaterialDifficulty.FOUNDATION, 7, Set.of());
        assertThat(finalizationService.finalizeRun(
                prepared.runId(), foreignRequest, terminalAwait(prepared), prepared.user()))
                .isEqualTo(new PlannerEnrichmentFinalizationService.FinalizationResult.NotFound());
        // 不存在的 Run。
        assertThat(finalizationService.finalizeRun(
                UUID.randomUUID(), prepared.request(), terminalAwait(prepared), prepared.user()))
                .isEqualTo(new PlannerEnrichmentFinalizationService.FinalizationResult.NotFound());

        assertThat(runRow(prepared.runId()).get("status")).isEqualTo("PENDING");
        assertThat(taskCount(prepared.ownerId())).isZero();
    }

    @Test
    void unresolvableSnapshotMemberReturnsUnavailableAndLeavesRunPending() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = newProfile(ownerId, "en");
        // snapshot 引用真实 catalog 不存在的 material：创建期不校验 Content，finalization 时 fail closed。
        PlanningCandidateSet brokenSet = new PlanningCandidateSet(List.of(
                deterministicPlan(profile.id(), CAFE_V2),
                deterministicPlan(profile.id(),
                        new MaterialIdentity("en-builtin-not-published", "v1"))));
        PlanningRunCreationService.Created created = planningRunCreationService.create(
                new UserContext(ownerId), brokenSet, OffsetDateTime.now().plusMinutes(5));

        PlannerEnrichmentFinalizationService.FinalizationResult result = finalizationService.finalizeRun(
                created.run().id(), request(profile), terminalAwait(created.job().id(), ownerId),
                new UserContext(ownerId));

        assertThat(result).isEqualTo(new PlannerEnrichmentFinalizationService.FinalizationResult.Unavailable(
                PlanningResult.UnavailableReason.SELECTED_MATERIAL_UNAVAILABLE));
        assertThat(runRow(created.run().id()).get("status")).isEqualTo("PENDING");
        assertThat(taskCount(ownerId)).isZero();
    }

    @Test
    void budgetExhaustedWithConsumedJobFailsClosed() {
        Prepared prepared = prepareSucceededRun(VALID_ENRICHMENT_JSON);
        jdbcTemplate.update(
                "UPDATE model_call_job SET consumption_status = 'CONSUMED', row_version = row_version + 1"
                        + " WHERE id = ?", prepared.jobId());
        sqlSession.clearCache();

        assertThatThrownBy(() -> finalizationService.finalizeRun(
                prepared.runId(), prepared.request(),
                new PlannerEnrichmentJobAwaiter.AwaitResult.BudgetExhausted(), prepared.user()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planning job is already consumed while its run is still pending");
        // budget fallback 不得绕过 consumption invariant：Run 保持 PENDING，不创建任何 task。
        assertThat(runRow(prepared.runId()).get("status")).isEqualTo("PENDING");
        assertThat(taskCount(prepared.ownerId())).isZero();
    }

    // --- helpers ---

    private record Prepared(
            UUID ownerId,
            LanguageProfileIdentity profile,
            PlanningRequest request,
            PlanningRun run,
            UUID jobId,
            UserContext user) {

        private UUID runId() {
            return run.id();
        }
    }

    private Prepared preparePendingRun() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = newProfile(ownerId, "en");
        PlanningCandidateSet candidateSet = new PlanningCandidateSet(List.of(
                deterministicPlan(profile.id(), CAFE_V2),
                deterministicPlan(profile.id(), GREETING_V1)));
        PlanningRunCreationService.Created created = planningRunCreationService.create(
                new UserContext(ownerId), candidateSet, OffsetDateTime.now().plusMinutes(5));
        return new Prepared(
                ownerId, profile, request(profile), created.run(), created.job().id(),
                new UserContext(ownerId));
    }

    private Prepared prepareSucceededRun(String generatedJson) {
        Prepared prepared = preparePendingRun();
        ModelCallJob created = modelCallJobRepository
                .findByIdAndUserId(prepared.jobId(), prepared.ownerId()).orElseThrow();
        ModelCallJob running = modelCallJobRepository
                .tryStartExecution(created.id(), prepared.ownerId(), created.rowVersion())
                .orElseThrow();
        TextGenerationResponse response = new TextGenerationResponse(
                new ProviderId("deepseek"),
                new ModelId("deepseek-chat"),
                generatedJson,
                TextGenerationResponse.FinishReason.COMPLETED,
                Optional.empty());
        modelCallJobRepository.tryRecordTextGenerationSuccess(
                running.id(), prepared.ownerId(), running.rowVersion(), response).orElseThrow();
        return prepared;
    }

    private void failBoundJob(Prepared prepared) {
        ModelCallJob created = modelCallJobRepository
                .findByIdAndUserId(prepared.jobId(), prepared.ownerId()).orElseThrow();
        ModelCallJob running = modelCallJobRepository
                .tryStartExecution(created.id(), prepared.ownerId(), created.rowVersion())
                .orElseThrow();
        modelCallJobRepository.tryRecordFailure(
                running.id(), prepared.ownerId(), running.rowVersion(),
                ModelFailure.withoutRoute(ModelFailureKind.PROVIDER_FAILURE)).orElseThrow();
    }

    private PlannerEnrichmentJobAwaiter.AwaitResult.Terminal terminalAwait(Prepared prepared) {
        ModelCallJob bound = modelCallJobRepository
                .findByIdAndUserId(prepared.jobId(), prepared.ownerId()).orElseThrow();
        return new PlannerEnrichmentJobAwaiter.AwaitResult.Terminal(bound);
    }

    private PlannerEnrichmentJobAwaiter.AwaitResult.Terminal terminalAwait(UUID jobId, UUID ownerId) {
        ModelCallJob bound = modelCallJobRepository.findByIdAndUserId(jobId, ownerId).orElseThrow();
        return new PlannerEnrichmentJobAwaiter.AwaitResult.Terminal(bound);
    }

    private static PlanningRequest request(LanguageProfileIdentity profile) {
        return new PlanningRequest(
                profile, "zh-cn", MaterialDifficulty.FOUNDATION, 7, Set.of());
    }

    private static LearningTaskPlan deterministicPlan(UUID profileId, MaterialIdentity identity) {
        return new LearningTaskPlan(
                profileId,
                identity,
                "en",
                "zh-cn",
                MaterialDifficulty.FOUNDATION,
                7,
                "SCENARIO",
                "Goal for SCENARIO",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK);
    }

    private UUID newUser() {
        UUID userId = userRepository.create();
        createdUserIds.add(userId);
        return userId;
    }

    private LanguageProfileIdentity newProfile(UUID ownerId, String languageCode) {
        return languageProfileRepository.create(ownerId, languageCode).orElseThrow();
    }

    private Map<String, Object> runRow(UUID runId) {
        return jdbcTemplate.queryForMap("""
                SELECT status, fallback_reason, learning_task_id, workflow_version, row_version
                FROM planning_run WHERE id = ?""", runId);
    }

    private UUID runTaskId(UUID runId) {
        return jdbcTemplate.queryForObject(
                "SELECT learning_task_id FROM planning_run WHERE id = ?", UUID.class, runId);
    }

    private String consumptionStatus(UUID jobId) {
        return jdbcTemplate.queryForObject(
                "SELECT consumption_status FROM model_call_job WHERE id = ?", String.class, jobId);
    }

    private Map<String, Object> taskRow(UUID taskId) {
        return jdbcTemplate.queryForMap("""
                SELECT material_id, published_version, planning_reason, recommendation_reason
                FROM learning_task WHERE id = ?""", taskId);
    }

    private int taskCount(UUID ownerId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM learning_task WHERE user_id = ?", Integer.class, ownerId);
        return count == null ? 0 : count;
    }
}
