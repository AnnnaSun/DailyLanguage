package com.dailylanguage.modelcalljob.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.domain.NewModelCallJob;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.user.infrastructure.UserRepository;

@SpringBootTest(properties = "app.registration-enabled=true")
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class ModelCallJobSubmissionRejectionRepositoryIntegrationTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ModelCallJobRepository modelCallJobRepository;

    @Test
    void recordsSubmissionRejectionFromCreatedJob() {
        UUID userId = userRepository.create();
        ModelCallJob createdJob = createJob(userId);

        ModelCallJob rejectedJob = modelCallJobRepository.tryRecordSubmissionRejection(
                createdJob.id(), userId, createdJob.rowVersion()).orElseThrow();

        assertThat(rejectedJob.executionStatus())
                .isEqualTo(ModelCallJob.ExecutionStatus.SUBMISSION_REJECTED);
        assertThat(rejectedJob.consumptionStatus())
                .isEqualTo(ModelCallJob.ConsumptionStatus.NOT_READY);
        assertThat(rejectedJob.failure()).isEmpty();
        assertThat(rejectedJob.completedAt()).isPresent();
        assertThat(rejectedJob.rowVersion()).isEqualTo(createdJob.rowVersion() + 1);
    }

    @Test
    void hidesSubmissionRejectionFromAnotherUser() {
        UUID ownerId = userRepository.create();
        UUID otherUserId = userRepository.create();
        ModelCallJob createdJob = createJob(ownerId);

        assertThat(modelCallJobRepository.tryRecordSubmissionRejection(
                createdJob.id(), otherUserId, createdJob.rowVersion())).isEmpty();
        assertThat(modelCallJobRepository.findByIdAndUserId(createdJob.id(), ownerId))
                .contains(createdJob);
    }

    @Test
    void staleVersionCannotOverwriteSubmissionRejection() {
        UUID userId = userRepository.create();
        ModelCallJob createdJob = createJob(userId);
        ModelCallJob rejectedJob = modelCallJobRepository.tryRecordSubmissionRejection(
                createdJob.id(), userId, createdJob.rowVersion()).orElseThrow();

        assertThat(modelCallJobRepository.tryRecordSubmissionRejection(
                createdJob.id(), userId, createdJob.rowVersion())).isEmpty();
        assertThat(modelCallJobRepository.findByIdAndUserId(createdJob.id(), userId))
                .contains(rejectedJob);
    }

    @Test
    void runningJobCannotBecomeSubmissionRejected() {
        UUID userId = userRepository.create();
        ModelCallJob createdJob = createJob(userId);
        ModelCallJob runningJob = modelCallJobRepository.tryStartExecution(
                createdJob.id(), userId, createdJob.rowVersion()).orElseThrow();

        assertThat(modelCallJobRepository.tryRecordSubmissionRejection(
                runningJob.id(), userId, runningJob.rowVersion())).isEmpty();
        assertThat(modelCallJobRepository.findByIdAndUserId(runningJob.id(), userId))
                .contains(runningJob);
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
}
