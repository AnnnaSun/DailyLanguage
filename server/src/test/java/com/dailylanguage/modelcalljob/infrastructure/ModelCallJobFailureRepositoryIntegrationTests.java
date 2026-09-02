package com.dailylanguage.modelcalljob.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.domain.NewModelCallJob;
import com.dailylanguage.modelgateway.result.ModelFailure;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.user.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "app.registration-enabled=true")
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class ModelCallJobFailureRepositoryIntegrationTests {

    private static final ProviderId PROVIDER_ID = new ProviderId("deepseek");
    private static final ModelId MODEL_ID = new ModelId("deepseek-chat");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ModelCallJobRepository modelCallJobRepository;

    @Test
    void roundsFractionalRetryHintUpToWholeSeconds() {
        UUID userId = userRepository.create();
        ModelCallJob runningJob = createAndStartJob(userId, Optional.empty(), Optional.empty());
        Duration retryAfter = Duration.ofSeconds(12, 345_678_901);
        ModelFailure failure = ModelFailure.forRoute(
                ModelFailureKind.RATE_LIMITED,
                PROVIDER_ID,
                MODEL_ID,
                retryAfter);

        ModelCallJob completedJob = modelCallJobRepository.tryRecordFailure(
                runningJob.id(), userId, runningJob.rowVersion(), failure).orElseThrow();
        ModelFailure storedFailure = ModelFailure.forRoute(
                ModelFailureKind.RATE_LIMITED,
                PROVIDER_ID,
                MODEL_ID,
                Duration.ofSeconds(13));

        assertThat(completedJob.executionStatus()).isEqualTo(ModelCallJob.ExecutionStatus.FAILED);
        assertThat(completedJob.failure()).contains(storedFailure);
        assertThat(completedJob.providerId()).contains(PROVIDER_ID);
        assertThat(completedJob.modelId()).contains(MODEL_ID);
        assertThat(completedJob.completedAt()).isPresent();
        assertThat(completedJob.rowVersion()).isEqualTo(runningJob.rowVersion() + 1);
    }

    @Test
    void recordsTimeoutAsTimedOut() {
        UUID userId = userRepository.create();
        ModelCallJob runningJob = createAndStartJob(
                userId, Optional.of(PROVIDER_ID), Optional.of(MODEL_ID));
        ModelFailure timeout = ModelFailure.forRoute(
                ModelFailureKind.TIMEOUT,
                PROVIDER_ID,
                MODEL_ID);

        ModelCallJob completedJob = modelCallJobRepository.tryRecordFailure(
                runningJob.id(), userId, runningJob.rowVersion(), timeout).orElseThrow();

        assertThat(completedJob.executionStatus()).isEqualTo(ModelCallJob.ExecutionStatus.TIMED_OUT);
        assertThat(completedJob.failure()).contains(timeout);
    }

    @Test
    void recordsRouteLessFailureWithoutInventingRoute() {
        UUID userId = userRepository.create();
        ModelCallJob runningJob = createAndStartJob(userId, Optional.empty(), Optional.empty());
        ModelFailure failure = ModelFailure.withoutRoute(ModelFailureKind.CAPABILITY_UNAVAILABLE);

        ModelCallJob completedJob = modelCallJobRepository.tryRecordFailure(
                runningJob.id(), userId, runningJob.rowVersion(), failure).orElseThrow();

        assertThat(completedJob.executionStatus()).isEqualTo(ModelCallJob.ExecutionStatus.FAILED);
        assertThat(completedJob.failure()).contains(failure);
        assertThat(completedJob.providerId()).isEmpty();
        assertThat(completedJob.modelId()).isEmpty();
    }

    @Test
    void staleVersionCannotOverwriteStoredFailure() {
        UUID userId = userRepository.create();
        ModelCallJob runningJob = createAndStartJob(userId, Optional.empty(), Optional.empty());
        ModelFailure firstFailure = ModelFailure.forRoute(
                ModelFailureKind.REQUEST_REJECTED,
                PROVIDER_ID,
                MODEL_ID);
        ModelFailure staleFailure = ModelFailure.forRoute(
                ModelFailureKind.PROVIDER_FAILURE,
                PROVIDER_ID,
                MODEL_ID);
        modelCallJobRepository.tryRecordFailure(
                runningJob.id(), userId, runningJob.rowVersion(), firstFailure).orElseThrow();

        assertThat(modelCallJobRepository.tryRecordFailure(
                runningJob.id(), userId, runningJob.rowVersion(), staleFailure)).isEmpty();
        assertThat(modelCallJobRepository.findByIdAndUserId(runningJob.id(), userId))
                .get()
                .extracting(ModelCallJob::failure)
                .isEqualTo(Optional.of(firstFailure));
    }

    @Test
    void rejectsFailureWithoutMatchingPreselectedRoute() {
        UUID userId = userRepository.create();
        ModelCallJob runningJob = createAndStartJob(
                userId,
                Optional.of(new ProviderId("openai")),
                Optional.of(new ModelId("gpt-model")));
        ModelFailure routeLessFailure = ModelFailure.withoutRoute(ModelFailureKind.CAPABILITY_UNAVAILABLE);
        ModelFailure mismatchedFailure = ModelFailure.forRoute(
                ModelFailureKind.PROVIDER_FAILURE,
                PROVIDER_ID,
                MODEL_ID);

        assertThat(modelCallJobRepository.tryRecordFailure(
                runningJob.id(), userId, runningJob.rowVersion(), routeLessFailure)).isEmpty();
        assertThat(modelCallJobRepository.tryRecordFailure(
                runningJob.id(), userId, runningJob.rowVersion(), mismatchedFailure)).isEmpty();
        assertThat(modelCallJobRepository.findByIdAndUserId(runningJob.id(), userId)).contains(runningJob);
    }

    private ModelCallJob createAndStartJob(
            UUID userId,
            Optional<ProviderId> providerId,
            Optional<ModelId> modelId) {
        NewModelCallJob newJob = new NewModelCallJob(
                userId,
                Optional.empty(),
                ModelPurpose.PLANNING,
                ModelOperation.TEXT_GENERATION,
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
}
