package com.dailylanguage.modelcalljob.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.domain.NewModelCallJob;
import com.dailylanguage.modelgateway.result.ModelUsage;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;
import com.dailylanguage.user.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "app.registration-enabled=true")
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class ModelCallJobTextSuccessRepositoryIntegrationTests {

    private static final ProviderId PROVIDER_ID = new ProviderId("deepseek");
    private static final ModelId MODEL_ID = new ModelId("deepseek-chat");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ModelCallJobRepository modelCallJobRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void recordsTextGenerationSuccessAndFinalRouteAtomically() {
        UUID userId = userRepository.create();
        ModelCallJob runningJob = createAndStartJob(
                userId, ModelOperation.TEXT_GENERATION, Optional.empty(), Optional.empty());
        TextGenerationResponse response = new TextGenerationResponse(
                PROVIDER_ID,
                MODEL_ID,
                "Hello",
                TextGenerationResponse.FinishReason.LENGTH_LIMIT,
                Optional.of(new ModelUsage(12, 7)));

        ModelCallJob completedJob = modelCallJobRepository.tryRecordTextGenerationSuccess(
                runningJob.id(), userId, runningJob.rowVersion(), response).orElseThrow();

        assertThat(completedJob.executionStatus()).isEqualTo(ModelCallJob.ExecutionStatus.SUCCEEDED);
        assertThat(completedJob.consumptionStatus()).isEqualTo(ModelCallJob.ConsumptionStatus.NOT_READY);
        assertThat(completedJob.providerId()).contains(PROVIDER_ID);
        assertThat(completedJob.modelId()).contains(MODEL_ID);
        assertThat(completedJob.rowVersion()).isEqualTo(runningJob.rowVersion() + 1);
        assertThat(completedJob.completedAt()).isPresent();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT generated_text, finish_reason, input_tokens, output_tokens
                FROM model_call_text_generation_result
                WHERE job_id = ?
                """, runningJob.id()))
                .containsEntry("generated_text", "Hello")
                .containsEntry("finish_reason", "LENGTH_LIMIT")
                .containsEntry("input_tokens", 12L)
                .containsEntry("output_tokens", 7L);
    }

    @Test
    void readsStoredTextGenerationResultForOwner() {
        UUID userId = userRepository.create();
        ModelCallJob runningJob = createAndStartJob(
                userId, ModelOperation.TEXT_GENERATION, Optional.empty(), Optional.empty());
        TextGenerationResponse response = new TextGenerationResponse(
                PROVIDER_ID,
                MODEL_ID,
                "Stored result",
                TextGenerationResponse.FinishReason.LENGTH_LIMIT,
                Optional.of(new ModelUsage(12, 7)));
        modelCallJobRepository.tryRecordTextGenerationSuccess(
                runningJob.id(), userId, runningJob.rowVersion(), response).orElseThrow();

        assertThat(modelCallJobRepository.findTextGenerationResultByJobIdAndUserId(
                runningJob.id(), userId)).contains(response);
    }

    @Test
    void readsStoredTextGenerationResultWithoutUsage() {
        UUID userId = userRepository.create();
        ModelCallJob runningJob = createAndStartJob(
                userId, ModelOperation.TEXT_GENERATION, Optional.empty(), Optional.empty());
        TextGenerationResponse response = response("No usage");
        modelCallJobRepository.tryRecordTextGenerationSuccess(
                runningJob.id(), userId, runningJob.rowVersion(), response).orElseThrow();

        assertThat(modelCallJobRepository.findTextGenerationResultByJobIdAndUserId(
                runningJob.id(), userId)).contains(response);
    }

    @Test
    void doesNotExposeStoredTextGenerationResultToAnotherOwner() {
        UUID ownerUserId = userRepository.create();
        UUID otherUserId = userRepository.create();
        ModelCallJob runningJob = createAndStartJob(
                ownerUserId, ModelOperation.TEXT_GENERATION, Optional.empty(), Optional.empty());
        modelCallJobRepository.tryRecordTextGenerationSuccess(
                runningJob.id(), ownerUserId, runningJob.rowVersion(), response("Private result")).orElseThrow();

        assertThat(modelCallJobRepository.findTextGenerationResultByJobIdAndUserId(
                runningJob.id(), otherUserId)).isEmpty();
    }

    @Test
    void doesNotReturnResultBeforeSuccessfulCompletion() {
        UUID userId = userRepository.create();
        ModelCallJob runningJob = createAndStartJob(
                userId, ModelOperation.TEXT_GENERATION, Optional.empty(), Optional.empty());

        assertThat(modelCallJobRepository.findTextGenerationResultByJobIdAndUserId(
                runningJob.id(), userId)).isEmpty();
    }

    @Test
    void staleVersionCannotOverwriteStoredResult() {
        UUID userId = userRepository.create();
        ModelCallJob runningJob = createAndStartJob(
                userId, ModelOperation.TEXT_GENERATION, Optional.empty(), Optional.empty());
        TextGenerationResponse firstResponse = response("first");
        TextGenerationResponse staleResponse = response("stale");
        modelCallJobRepository.tryRecordTextGenerationSuccess(
                runningJob.id(), userId, runningJob.rowVersion(), firstResponse).orElseThrow();

        assertThat(modelCallJobRepository.tryRecordTextGenerationSuccess(
                runningJob.id(), userId, runningJob.rowVersion(), staleResponse)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT generated_text
                FROM model_call_text_generation_result
                WHERE job_id = ?
                """, String.class, runningJob.id())).isEqualTo("first");
    }

    @Test
    void rejectsMismatchedPreselectedRouteWithoutMutation() {
        UUID userId = userRepository.create();
        ModelCallJob runningJob = createAndStartJob(
                userId,
                ModelOperation.TEXT_GENERATION,
                Optional.of(new ProviderId("openai")),
                Optional.of(new ModelId("gpt-model")));

        assertThat(modelCallJobRepository.tryRecordTextGenerationSuccess(
                runningJob.id(), userId, runningJob.rowVersion(), response("result"))).isEmpty();
        assertThat(modelCallJobRepository.findByIdAndUserId(runningJob.id(), userId)).contains(runningJob);
        assertThat(resultCount(runningJob.id())).isZero();
    }

    @Test
    void rejectsTextResultForAnotherOperationWithoutMutation() {
        UUID userId = userRepository.create();
        ModelCallJob runningJob = createAndStartJob(
                userId, ModelOperation.VISION_UNDERSTANDING, Optional.empty(), Optional.empty());

        assertThat(modelCallJobRepository.tryRecordTextGenerationSuccess(
                runningJob.id(), userId, runningJob.rowVersion(), response("result"))).isEmpty();
        assertThat(modelCallJobRepository.findByIdAndUserId(runningJob.id(), userId)).contains(runningJob);
        assertThat(resultCount(runningJob.id())).isZero();
    }

    @Test
    void rollsBackTerminalUpdateWhenResultInsertFails() {
        UUID userId = userRepository.create();
        ModelCallJob runningJob = createAndStartJob(
                userId, ModelOperation.TEXT_GENERATION, Optional.empty(), Optional.empty());
        jdbcTemplate.update("""
                INSERT INTO model_call_text_generation_result (
                    job_id, generated_text, finish_reason
                )
                VALUES (?, 'existing', 'COMPLETED')
                """, runningJob.id());

        assertThatThrownBy(() -> modelCallJobRepository.tryRecordTextGenerationSuccess(
                        runningJob.id(), userId, runningJob.rowVersion(), response("result")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(modelCallJobRepository.findByIdAndUserId(runningJob.id(), userId)).contains(runningJob);
        assertThat(resultCount(runningJob.id())).isEqualTo(1);
    }

    private ModelCallJob createAndStartJob(
            UUID userId,
            ModelOperation modelOperation,
            Optional<ProviderId> providerId,
            Optional<ModelId> modelId) {
        NewModelCallJob newJob = new NewModelCallJob(
                userId,
                Optional.empty(),
                ModelPurpose.PLANNING,
                modelOperation,
                providerId,
                modelId,
                UUID.randomUUID(),
                "GENERATE_TASK",
                0,
                OffsetDateTime.now().plusHours(1));
        ModelCallJob createdJob = modelCallJobRepository.create(newJob);
        return modelCallJobRepository.tryStartExecution(
                createdJob.id(), userId, createdJob.rowVersion()).orElseThrow();
    }

    private static TextGenerationResponse response(String text) {
        return new TextGenerationResponse(
                PROVIDER_ID,
                MODEL_ID,
                text,
                TextGenerationResponse.FinishReason.COMPLETED,
                Optional.empty());
    }

    private int resultCount(UUID jobId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM model_call_text_generation_result
                WHERE job_id = ?
                """, Integer.class, jobId);
        return count == null ? 0 : count;
    }
}
