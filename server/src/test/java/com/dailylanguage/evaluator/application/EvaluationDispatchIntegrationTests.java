package com.dailylanguage.evaluator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dailylanguage.evaluator.application.EvaluationDispatchService.DispatchResult;
import com.dailylanguage.evaluator.application.EvaluationResultConsumptionService.ConsumptionResult;
import com.dailylanguage.evaluator.application.GroundedEvaluationInputResult.Ready;
import com.dailylanguage.evaluator.domain.EvaluationRun;
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
import com.dailylanguage.planner.application.LearningTaskPlanningResult;
import com.dailylanguage.planner.application.LearningTaskPlanningService;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.practice.application.PracticeSessionApplicationService;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.CompletionResult;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.StartResult;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.SubmitResult;
import com.dailylanguage.security.domain.UserContext;
import com.dailylanguage.user.infrastructure.UserRepository;

/**
 * 使用真实 PostgreSQL 与异步 Job Worker 验证 S8D，不访问真实 Model Provider。
 */
@SpringBootTest(properties = "app.registration-enabled=true")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class EvaluationDispatchIntegrationTests {

    private static final String GENERATED_JSON = "{\"claims\":[]}";
    private static final String PROVIDER_CREDENTIAL_HEADER = "X-Model-Provider-Credential";

    @Autowired
    private MockMvc mockMvc;

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
    private EvaluationDispatchService dispatchService;

    @Autowired
    private EvaluationResultConsumptionService consumptionService;

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
    void dispatchesCommittedEvaluationJobAndConsumesItsDurableResult() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "en").orElseThrow();
        UserContext user = new UserContext(ownerId);
        UUID sessionId = completeCafeSession(profile.id(), user);
        Ready ready = readyInput(profile.id(), user, sessionId);
        TransientProviderCredential credential =
                new TransientProviderCredential(new ProviderId("deepseek"), "integration-secret");
        AtomicBoolean durableRowsVisibleToWorker = new AtomicBoolean();
        TextGenerationResponse response = new TextGenerationResponse(
                new ProviderId("deepseek"), new ModelId("deepseek-v4-flash"), GENERATED_JSON,
                TextGenerationResponse.FinishReason.COMPLETED, Optional.empty());
        when(textGenerationPort.generateText(any(TextGenerationRequest.class), same(credential)))
                .thenAnswer(invocation -> {
                    Integer runCount = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM evaluation_run WHERE session_id = ?",
                            Integer.class, sessionId);
                    Integer jobCount = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM model_call_job WHERE user_id = ?",
                            Integer.class, ownerId);
                    durableRowsVisibleToWorker.set(runCount == 1 && jobCount == 1);
                    return ModelResult.success(response);
                });

        DispatchResult first = dispatchService.dispatchForReadyInput(ready, user, credential);

        assertThat(first).isInstanceOfSatisfying(DispatchResult.Created.class, created -> {
            assertThat(created.submissionOutcome()).isEqualTo(SubmissionOutcome.ACCEPTED);
            ModelCallJob completedJob = awaitTerminalJob(created.jobId(), ownerId);
            assertThat(completedJob.executionStatus()).isEqualTo(ModelCallJob.ExecutionStatus.SUCCEEDED);
            assertThat(completedJob.consumptionStatus()).isEqualTo(ModelCallJob.ConsumptionStatus.NOT_READY);
            assertThat(completedJob.modelPurpose()).isEqualTo(ModelPurpose.EVALUATION);
            assertThat(completedJob.expiresAt().toInstant())
                    .isAfter(completedJob.createdAt().plus(Duration.ofDays(6)).toInstant());
            assertThat(modelCallJobRepository.findTextGenerationResultByJobIdAndUserId(
                    created.jobId(), ownerId).orElseThrow().text()).isEqualTo(GENERATED_JSON);
            assertThat(durableRowsVisibleToWorker).isTrue();

            ConsumptionResult consumed = consumptionService.consumeForReadyInput(ready, user);
            assertThat(consumed).isInstanceOf(ConsumptionResult.Consumed.class);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM evaluation_run WHERE id = ?",
                    String.class, created.run().id())).isEqualTo(EvaluationRun.Status.SUCCEEDED.name());
        });

        DispatchResult replay = dispatchService.dispatchForReadyInput(ready, user, credential);

        assertThat(replay).isInstanceOf(DispatchResult.Existing.class);
        verify(textGenerationPort, timeout(5_000).times(1))
                .generateText(any(TextGenerationRequest.class), same(credential));
        assertThat(routes.findRoute(ModelPurpose.EVALUATION)).isPresent();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1",
                String.class)).isEqualTo("13");
    }

    @Test
    void httpOwnerCanTriggerReconcileAndReplayOneEvaluation() throws Exception {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "en").orElseThrow();
        UserContext owner = new UserContext(ownerId);
        UUID sessionId = completeCafeSession(profile.id(), owner);
        String endpoint = "/api/language-profiles/" + profile.id()
                + "/practice-sessions/" + sessionId + "/evaluation";
        String credential = "api-integration-secret";
        when(textGenerationPort.generateText(
                any(TextGenerationRequest.class), any(TransientProviderCredential.class)))
                .thenReturn(ModelResult.success(new TextGenerationResponse(
                        new ProviderId("deepseek"),
                        new ModelId("deepseek-v4-flash"),
                        GENERATED_JSON,
                        TextGenerationResponse.FinishReason.COMPLETED,
                        Optional.empty())));

        int triggerStatus = mockMvc.perform(put(endpoint)
                        .with(authenticated(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(PROVIDER_CREDENTIAL_HEADER, credential)
                        .content("{\"providerId\":\"deepseek\"}"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andReturn().getResponse().getStatus();
        assertThat(triggerStatus).isIn(200, 202);

        UUID jobId = jdbcTemplate.queryForObject(
                "SELECT id FROM model_call_job WHERE user_id = ?", UUID.class, ownerId);
        awaitTerminalJob(jobId, ownerId);
        mockMvc.perform(put(endpoint + "/reconciliation")
                        .with(authenticated(owner)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.semanticResult.claims").isEmpty());

        mockMvc.perform(put(endpoint)
                        .with(authenticated(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(PROVIDER_CREDENTIAL_HEADER, credential)
                        .content("{\"providerId\":\"deepseek\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        verify(textGenerationPort, timeout(5_000).times(1))
                .generateText(any(TextGenerationRequest.class), any(TransientProviderCredential.class));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT row_to_json(job)::text FROM model_call_job job WHERE user_id = ?",
                String.class, ownerId)).doesNotContain(credential);

        UUID foreignUserId = newUser();
        mockMvc.perform(put(endpoint + "/reconciliation")
                        .with(authenticated(new UserContext(foreignUserId))).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRACTICE_SESSION_NOT_FOUND"));
    }

    private UUID newUser() {
        UUID userId = userRepository.create();
        createdUserIds.add(userId);
        return userId;
    }

    private Ready readyInput(UUID profileId, UserContext user, UUID sessionId) {
        GroundedEvaluationInputResult result = reader.readOwned(profileId, sessionId, user);
        assertThat(result).isInstanceOf(Ready.class);
        return (Ready) result;
    }

    private UUID completeCafeSession(UUID profileId, UserContext user) {
        LearningTaskPlanningResult planning = planningService.plan(
                profileId, user,
                new LearningTaskPlanningService.PlanningCommand("zh-cn", "FOUNDATION", 10));
        assertThat(planning).isInstanceOf(LearningTaskPlanningResult.Created.class);
        LearningTask task = ((LearningTaskPlanningResult.Created) planning).task();

        StartResult startResult = practiceService.start(profileId, task.id(), user);
        assertThat(startResult).isInstanceOf(StartResult.Created.class);
        UUID sessionId = ((StartResult.Created) startResult).session().id();
        assertThat(practiceService.submit(profileId, sessionId, "order-drink", user,
                "Could I have a medium coffee, please?"))
                .isInstanceOf(SubmitResult.Accepted.class);
        assertThat(practiceService.submit(profileId, sessionId, "ask-price", user,
                "How much is it?"))
                .isInstanceOf(SubmitResult.Accepted.class);
        assertThat(practiceService.submit(profileId, sessionId, "answer-to-go", user,
                "To go, please. Thank you!"))
                .isInstanceOf(SubmitResult.Accepted.class);
        assertThat(practiceService.complete(profileId, sessionId, user))
                .isInstanceOf(CompletionResult.Created.class);
        return sessionId;
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

    private static org.springframework.test.web.servlet.request.RequestPostProcessor authenticated(
            UserContext userContext) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                userContext, null, List.of()));
    }
}
