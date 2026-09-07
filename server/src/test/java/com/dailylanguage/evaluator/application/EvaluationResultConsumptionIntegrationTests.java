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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.dailylanguage.evaluator.application.EvaluationResultConsumptionService.ConsumptionResult;
import com.dailylanguage.evaluator.application.EvaluationResultConsumptionService.DurableOutcome;
import com.dailylanguage.evaluator.application.GroundedEvaluationInputResult.Ready;
import com.dailylanguage.evaluator.domain.EvaluationRun;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.GroundedClaim;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.Rejected;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.RejectionReason;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.Validated;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.ValidatedSemanticCandidate;
import com.dailylanguage.evaluator.infrastructure.EvaluationRunRepository;
import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.languageprofile.infrastructure.LanguageProfileRepository;
import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.infrastructure.ModelCallJobRepository;
import com.dailylanguage.modelgateway.result.ModelFailure;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;
import com.dailylanguage.modelgateway.text.TextGenerationResponse.FinishReason;
import com.dailylanguage.planner.application.LearningTaskPlanningService;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.practice.application.PracticeSessionApplicationService;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.CompletionResult;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.StartResult;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.SubmitResult;
import com.dailylanguage.practice.domain.PracticeSession;
import com.dailylanguage.security.domain.UserContext;
import com.dailylanguage.user.infrastructure.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;

/**
 * 用真实 PostgreSQL durable 数据验证 S8C：绑定 Job 的 SUCCEEDED raw result 在同一事务内被
 * grounding、消费并落为 Run 终态 + normalized candidate / claim；Job CONSUMED 与 business
 * outcome 原子提交，失败整体回滚；terminal Run replay 不重复评估。fixture 数据必须先于服务调用
 * 提交，AfterEach 按 FK 依赖顺序清理。
 */
@SpringBootTest(properties = "app.registration-enabled=true")
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class EvaluationResultConsumptionIntegrationTests {

    private static final String ANSWER_TO_GO_TEXT = "To go, please. Thank you!";
    private static final String VALID_CLAIM_JSON = """
            {"claims":[{"sourceTurnId":"answer-to-go","exactQuote":"Thank you","occurrenceIndex":-1,
            "issueType":"NATURALNESS","explanation":"The quoted thanks reads as abrupt here.",
            "confidence":0.7}]}
            """;
    private static final String ZERO_CLAIM_JSON = "{\"claims\":[]}";
    private static final String REJECTED_CLAIM_JSON = """
            {"claims":[{"sourceTurnId":"answer-to-go","exactQuote":"not in the durable text",
            "occurrenceIndex":-1,"issueType":"GRAMMAR","explanation":"fabricated","confidence":0.9}]}
            """;

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
    private EvaluationRunCreationService creationService;

    @Autowired
    private EvaluationResultConsumptionService consumptionService;

    @Autowired
    private ModelCallJobRepository modelCallJobRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SqlSessionTemplate sqlSession;

    // spy 默认委托真实 bean；仅 rollback 用例局部 stub claim 写入。
    @MockitoSpyBean
    private EvaluationRunRepository evaluationRunRepository;

    private final List<UUID> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanupCreatedUsers() {
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("""
                    DELETE FROM validated_semantic_claim WHERE evaluation_run_id IN (
                        SELECT run.id FROM evaluation_run run
                        JOIN practice_session session ON session.id = run.session_id
                        JOIN learning_task task ON task.id = session.task_id
                        WHERE task.user_id = ?)""", userId);
            jdbcTemplate.update("""
                    DELETE FROM validated_semantic_candidate WHERE evaluation_run_id IN (
                        SELECT run.id FROM evaluation_run run
                        JOIN practice_session session ON session.id = run.session_id
                        JOIN learning_task task ON task.id = session.task_id
                        WHERE task.user_id = ?)""", userId);
            jdbcTemplate.update("""
                    DELETE FROM evaluation_run WHERE session_id IN (
                        SELECT session.id FROM practice_session session
                        JOIN learning_task task ON task.id = session.task_id
                        WHERE task.user_id = ?)""", userId);
            jdbcTemplate.update("""
                    DELETE FROM model_call_text_generation_result WHERE job_id IN (
                        SELECT id FROM model_call_job WHERE user_id = ?)""", userId);
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
    void consumesDurableResultIntoCandidateAndConsumedJob() {
        Prepared prepared = prepareSucceededEvaluation(VALID_CLAIM_JSON);

        ConsumptionResult result = consumptionService.consumeForReadyInput(prepared.ready, prepared.user);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1",
                String.class)).isEqualTo("12");
        assertThat(result).isInstanceOfSatisfying(ConsumptionResult.Consumed.class, consumed -> {
            DurableOutcome outcome = consumed.outcome();
            assertThat(outcome.run().status()).isEqualTo(EvaluationRun.Status.SUCCEEDED);
            assertThat(outcome.run().completedAt()).isPresent();
            assertThat(outcome.run().rowVersion()).isEqualTo(1L);
            ValidatedSemanticCandidate candidate = ((Validated) outcome.groundingResult()).candidate();
            assertThat(candidate.sessionId()).isEqualTo(prepared.sessionId);
            GroundedClaim claim = candidate.claims().getFirst();
            assertThat(claim.startOffset()).isEqualTo(ANSWER_TO_GO_TEXT.indexOf("Thank you"));
            assertThat(ANSWER_TO_GO_TEXT.substring(claim.startOffset(), claim.endOffset()))
                    .isEqualTo("Thank you");
        });
        sqlSession.clearCache();
        assertThat(consumptionRow(prepared.jobId)).isEqualTo("CONSUMED");
        var header = jdbcTemplate.queryForMap(
                "SELECT session_id, material_id, material_published_version, rubric_reference,"
                        + " target_language, grounding_policy_version"
                        + " FROM validated_semantic_candidate WHERE evaluation_run_id = ?",
                prepared.runId);
        assertThat(header.get("session_id")).isEqualTo(prepared.sessionId);
        assertThat(header.get("material_id")).isEqualTo("en-builtin-cafe-request");
        assertThat(header.get("material_published_version")).isEqualTo("v1");
        assertThat(header.get("rubric_reference")).isEqualTo("builtin-text-communication-rubric/v1");
        assertThat(header.get("target_language")).isEqualTo("en");
        assertThat(header.get("grounding_policy_version")).isEqualTo("M1_GROUNDED_QUOTE_V1");
        var claimRow = jdbcTemplate.queryForMap(
                "SELECT claim_index, source_turn_id, exact_quote, occurrence_index, start_offset,"
                        + " end_offset, issue_type, confidence FROM validated_semantic_claim"
                        + " WHERE evaluation_run_id = ?",
                prepared.runId);
        assertThat(claimRow.get("claim_index")).isEqualTo(0);
        assertThat(claimRow.get("source_turn_id")).isEqualTo("answer-to-go");
        assertThat(claimRow.get("start_offset")).isEqualTo(ANSWER_TO_GO_TEXT.indexOf("Thank you"));
        assertThat(claimRow.get("issue_type")).isEqualTo("NATURALNESS");
    }

    @Test
    void zeroClaimCandidateKeepsHeaderOnly() {
        Prepared prepared = prepareSucceededEvaluation(ZERO_CLAIM_JSON);

        ConsumptionResult result = consumptionService.consumeForReadyInput(prepared.ready, prepared.user);

        assertThat(result).isInstanceOfSatisfying(ConsumptionResult.Consumed.class, consumed -> {
            assertThat(consumed.outcome().run().status()).isEqualTo(EvaluationRun.Status.SUCCEEDED);
            assertThat(((Validated) consumed.outcome().groundingResult()).candidate().claims()).isEmpty();
        });
        assertThat(candidateHeaderCount(prepared.runId)).isEqualTo(1);
        assertThat(claimCount(prepared.runId)).isZero();
    }

    @Test
    void rejectedOutputIsDurableFailedOutcomeWithoutCandidate() {
        Prepared prepared = prepareSucceededEvaluation(REJECTED_CLAIM_JSON);

        ConsumptionResult result = consumptionService.consumeForReadyInput(prepared.ready, prepared.user);

        assertThat(result).isInstanceOfSatisfying(ConsumptionResult.Consumed.class, consumed -> {
            assertThat(consumed.outcome().run().status()).isEqualTo(EvaluationRun.Status.FAILED);
            assertThat(((Rejected) consumed.outcome().groundingResult()).reason())
                    .isEqualTo(RejectionReason.QUOTE_MISMATCH);
        });
        sqlSession.clearCache();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT grounding_rejection_reason FROM evaluation_run WHERE id = ?",
                String.class, prepared.runId)).isEqualTo("QUOTE_MISMATCH");
        assertThat(candidateHeaderCount(prepared.runId)).isZero();
        assertThat(consumptionRow(prepared.jobId)).isEqualTo("CONSUMED");
    }

    @Test
    void duplicateConsumptionReplaysDurableOutcome() {
        Prepared prepared = prepareSucceededEvaluation(VALID_CLAIM_JSON);
        ConsumptionResult first = consumptionService.consumeForReadyInput(prepared.ready, prepared.user);

        ConsumptionResult second = consumptionService.consumeForReadyInput(prepared.ready, prepared.user);

        assertThat(first).isInstanceOf(ConsumptionResult.Consumed.class);
        assertThat(second).isInstanceOfSatisfying(ConsumptionResult.Existing.class, existing ->
                assertThat(existing.outcome().groundingResult())
                        .isEqualTo(((ConsumptionResult.Consumed) first).outcome().groundingResult()));
        assertThat(candidateHeaderCount(prepared.runId)).isEqualTo(1);
        assertThat(claimCount(prepared.runId)).isEqualTo(1);
    }

    @Test
    void concurrentConsumptionYieldsSingleDurableOutcome() throws Exception {
        Prepared prepared = prepareSucceededEvaluation(VALID_CLAIM_JSON);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<ConsumptionResult> consumption = () -> {
                start.await();
                return consumptionService.consumeForReadyInput(prepared.ready, prepared.user);
            };
            Future<ConsumptionResult> first = executor.submit(consumption);
            Future<ConsumptionResult> second = executor.submit(consumption);
            start.countDown();
            ConsumptionResult firstResult = first.get(30, TimeUnit.SECONDS);
            ConsumptionResult secondResult = second.get(30, TimeUnit.SECONDS);

            // Run 行锁串行化：恰一个 Consumed + 一个 Existing，且是同一 durable outcome。
            ConsumptionResult consumedResult =
                    firstResult instanceof ConsumptionResult.Consumed ? firstResult : secondResult;
            ConsumptionResult existingResult =
                    firstResult instanceof ConsumptionResult.Consumed ? secondResult : firstResult;
            assertThat(consumedResult).isInstanceOf(ConsumptionResult.Consumed.class);
            assertThat(existingResult).isInstanceOf(ConsumptionResult.Existing.class);
            assertThat(((ConsumptionResult.Existing) existingResult).outcome().groundingResult())
                    .isEqualTo(((ConsumptionResult.Consumed) consumedResult).outcome().groundingResult());
            assertThat(candidateHeaderCount(prepared.runId)).isEqualTo(1);
            assertThat(claimCount(prepared.runId)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void createdOrRunningJobIsPendingWithoutMutation() {
        Prepared prepared = prepareCreatedEvaluation();

        assertThat(consumptionService.consumeForReadyInput(prepared.ready, prepared.user))
                .isEqualTo(new ConsumptionResult.Pending());

        sqlSession.clearCache();
        ModelCallJob started = modelCallJobRepository.tryStartExecution(
                prepared.jobId, prepared.ownerId, 0L).orElseThrow();
        assertThat(started.executionStatus()).isEqualTo(ModelCallJob.ExecutionStatus.RUNNING);
        assertThat(consumptionService.consumeForReadyInput(prepared.ready, prepared.user))
                .isEqualTo(new ConsumptionResult.Pending());
        assertThat(runStatus(prepared.runId)).isEqualTo("PENDING");
    }

    @Test
    void modelFailureAndExpiredResultDeferToReconciliation() {
        Prepared prepared = prepareCreatedEvaluation();
        ModelCallJob started = modelCallJobRepository.tryStartExecution(
                prepared.jobId, prepared.ownerId, 0L).orElseThrow();
        ModelCallJob failed = modelCallJobRepository.tryRecordFailure(
                prepared.jobId, prepared.ownerId, started.rowVersion(),
                new ModelFailure(ModelFailureKind.CAPABILITY_UNAVAILABLE,
                        Optional.empty(), Optional.empty(), Optional.empty()))
                .orElseThrow();
        assertThat(failed.executionStatus()).isEqualTo(ModelCallJob.ExecutionStatus.FAILED);
        assertThat(consumptionService.consumeForReadyInput(prepared.ready, prepared.user))
                .isEqualTo(new ConsumptionResult.DeferredToReconciliation());
        assertThat(runStatus(prepared.runId)).isEqualTo("PENDING");

        Prepared expired = prepareSucceededEvaluation(VALID_CLAIM_JSON);
        jdbcTemplate.update("""
                UPDATE model_call_job
                SET created_at = CURRENT_TIMESTAMP - INTERVAL '3 seconds',
                    completed_at = CURRENT_TIMESTAMP - INTERVAL '2 seconds',
                    expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """, expired.jobId);
        sqlSession.clearCache();
        assertThat(consumptionService.consumeForReadyInput(expired.ready, expired.user))
                .isEqualTo(new ConsumptionResult.DeferredToReconciliation());
        assertThat(runStatus(expired.runId)).isEqualTo("PENDING");
    }

    @Test
    void candidateCannotBeInsertedBeforeBoundJobIsConsumed() {
        Prepared prepared = prepareSucceededEvaluation(ZERO_CLAIM_JSON);
        var input = prepared.ready.input();
        ValidatedSemanticCandidate candidate = new ValidatedSemanticCandidate(
                prepared.profileId,
                prepared.sessionId,
                input.material().identity(),
                input.material().targetCore().semanticRubricReference(),
                input.task().targetLanguage(),
                SemanticGroundingValidator.GROUNDING_POLICY_VERSION,
                List.of());

        assertThatThrownBy(() -> evaluationRunRepository.insertValidatedCandidate(
                prepared.runId, prepared.ownerId, prepared.profileId, candidate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("validated semantic candidate insert requires an owned evaluation run");
        assertThat(candidateHeaderCount(prepared.runId)).isZero();
        assertThat(consumptionRow(prepared.jobId)).isEqualTo("NOT_READY");
    }

    @Test
    void wrongOwnerProfileOrUnknownSessionCannotConsume() {
        Prepared prepared = prepareCreatedEvaluation();
        LanguageProfileIdentity otherProfile =
                languageProfileRepository.create(prepared.ownerId, "ja").orElseThrow();
        var input = prepared.ready.input();

        assertThat(consumptionService.consumeForReadyInput(prepared.ready, new UserContext(UUID.randomUUID())))
                .isEqualTo(new ConsumptionResult.InconsistentInput());
        assertThat(consumptionService.consumeForReadyInput(new Ready(new com.dailylanguage.evaluator.domain.GroundedEvaluationInput(
                prepared.ownerId, otherProfile.id(), input.task(), input.session(), input.assessment(),
                input.responses(), input.material())), prepared.user))
                .isEqualTo(new ConsumptionResult.NotFound());
        assertThat(consumptionService.consumeForReadyInput(new Ready(new com.dailylanguage.evaluator.domain.GroundedEvaluationInput(
                prepared.ownerId, prepared.profileId, input.task(),
                new PracticeSession(UUID.randomUUID(), input.session().taskId(),
                        PracticeSession.Status.COMPLETED, input.session().startedAt(),
                        input.session().completedAt(), Optional.empty()),
                input.assessment(), input.responses(), input.material())), prepared.user))
                .isEqualTo(new ConsumptionResult.NotFound());
        assertThat(runStatus(prepared.runId)).isEqualTo("PENDING");
    }

    @Test
    void claimPersistenceFailureRollsBackJobConsumption() {
        Prepared prepared = prepareSucceededEvaluation(VALID_CLAIM_JSON);
        doThrow(new IllegalStateException("claim insert failed"))
                .when(evaluationRunRepository)
                .insertValidatedClaim(any(), any(), any(), anyInt(), any());

        assertThatThrownBy(() -> consumptionService.consumeForReadyInput(prepared.ready, prepared.user))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("claim insert failed");

        // 核心保证：Job 的 CONSUMED 与 outcome 原子提交——outcome 写入失败时消费一并回滚。
        sqlSession.clearCache();
        assertThat(consumptionRow(prepared.jobId)).isEqualTo("NOT_READY");
        assertThat(runStatus(prepared.runId)).isEqualTo("PENDING");
        assertThat(candidateHeaderCount(prepared.runId)).isZero();
    }

    @Test
    void consumedJobWithPendingRunFailsClosed() {
        Prepared prepared = prepareSucceededEvaluation(VALID_CLAIM_JSON);
        jdbcTemplate.update(
                "UPDATE model_call_job SET consumption_status = 'CONSUMED', row_version = row_version + 1"
                        + " WHERE id = ?", prepared.jobId);
        sqlSession.clearCache();

        assertThatThrownBy(() -> consumptionService.consumeForReadyInput(prepared.ready, prepared.user))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("evaluation job is already consumed while its run is still pending");
        assertThat(runStatus(prepared.runId)).isEqualTo("PENDING");
    }

    @Test
    void succeededJobWithoutResultRowFailsClosed() {
        Prepared prepared = prepareSucceededEvaluation(VALID_CLAIM_JSON);
        jdbcTemplate.update(
                "DELETE FROM model_call_text_generation_result WHERE job_id = ?", prepared.jobId);
        sqlSession.clearCache();

        assertThatThrownBy(() -> consumptionService.consumeForReadyInput(prepared.ready, prepared.user))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("succeeded evaluation job is missing its durable text generation result");
        assertThat(runStatus(prepared.runId)).isEqualTo("PENDING");
        assertThat(candidateHeaderCount(prepared.runId)).isZero();
    }

    // --- helpers ---

    private record Prepared(
            UUID ownerId, UUID profileId, UUID sessionId, UUID runId, UUID jobId,
            Ready ready, UserContext user) {
    }

    /** plan → start → submit → complete → Ready → S8B 创建 Run + Job；可选驱动 Job 到 SUCCEEDED。 */
    private Prepared prepareCreatedEvaluation() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "en").orElseThrow();
        UserContext user = new UserContext(ownerId);
        UUID sessionId = completeCafeSession(profile.id(), user);
        Ready ready = readyInput(profile.id(), user, sessionId);
        EvaluationRunCreationService.CreationResult created =
                creationService.createForReadyInput(ready, user, OffsetDateTime.now().plusDays(7));
        assertThat(created).isInstanceOf(EvaluationRunCreationService.CreationResult.Created.class);
        var createdRun = (EvaluationRunCreationService.CreationResult.Created) created;
        return new Prepared(ownerId, profile.id(), sessionId,
                createdRun.run().id(), createdRun.job().id(), ready, user);
    }

    private Prepared prepareSucceededEvaluation(String generatedJson) {
        Prepared prepared = prepareCreatedEvaluation();
        ModelCallJob started = modelCallJobRepository.tryStartExecution(
                prepared.jobId, prepared.ownerId, 0L).orElseThrow();
        ModelCallJob succeeded = modelCallJobRepository.tryRecordTextGenerationSuccess(
                started.id(), prepared.ownerId, started.rowVersion(),
                new TextGenerationResponse(
                        new ProviderId("deepseek"), new ModelId("deepseek-chat"),
                        generatedJson, FinishReason.COMPLETED, Optional.empty()))
                .orElseThrow();
        assertThat(succeeded.executionStatus()).isEqualTo(ModelCallJob.ExecutionStatus.SUCCEEDED);
        return prepared;
    }

    private UUID newUser() {
        UUID userId = userRepository.create();
        createdUserIds.add(userId);
        return userId;
    }

    private String runStatus(UUID runId) {
        sqlSession.clearCache();
        return jdbcTemplate.queryForObject(
                "SELECT status FROM evaluation_run WHERE id = ?", String.class, runId);
    }

    private String consumptionRow(UUID jobId) {
        return jdbcTemplate.queryForObject(
                "SELECT consumption_status FROM model_call_job WHERE id = ?", String.class, jobId);
    }

    private int candidateHeaderCount(UUID runId) {
        sqlSession.clearCache();
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM validated_semantic_candidate WHERE evaluation_run_id = ?",
                Integer.class, runId);
    }

    private int claimCount(UUID runId) {
        sqlSession.clearCache();
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM validated_semantic_claim WHERE evaluation_run_id = ?",
                Integer.class, runId);
    }

    private Ready readyInput(UUID profileId, UserContext user, UUID sessionId) {
        GroundedEvaluationInputResult result = reader.readOwned(profileId, sessionId, user);
        assertThat(result).isInstanceOf(Ready.class);
        return (Ready) result;
    }

    private UUID completeCafeSession(UUID profileId, UserContext user) {
        var planning = planningService.plan(profileId, user,
                new LearningTaskPlanningService.PlanningCommand("zh-cn", "FOUNDATION", 10));
        assertThat(planning).isInstanceOf(com.dailylanguage.planner.application.LearningTaskPlanningResult.Created.class);
        LearningTask task = ((com.dailylanguage.planner.application.LearningTaskPlanningResult.Created) planning).task();

        StartResult startResult = practiceService.start(profileId, task.id(), user);
        assertThat(startResult).isInstanceOf(StartResult.Created.class);
        UUID sessionId = ((StartResult.Created) startResult).session().id();

        assertThat(practiceService.submit(profileId, sessionId, "order-drink", user,
                "Could I have a medium coffee, please?")).isInstanceOf(SubmitResult.Accepted.class);
        assertThat(practiceService.submit(profileId, sessionId, "ask-price", user, "How much is it?"))
                .isInstanceOf(SubmitResult.Accepted.class);
        assertThat(practiceService.submit(profileId, sessionId, "answer-to-go", user, ANSWER_TO_GO_TEXT))
                .isInstanceOf(SubmitResult.Accepted.class);
        assertThat(practiceService.complete(profileId, sessionId, user))
                .isInstanceOf(CompletionResult.Created.class);
        return sessionId;
    }
}
