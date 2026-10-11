package com.dailylanguage.evaluator.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.content.domain.TextPracticeStep;
import com.dailylanguage.evaluator.application.EvaluationDispatchService.DispatchResult;
import com.dailylanguage.evaluator.application.EvaluationResultConsumptionService.ConsumptionResult;
import com.dailylanguage.evaluator.application.EvaluationResultConsumptionService.DurableOutcome;
import com.dailylanguage.evaluator.application.GroundedEvaluationInputResult.NotFound;
import com.dailylanguage.evaluator.application.GroundedEvaluationInputResult.Ready;
import com.dailylanguage.evaluator.domain.GroundedEvaluationInput;
import com.dailylanguage.evaluator.domain.EvaluationRun;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.Rejected;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.RejectionReason;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.Validated;
import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.languageprofile.infrastructure.LanguageProfileRepository;
import com.dailylanguage.modelcalljob.application.TextGenerationJobSubmission.SubmissionOutcome;
import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.infrastructure.ModelCallJobRepository;
import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.result.ModelFailure;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.result.ModelResult;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationPort;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;
import com.dailylanguage.planner.application.LearningTaskPlanningResult;
import com.dailylanguage.planner.application.LearningTaskPlanningService;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.infrastructure.LearningTaskRepository;
import com.dailylanguage.practice.application.PracticeSessionApplicationService;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.CompletionResult;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.StartResult;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.SubmitResult;
import com.dailylanguage.practice.domain.DeterministicAssessment.StepOutcome;
import com.dailylanguage.practice.domain.DeterministicTextAssessmentPolicy;
import com.dailylanguage.practice.infrastructure.PracticeSessionRepository;
import com.dailylanguage.security.domain.UserContext;
import com.dailylanguage.user.infrastructure.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M1-S10 Human Core：用真实 PostgreSQL 验证 Japanese pack 复用既有 Planner / Practice /
 * deterministic assessment / Evaluator dispatch / durable consumption 路径，同时保持 Profile 语言隔离。
 * 仅替换 Model Gateway 的返回值；request、异步 Worker、grounding 和持久化使用真实实现。
 * Provider-free planning 必须保持零 Run / Job；显式 Evaluation 才允许创建 Evaluation Run / Job。
 */
@SpringBootTest(properties = "app.registration-enabled=true")
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class JapaneseValidationPackIntegrationTests {

    private static final MaterialIdentity JAPANESE_MATERIAL =
            new MaterialIdentity("ja-builtin-clarify-repeat", "v1");
    private static final String EXACT_RESPONSE = "すみません、もう一度お願いします。";
    private static final String SEMANTIC_RESPONSE = "三番線ですね。わかりました。ありがとうございます。";
    private static final String JAPANESE_RUBRIC = "builtin-japanese-text-communication-rubric/v1";
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LanguageProfileRepository languageProfileRepository;

    @Autowired
    private LearningTaskPlanningService planningService;

    @Autowired
    private PracticeSessionApplicationService practiceService;

    @Autowired
    private LearningTaskRepository learningTaskRepository;

    @Autowired
    private PracticeSessionRepository practiceSessionRepository;

    @Autowired
    private GroundedEvaluationInputReader inputReader;

    @Autowired
    private EvaluationDispatchService dispatchService;

    @Autowired
    private EvaluationResultConsumptionService consumptionService;

    @Autowired
    private ModelCallJobRepository modelCallJobRepository;

    @Autowired
    private SqlSessionTemplate sqlSession;

    @MockitoBean
    private TextGenerationPort textGenerationPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ClasspathRubricSource rubricSource = new ClasspathRubricSource();
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
            jdbcTemplate.update("""
                    DELETE FROM planning_run_candidate WHERE run_id IN (
                        SELECT id FROM planning_run WHERE user_id = ?)""", userId);
            jdbcTemplate.update("DELETE FROM planning_run WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM model_call_job WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM learning_task WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM language_profile WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
        createdUserIds.clear();
    }

    @Test
    void japanesePackCompletesSharedWorkflowWithoutCrossLanguagePollution() throws IOException {
        UUID ownerId = newUser();
        UserContext user = new UserContext(ownerId);
        LanguageProfileIdentity japaneseProfile =
                languageProfileRepository.create(ownerId, "ja").orElseThrow();
        LanguageProfileIdentity englishProfile =
                languageProfileRepository.create(ownerId, "en").orElseThrow();

        LearningTask japaneseTask = createdTask(japaneseProfile.id(), user);
        assertThat(japaneseTask.materialIdentity()).isEqualTo(JAPANESE_MATERIAL);
        assertThat(japaneseTask.targetLanguage()).isEqualTo("ja");
        assertThat(japaneseTask.supportLanguage()).isEqualTo("zh-cn");

        LearningTask englishTask = createdTask(englishProfile.id(), user);
        assertThat(englishTask.targetLanguage()).isEqualTo("en");
        assertThat(englishTask.materialIdentity()).isNotEqualTo(JAPANESE_MATERIAL);
        assertProviderFreePlanningCreatedNoRunOrJob(ownerId);

        // 同一用户的 English Profile 仍不能启动或读取 Japanese Task / Session。
        assertThat(practiceService.start(englishProfile.id(), japaneseTask.id(), user))
                .isEqualTo(new StartResult.TaskNotFound());
        StartResult startResult = practiceService.start(japaneseProfile.id(), japaneseTask.id(), user);
        assertThat(startResult).isInstanceOf(StartResult.Created.class);
        StartResult.Created started = (StartResult.Created) startResult;
        UUID sessionId = started.session().id();
        assertThat(started.material().targetLanguage()).isEqualTo("ja");
        assertThat(started.material().readingInfo().readingLine()).contains("つぎの でんしゃ");
        assertThat(started.material().steps())
                .extracting(step -> step.learningPurpose())
                .containsExactly("PRACTICE", "PRACTICE");

        assertThat(practiceService.submit(
                englishProfile.id(), sessionId, "ask-to-repeat", user, EXACT_RESPONSE))
                .isEqualTo(new SubmitResult.SessionNotFound());
        assertThat(practiceService.submit(
                japaneseProfile.id(), sessionId, "ask-to-repeat", user, EXACT_RESPONSE))
                .isInstanceOf(SubmitResult.Accepted.class);
        assertThat(practiceService.submit(
                japaneseProfile.id(), sessionId, "acknowledge-information", user, SEMANTIC_RESPONSE))
                .isInstanceOf(SubmitResult.Accepted.class);
        assertThat(practiceService.complete(englishProfile.id(), sessionId, user))
                .isEqualTo(new CompletionResult.SessionNotFound());

        CompletionResult completionResult = practiceService.complete(japaneseProfile.id(), sessionId, user);
        assertThat(completionResult).isInstanceOf(CompletionResult.Created.class);
        CompletionResult.Created completed = (CompletionResult.Created) completionResult;
        assertThat(completed.assessment().stepResults())
                .anySatisfy(result -> {
                    assertThat(result.stepId()).isEqualTo("ask-to-repeat");
                    assertThat(result.outcome()).isEqualTo(StepOutcome.MATCHED);
                })
                .anySatisfy(result -> {
                    assertThat(result.stepId()).isEqualTo("acknowledge-information");
                    assertThat(result.outcome()).isEqualTo(StepOutcome.NOT_APPLICABLE);
                });

        assertThat(inputReader.readOwned(englishProfile.id(), sessionId, user))
                .isEqualTo(new NotFound());
        GroundedEvaluationInputResult inputResult =
                inputReader.readOwned(japaneseProfile.id(), sessionId, user);
        assertThat(inputResult).isInstanceOf(Ready.class);
        Ready ready = (Ready) inputResult;
        GroundedEvaluationInput input = ready.input();

        TextPracticeStep exactStep = input.material().targetCore().steps().stream()
                .filter(step -> step.stepId().equals("ask-to-repeat"))
                .findFirst()
                .orElseThrow();
        assertThat(DeterministicTextAssessmentPolicy.exactOutcome(
                "すみません、もう一度言ってください。", exactStep.acceptedAnswers()))
                .isEqualTo(StepOutcome.NOT_MATCHED);

        assertThat(rubricSource.resolve(
                "builtin-japanese-text-communication-rubric/v1", "ja")).isPresent();
        assertThat(rubricSource.resolve(
                "builtin-japanese-text-communication-rubric/v1", "en")).isEmpty();
        assertThat(rubricSource.resolve("builtin-text-communication-rubric/v1", "ja")).isEmpty();

        String supportTextClaim = """
                {"claims":[{"sourceTurnId":"acknowledge-information",
                "exactQuote":"先礼貌地请求对方重复一遍","occurrenceIndex":-1,
                "issueType":"TASK_RESPONSE","explanation":"support text is not learner evidence",
                "confidence":0.9}]}
                """;
        DispatchResult.Created dispatched = dispatchAndAwait(ready, user, modelSuccess(supportTextClaim));
        DurableOutcome outcome = consume(ready, user);
        assertThat(outcome.run().status()).isEqualTo(EvaluationRun.Status.FAILED);
        assertThat(outcome.run().failureReason()).contains(EvaluationRun.FailureReason.GROUNDING_REJECTED);
        assertThat(outcome.groundingResult()).contains(new Rejected(RejectionReason.QUOTE_MISMATCH));
        assertThat(job(dispatched.jobId(), ownerId).executionStatus())
                .isEqualTo(ModelCallJob.ExecutionStatus.SUCCEEDED);
        assertThat(job(dispatched.jobId(), ownerId).consumptionStatus())
                .isEqualTo(ModelCallJob.ConsumptionStatus.CONSUMED);
        assertNoSemanticCandidate(dispatched.run().id());
        assertCompletedPracticeUnchanged(completed, ready);
        assertThat(consumptionService.consumeForReadyInput(ready, user))
                .isEqualTo(new ConsumptionResult.Existing(outcome));
    }

    @Test
    void japaneseClaimIsGroundedAndPersistedThroughSharedEvaluation() throws IOException {
        CompletedJapanesePractice prepared = completeJapanesePractice();
        // 此 claim 只用于验证引用与持久化，不代表真实模型对该句自然度的判断。
        String generatedJson = """
                {"claims":[{"sourceTurnId":"acknowledge-information",
                "exactQuote":"三番線ですね。","occurrenceIndex":-1,
                "issueType":"NATURALNESS","explanation":"受控测试诊断，不用于教学质量结论。",
                "confidence":0.7}]}
                """;
        DispatchResult.Created dispatched = dispatchAndAwait(
                prepared.ready(), prepared.user(), modelSuccess(generatedJson));

        DurableOutcome outcome = consume(prepared.ready(), prepared.user());

        assertThat(outcome.run().status()).isEqualTo(EvaluationRun.Status.SUCCEEDED);
        assertThat(outcome.run().failureReason()).isEmpty();
        var candidate = ((Validated) outcome.groundingResult().orElseThrow()).candidate();
        assertThat(candidate.targetLanguage()).isEqualTo("ja");
        assertThat(candidate.rubricReference()).isEqualTo(JAPANESE_RUBRIC);
        assertThat(candidate.materialIdentity()).isEqualTo(JAPANESE_MATERIAL);
        assertThat(candidate.languageProfileId()).isEqualTo(prepared.ready().input().languageProfileId());
        assertThat(candidate.claims()).singleElement().satisfies(claim -> {
            assertThat(claim.sourceTurnId()).isEqualTo("acknowledge-information");
            assertThat(claim.occurrenceIndex()).isZero();
            assertThat(claim.startOffset()).isEqualTo(SEMANTIC_RESPONSE.indexOf("三番線ですね。"));
            assertThat(SEMANTIC_RESPONSE.substring(claim.startOffset(), claim.endOffset()))
                    .isEqualTo("三番線ですね。");
        });
        var persisted = jdbcTemplate.queryForMap(
                "SELECT target_language, rubric_reference, material_id FROM validated_semantic_candidate"
                        + " WHERE evaluation_run_id = ?", dispatched.run().id());
        assertThat(persisted.get("target_language")).isEqualTo("ja");
        assertThat(persisted.get("rubric_reference")).isEqualTo(JAPANESE_RUBRIC);
        assertThat(persisted.get("material_id")).isEqualTo("ja-builtin-clarify-repeat");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT exact_quote FROM validated_semantic_claim WHERE evaluation_run_id = ?",
                String.class, dispatched.run().id())).isEqualTo("三番線ですね。");
        assertThat(job(dispatched.jobId(), prepared.user().userId()).consumptionStatus())
                .isEqualTo(ModelCallJob.ConsumptionStatus.CONSUMED);
        // Replay 从持久化记录恢复同一 candidate，而不是只验证内存返回值。
        assertThat(consumptionService.consumeForReadyInput(prepared.ready(), prepared.user()))
                .isEqualTo(new ConsumptionResult.Existing(outcome));
        assertCompletedPracticeUnchanged(prepared.completed(), prepared.ready());
    }

    @Test
    void modelFailurePreservesCompletedJapanesePracticeWithoutSemanticCandidate() throws IOException {
        CompletedJapanesePractice prepared = completeJapanesePractice();
        DispatchResult.Created dispatched = dispatchAndAwait(prepared.ready(), prepared.user(),
                ModelResult.failure(new ModelFailure(ModelFailureKind.CAPABILITY_UNAVAILABLE,
                        Optional.empty(), Optional.empty(), Optional.empty())));

        DurableOutcome outcome = consume(prepared.ready(), prepared.user());

        assertThat(outcome.run().status()).isEqualTo(EvaluationRun.Status.FAILED);
        assertThat(outcome.run().failureReason()).contains(EvaluationRun.FailureReason.MODEL_CALL_FAILED);
        assertThat(outcome.groundingResult()).isEmpty();
        ModelCallJob failedJob = job(dispatched.jobId(), prepared.user().userId());
        assertThat(failedJob.executionStatus()).isEqualTo(ModelCallJob.ExecutionStatus.FAILED);
        assertThat(failedJob.consumptionStatus()).isEqualTo(ModelCallJob.ConsumptionStatus.NOT_READY);
        assertNoSemanticCandidate(dispatched.run().id());
        assertCompletedPracticeUnchanged(prepared.completed(), prepared.ready());
        assertThat(consumptionService.consumeForReadyInput(prepared.ready(), prepared.user()))
                .isEqualTo(new ConsumptionResult.Existing(outcome));
    }

    private record CompletedJapanesePractice(UserContext user, Ready ready, CompletionResult.Created completed) {
    }

    private CompletedJapanesePractice completeJapanesePractice() {
        UserContext user = new UserContext(newUser());
        UUID profileId = languageProfileRepository.create(user.userId(), "ja").orElseThrow().id();
        LearningTask task = createdTask(profileId, user);
        assertProviderFreePlanningCreatedNoRunOrJob(user.userId());
        StartResult start = practiceService.start(profileId, task.id(), user);
        assertThat(start).isInstanceOf(StartResult.Created.class);
        UUID sessionId = ((StartResult.Created) start).session().id();
        assertThat(practiceService.submit(profileId, sessionId, "ask-to-repeat", user, EXACT_RESPONSE))
                .isInstanceOf(SubmitResult.Accepted.class);
        assertThat(practiceService.submit(profileId, sessionId, "acknowledge-information", user, SEMANTIC_RESPONSE))
                .isInstanceOf(SubmitResult.Accepted.class);
        CompletionResult completion = practiceService.complete(profileId, sessionId, user);
        assertThat(completion).isInstanceOf(CompletionResult.Created.class);
        var ready = inputReader.readOwned(profileId, sessionId, user);
        assertThat(ready).isInstanceOf(Ready.class);
        return new CompletedJapanesePractice(user, (Ready) ready, (CompletionResult.Created) completion);
    }

    private DispatchResult.Created dispatchAndAwait(
            Ready ready, UserContext user, ModelResult<TextGenerationResponse> modelResult) throws IOException {
        var credential = new TransientProviderCredential(new ProviderId("deepseek"), "s10-test-only-secret");
        when(textGenerationPort.generateText(any(TextGenerationRequest.class), same(credential)))
                .thenReturn(modelResult);
        DispatchResult result = dispatchService.dispatchForReadyInput(ready, user, credential);
        assertThat(result).isInstanceOf(DispatchResult.Created.class);
        DispatchResult.Created created = (DispatchResult.Created) result;
        assertThat(created.submissionOutcome()).isEqualTo(SubmissionOutcome.ACCEPTED);
        assertThat(created.run().status()).isEqualTo(EvaluationRun.Status.PENDING);
        awaitTerminalJob(created.jobId(), user.userId());
        ArgumentCaptor<TextGenerationRequest> request = ArgumentCaptor.forClass(TextGenerationRequest.class);
        verify(textGenerationPort).generateText(request.capture(), same(credential));
        assertJapaneseRequest(request.getValue());
        return created;
    }

    private static void assertJapaneseRequest(TextGenerationRequest request) throws IOException {
        assertThat(request.purpose()).isEqualTo(ModelPurpose.EVALUATION);
        assertThat(request.messages()).hasSize(2);
        assertThat(request.messages().getFirst().content()).isEqualTo(new ClassPathResource(
                "evaluator/prompts/semantic-evaluation/v1.txt").getContentAsString(StandardCharsets.UTF_8));
        String promptJson = request.messages().get(1).content();
        assertThat(promptJson).doesNotContain("先礼貌地请求对方重复一遍");
        JsonNode root = JSON_MAPPER.readTree(promptJson);
        assertThat(root.get("targetLanguage").asText()).isEqualTo("ja");
        assertThat(root.get("rubric").get("rubricReference").asText()).isEqualTo(JAPANESE_RUBRIC);
        assertThat(root.get("rubric").get("targetLanguage").asText()).isEqualTo("ja");
        assertThat(root.get("rubric").get("issueDefinitions").get(0).get("scope").asText())
                .contains("Japanese grammar");
        // 持久化读取顺序不是业务 contract；用稳定 step identity 检查绑定，避免数组下标假设。
        Map<String, String> learnerResponses = new HashMap<>();
        root.get("learnerResponses").forEach(response -> learnerResponses.put(
                response.get("sourceTurnId").asText(), response.get("learnerText").asText()));
        assertThat(learnerResponses).hasSize(2)
                .containsEntry("ask-to-repeat", EXACT_RESPONSE)
                .containsEntry("acknowledge-information", SEMANTIC_RESPONSE);
        Map<String, String> deterministicResults = new HashMap<>();
        root.get("deterministicStepResults").forEach(result -> deterministicResults.put(
                result.get("stepId").asText(), result.get("outcome").asText()));
        assertThat(deterministicResults).hasSize(2)
                .containsEntry("ask-to-repeat", "MATCHED")
                .containsEntry("acknowledge-information", "NOT_APPLICABLE");
    }

    private static ModelResult<TextGenerationResponse> modelSuccess(String generatedJson) {
        return ModelResult.success(new TextGenerationResponse(
                new ProviderId("deepseek"), new ModelId("deepseek-v4-flash"), generatedJson,
                TextGenerationResponse.FinishReason.COMPLETED, Optional.empty()));
    }

    private DurableOutcome consume(Ready ready, UserContext user) {
        var result = consumptionService.consumeForReadyInput(ready, user);
        assertThat(result).isInstanceOf(ConsumptionResult.Consumed.class);
        return ((ConsumptionResult.Consumed) result).outcome();
    }

    private void assertCompletedPracticeUnchanged(CompletionResult.Created completed, Ready ready) {
        GroundedEvaluationInput input = ready.input();
        sqlSession.clearCache();
        assertThat(practiceSessionRepository.findOwned(input.session().id(), input.userId(), input.languageProfileId()))
                .contains(completed.session());
        assertThat(learningTaskRepository.findOwned(input.task().id(), input.userId(), input.languageProfileId()))
                .contains(completed.task());
        assertThat(practiceSessionRepository.findOwnedAssessment(
                input.session().id(), input.userId(), input.languageProfileId())).contains(completed.assessment());
    }

    private void assertNoSemanticCandidate(UUID runId) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM validated_semantic_candidate WHERE evaluation_run_id = ?",
                Integer.class, runId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM validated_semantic_claim WHERE evaluation_run_id = ?",
                Integer.class, runId)).isZero();
    }

    private ModelCallJob job(UUID jobId, UUID ownerId) {
        sqlSession.clearCache();
        return modelCallJobRepository.findByIdAndUserId(jobId, ownerId).orElseThrow();
    }

    private void awaitTerminalJob(UUID jobId, UUID ownerId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            ModelCallJob current = job(jobId, ownerId);
            if (current.executionStatus() != ModelCallJob.ExecutionStatus.CREATED
                    && current.executionStatus() != ModelCallJob.ExecutionStatus.RUNNING) {
                return;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for evaluation job", exception);
            }
        }
        throw new IllegalStateException("evaluation job did not reach terminal state");
    }

    private UUID newUser() {
        UUID userId = userRepository.create();
        createdUserIds.add(userId);
        return userId;
    }

    private LearningTask createdTask(UUID profileId, UserContext user) {
        LearningTaskPlanningResult result = planningService.plan(
                profileId,
                user,
                new LearningTaskPlanningService.PlanningCommand("zh-cn", "FOUNDATION", 10));
        assertThat(result).isInstanceOf(LearningTaskPlanningResult.Created.class);
        return ((LearningTaskPlanningResult.Created) result).task();
    }

    private void assertProviderFreePlanningCreatedNoRunOrJob(UUID ownerId) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM planning_run WHERE user_id = ?", Integer.class, ownerId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM model_call_job WHERE user_id = ?", Integer.class, ownerId))
                .isZero();
    }
}
