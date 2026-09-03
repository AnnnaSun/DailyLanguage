package com.dailylanguage.modelcalljob.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.domain.NewModelCallJob;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.user.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "app.registration-enabled=true")
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class ModelCallJobOutcomeUnknownRepositoryIntegrationTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ModelCallJobRepository modelCallJobRepository;

    @Test
    void recordsOutcomeUnknownFromRunningJobWithoutFailureDetails() {
        UUID userId = userRepository.create();
        ModelCallJob runningJob = createAndStartJob(userId);

        ModelCallJob completedJob = modelCallJobRepository.tryRecordOutcomeUnknown(
                runningJob.id(), userId, runningJob.rowVersion()).orElseThrow();

        assertThat(completedJob.executionStatus()).isEqualTo(ModelCallJob.ExecutionStatus.OUTCOME_UNKNOWN);
        assertThat(completedJob.failure()).isEmpty();
        assertThat(completedJob.completedAt()).isPresent();
        assertThat(completedJob.rowVersion()).isEqualTo(runningJob.rowVersion() + 1);
    }

    @Test
    void createdJobWithoutRunningClaimCannotBecomeOutcomeUnknown() {
        UUID userId = userRepository.create();
        ModelCallJob createdJob = createJob(userId);

        assertThat(modelCallJobRepository.tryRecordOutcomeUnknown(
                createdJob.id(), userId, createdJob.rowVersion())).isEmpty();
        assertThat(modelCallJobRepository.findByIdAndUserId(createdJob.id(), userId))
                .contains(createdJob);
    }

    @Test
    void staleVersionCannotOverwriteOutcomeUnknown() {
        UUID userId = userRepository.create();
        ModelCallJob runningJob = createAndStartJob(userId);
        modelCallJobRepository.tryRecordOutcomeUnknown(
                runningJob.id(), userId, runningJob.rowVersion()).orElseThrow();

        assertThat(modelCallJobRepository.tryRecordOutcomeUnknown(
                runningJob.id(), userId, runningJob.rowVersion())).isEmpty();
        assertThat(modelCallJobRepository.findByIdAndUserId(runningJob.id(), userId))
                .get()
                .extracting(ModelCallJob::executionStatus)
                .isEqualTo(ModelCallJob.ExecutionStatus.OUTCOME_UNKNOWN);
    }

    private ModelCallJob createJob(UUID userId) {
        NewModelCallJob newJob = new NewModelCallJob(
                userId,
                Optional.empty(),
                ModelPurpose.PLANNING,
                ModelOperation.TEXT_GENERATION,
                Optional.empty(),
                Optional.empty(),
                UUID.randomUUID(),
                "GENERATE_TASK",
                0,
                OffsetDateTime.now().plusHours(1));
        return modelCallJobRepository.create(newJob);
    }

    private ModelCallJob createAndStartJob(UUID userId) {
        ModelCallJob createdJob = createJob(userId);
        return modelCallJobRepository.tryStartExecution(
                createdJob.id(), userId, createdJob.rowVersion()).orElseThrow();
    }
}
