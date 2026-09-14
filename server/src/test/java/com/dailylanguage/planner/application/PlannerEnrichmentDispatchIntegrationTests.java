package com.dailylanguage.planner.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.languageprofile.infrastructure.LanguageProfileRepository;
import com.dailylanguage.modelcalljob.application.TextGenerationJobSubmission.SubmissionOutcome;
import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.infrastructure.ModelCallJobRepository;
import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.result.ModelResult;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationPort;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;
import com.dailylanguage.modelgateway.text.execution.FixedTextGenerationRoutes;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.planner.domain.PlanningCandidateSet;
import com.dailylanguage.planner.domain.PlanningRequest;
import com.dailylanguage.planner.domain.PlanningRun;
import com.dailylanguage.planner.infrastructure.PlanningRunRepository;
import com.dailylanguage.security.domain.UserContext;
import com.dailylanguage.user.infrastructure.UserRepository;

/**
 * 用真实 PostgreSQL durable 数据验证 S9D：dispatch 前 Run / Job / snapshot 已在独立事务提交
 * 并对异步 Worker 可见，Worker 成功认领并写入 durable terminal result。Provider 边界以
 * TextGenerationPort mock 替代，不调用真实 Provider；Credential 只以同一实例在内存中传播。
 * 服务自身提交发生在测试管理事务之外（NEVER + REQUIRES_NEW），因此不使用 @Transactional，
 * 改在 AfterEach 按 FK 依赖顺序清理。
 */
@SpringBootTest(properties = "app.registration-enabled=true")
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class PlannerEnrichmentDispatchIntegrationTests {

    private static final String GENERATED_JSON =
            "{\"materialId\":\"builtin:text-practice/material-0\",\"publishedVersion\":\"2026.03.1\","
                    + "\"recommendationReason\":\"今天在咖啡馆练习点单，并追问今日特供。\"}";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LanguageProfileRepository languageProfileRepository;

    @Autowired
    private PlannerEnrichmentDispatchService dispatchService;

    @Autowired
    private PlanningRunRepository planningRunRepository;

    @Autowired
    private ModelCallJobRepository modelCallJobRepository;

    @Autowired
    private FixedTextGenerationRoutes routes;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SqlSessionTemplate sqlSession;

    @MockitoBean
    private TextGenerationPort textGenerationPort;

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
            jdbcTemplate.update("DELETE FROM language_profile WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
        createdUserIds.clear();
    }

    @Test
    void dispatchesCommittedPlanningJobAndRecordsDurableResult() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();
        UserContext user = new UserContext(ownerId);
        TransientProviderCredential credential =
                new TransientProviderCredential(new ProviderId("deepseek"), "integration-secret");
        AtomicBoolean durableRowsVisibleToWorker = new AtomicBoolean();
        TextGenerationResponse response = new TextGenerationResponse(
                new ProviderId("deepseek"), new ModelId("deepseek-v4-flash"), GENERATED_JSON,
                TextGenerationResponse.FinishReason.COMPLETED, Optional.empty());
        when(textGenerationPort.generateText(any(TextGenerationRequest.class), same(credential)))
                .thenAnswer(invocation -> {
                    // dispatch 返回前，Worker 在另一个连接里必须已看到提交后的 Run / Job / snapshot。
                    Integer runCount = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM planning_run WHERE user_id = ?",
                            Integer.class, ownerId);
                    Integer jobCount = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM model_call_job WHERE user_id = ?",
                            Integer.class, ownerId);
                    Integer candidateCount = jdbcTemplate.queryForObject("""
                            SELECT COUNT(*) FROM planning_run_candidate
                            WHERE run_id IN (SELECT id FROM planning_run WHERE user_id = ?)
                            """, Integer.class, ownerId);
                    durableRowsVisibleToWorker.set(
                            runCount == 1 && jobCount == 1 && candidateCount == 2);
                    return ModelResult.success(response);
                });

        PlannerEnrichmentDispatchService.DispatchResult result = dispatchService.dispatch(
                planningRequest(profile), candidateSet(profile.id()), user, credential);

        assertThat(result).isInstanceOfSatisfying(
                PlannerEnrichmentDispatchService.DispatchResult.Created.class, created -> {
                    assertThat(created.submissionOutcome()).isEqualTo(SubmissionOutcome.ACCEPTED);
                    ModelCallJob completedJob = awaitTerminalJob(created.jobId(), ownerId);
                    assertThat(completedJob.executionStatus())
                            .isEqualTo(ModelCallJob.ExecutionStatus.SUCCEEDED);
                    assertThat(completedJob.consumptionStatus())
                            .isEqualTo(ModelCallJob.ConsumptionStatus.NOT_READY);
                    assertThat(completedJob.modelPurpose()).isEqualTo(ModelPurpose.PLANNING);
                    // fixed route 在执行期解析；route-unresolved 创建态已被 Worker 填充。
                    assertThat(completedJob.providerId()).contains(new ProviderId("deepseek"));
                    assertThat(completedJob.modelId()).contains(new ModelId("deepseek-v4-flash"));
                    // TTL 来自 app.planner.enrichment.result-ttl（默认 5m），与 route timeout 独立。
                    assertThat(completedJob.expiresAt().toInstant())
                            .isAfter(completedJob.createdAt().plus(Duration.ofMinutes(4)).toInstant())
                            .isBefore(completedJob.createdAt().plus(Duration.ofMinutes(6)).toInstant());
                    assertThat(modelCallJobRepository.findTextGenerationResultByJobIdAndUserId(
                            created.jobId(), ownerId).orElseThrow().text())
                            .isEqualTo(GENERATED_JSON);
                    assertThat(durableRowsVisibleToWorker).isTrue();

                    assertThat(planningRunRepository.findOwned(
                            created.run().id(), ownerId, profile.id()))
                            .contains(created.run());
                    // durable snapshot 与派发用的同一 candidate set 逐项一致。
                    assertThat(planningRunRepository.findOwnedSnapshot(
                            created.run().id(), ownerId, profile.id()))
                            .contains(PlanningRun.Snapshot.fromCandidateSet(candidateSet(profile.id())));
                });
        assertThat(routes.findRoute(ModelPurpose.PLANNING)).isPresent();
    }

    @Test
    void mismatchedUserFailsBeforeAnyDurableInteraction() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();

        assertThatThrownBy(() -> dispatchService.dispatch(
                planningRequest(profile), candidateSet(profile.id()),
                new UserContext(UUID.randomUUID()),
                new TransientProviderCredential(new ProviderId("deepseek"), "integration-secret")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("userContext must match the planning request language profile owner");

        assertThat(planningRunCount(ownerId)).isZero();
        assertThat(jobCount(ownerId)).isZero();
    }

    private UUID newUser() {
        UUID userId = userRepository.create();
        createdUserIds.add(userId);
        return userId;
    }

    private static PlanningRequest planningRequest(LanguageProfileIdentity profile) {
        return new PlanningRequest(
                profile, "zh", MaterialDifficulty.FOUNDATION, 10, Set.of());
    }

    private static PlanningCandidateSet candidateSet(UUID profileId) {
        return new PlanningCandidateSet(List.of(
                deterministicPlan(profileId, 0),
                deterministicPlan(profileId, 1)));
    }

    private static LearningTaskPlan deterministicPlan(UUID profileId, int sequence) {
        return new LearningTaskPlan(
                profileId,
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

    private ModelCallJob awaitTerminalJob(UUID jobId, UUID userId) {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            sqlSession.clearCache();
            ModelCallJob job = modelCallJobRepository.findByIdAndUserId(jobId, userId).orElseThrow();
            if (job.executionStatus() != ModelCallJob.ExecutionStatus.CREATED
                    && job.executionStatus() != ModelCallJob.ExecutionStatus.RUNNING) {
                return job;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for model job", exception);
            }
        }
        throw new IllegalStateException("model call job did not reach a terminal state");
    }

    private long planningRunCount(UUID ownerId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM planning_run WHERE user_id = ?", Long.class, ownerId);
    }

    private long jobCount(UUID ownerId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM model_call_job WHERE user_id = ?", Long.class, ownerId);
    }
}
