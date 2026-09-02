package com.dailylanguage.modelcalljob.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.domain.NewModelCallJob;
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
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "app.registration-enabled=true")
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class ModelCallJobConsumptionRepositoryIntegrationTests {

    private static final ProviderId PROVIDER_ID = new ProviderId("deepseek");
    private static final ModelId MODEL_ID = new ModelId("deepseek-chat");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ModelCallJobRepository modelCallJobRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void consumesSucceededResultForCurrentWorkflowVersionOnce() {
        UUID userId = userRepository.create();
        ModelCallJob succeededJob = createSucceededJob(userId, 4, OffsetDateTime.now().plusHours(1));

        ModelCallJob consumedJob = modelCallJobRepository.tryConsumeSucceededResult(
                succeededJob.id(), userId, 4, succeededJob.rowVersion()).orElseThrow();

        assertThat(consumedJob.consumptionStatus()).isEqualTo(ModelCallJob.ConsumptionStatus.CONSUMED);
        assertThat(consumedJob.rowVersion()).isEqualTo(succeededJob.rowVersion() + 1);
        assertThat(modelCallJobRepository.tryConsumeSucceededResult(
                succeededJob.id(), userId, 4, succeededJob.rowVersion())).isEmpty();
    }

    @Test
    void marksOlderSucceededResultStaleForNewerWorkflowVersion() {
        UUID userId = userRepository.create();
        ModelCallJob succeededJob = createSucceededJob(userId, 4, OffsetDateTime.now().plusHours(1));

        ModelCallJob staleJob = modelCallJobRepository.tryMarkSucceededResultStale(
                succeededJob.id(), userId, 5, succeededJob.rowVersion()).orElseThrow();

        assertThat(staleJob.consumptionStatus()).isEqualTo(ModelCallJob.ConsumptionStatus.STALE);
        assertThat(staleJob.rowVersion()).isEqualTo(succeededJob.rowVersion() + 1);
    }

    @Test
    void olderWorkflowCallerCannotConsumeOrMarkNewerJobStale() {
        UUID userId = userRepository.create();
        ModelCallJob succeededJob = createSucceededJob(userId, 5, OffsetDateTime.now().plusHours(1));

        assertThat(modelCallJobRepository.tryConsumeSucceededResult(
                succeededJob.id(), userId, 4, succeededJob.rowVersion())).isEmpty();
        assertThat(modelCallJobRepository.tryMarkSucceededResultStale(
                succeededJob.id(), userId, 4, succeededJob.rowVersion())).isEmpty();
        assertThat(modelCallJobRepository.findByIdAndUserId(
                succeededJob.id(), userId)).contains(succeededJob);
    }

    @Test
    void anotherOwnerCannotChangeConsumptionStatus() {
        UUID ownerUserId = userRepository.create();
        UUID otherUserId = userRepository.create();
        ModelCallJob succeededJob = createSucceededJob(ownerUserId, 4, OffsetDateTime.now().plusHours(1));

        assertThat(modelCallJobRepository.tryConsumeSucceededResult(
                succeededJob.id(), otherUserId, 4, succeededJob.rowVersion())).isEmpty();
        assertThat(modelCallJobRepository.findByIdAndUserId(
                succeededJob.id(), ownerUserId)).contains(succeededJob);
    }

    @Test
    void staleRowVersionCannotConsumeStillReadyResult() {
        UUID userId = userRepository.create();
        ModelCallJob succeededJob = createSucceededJob(userId, 4, OffsetDateTime.now().plusHours(1));

        assertThat(modelCallJobRepository.tryConsumeSucceededResult(
                succeededJob.id(), userId, 4, succeededJob.rowVersion() - 1)).isEmpty();
        assertThat(modelCallJobRepository.findByIdAndUserId(
                succeededJob.id(), userId)).contains(succeededJob);
    }

    @Test
    void runningOrExpiredJobCannotBeConsumed() {
        UUID userId = userRepository.create();
        ModelCallJob runningJob = createRunningJob(userId, 4, OffsetDateTime.now().plusHours(1));
        ModelCallJob expiredJob = createSucceededJob(userId, 4, OffsetDateTime.now().plusHours(1));
        jdbcTemplate.update("""
                UPDATE model_call_job
                SET created_at = CURRENT_TIMESTAMP - INTERVAL '3 seconds',
                    completed_at = CURRENT_TIMESTAMP - INTERVAL '2 seconds',
                    expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """, expiredJob.id());

        assertThat(modelCallJobRepository.tryConsumeSucceededResult(
                runningJob.id(), userId, 4, runningJob.rowVersion())).isEmpty();
        assertThat(modelCallJobRepository.tryConsumeSucceededResult(
                expiredJob.id(), userId, 4, expiredJob.rowVersion())).isEmpty();
    }

    private ModelCallJob createSucceededJob(UUID userId, long workflowVersion, OffsetDateTime expiresAt) {
        ModelCallJob runningJob = createRunningJob(userId, workflowVersion, expiresAt);
        TextGenerationResponse response = new TextGenerationResponse(
                PROVIDER_ID,
                MODEL_ID,
                "result",
                TextGenerationResponse.FinishReason.COMPLETED,
                Optional.empty());
        return modelCallJobRepository.tryRecordTextGenerationSuccess(
                runningJob.id(), userId, runningJob.rowVersion(), response).orElseThrow();
    }

    private ModelCallJob createRunningJob(UUID userId, long workflowVersion, OffsetDateTime expiresAt) {
        NewModelCallJob newJob = new NewModelCallJob(
                userId,
                Optional.empty(),
                ModelPurpose.PLANNING,
                ModelOperation.TEXT_GENERATION,
                Optional.empty(),
                Optional.empty(),
                UUID.randomUUID(),
                "GENERATE_TASK",
                workflowVersion,
                expiresAt);
        ModelCallJob createdJob = modelCallJobRepository.create(newJob);
        return modelCallJobRepository.tryStartExecution(
                createdJob.id(), userId, createdJob.rowVersion()).orElseThrow();
    }
}
