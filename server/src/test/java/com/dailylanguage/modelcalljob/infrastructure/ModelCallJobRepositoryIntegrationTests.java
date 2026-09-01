package com.dailylanguage.modelcalljob.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.languageprofile.infrastructure.LanguageProfileRepository;
import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.domain.NewModelCallJob;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.user.infrastructure.UserRepository;

@SpringBootTest(properties = "app.registration-enabled=true")
@Transactional
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class ModelCallJobRepositoryIntegrationTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LanguageProfileRepository languageProfileRepository;

    @Autowired
    private ModelCallJobRepository modelCallJobRepository;

    @Test
    void createsAndReadsTypedJobThroughOwnerScopedIdentity() {
        UUID userId = userRepository.create();
        LanguageProfileIdentity profile = languageProfileRepository.create(userId, "en").orElseThrow();
        UUID workflowId = UUID.randomUUID();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusHours(1);
        NewModelCallJob newJob = new NewModelCallJob(
                userId,
                Optional.of(profile.id()),
                ModelPurpose.CONVERSATION,
                ModelOperation.TEXT_GENERATION,
                Optional.of(new ProviderId("deepseek")),
                Optional.of(new ModelId("deepseek-chat")),
                workflowId,
                "GENERATE_REPLY",
                3,
                expiresAt);

        ModelCallJob createdJob = modelCallJobRepository.create(newJob);

        assertThat(createdJob.id().version()).isEqualTo(7);
        assertThat(createdJob.userId()).isEqualTo(userId);
        assertThat(createdJob.languageProfileId()).contains(profile.id());
        assertThat(createdJob.providerId()).contains(new ProviderId("deepseek"));
        assertThat(createdJob.modelId()).contains(new ModelId("deepseek-chat"));
        assertThat(createdJob.workflowId()).isEqualTo(workflowId);
        assertThat(createdJob.workflowVersion()).isEqualTo(3);
        assertThat(createdJob.executionStatus()).isEqualTo(ModelCallJob.ExecutionStatus.CREATED);
        assertThat(createdJob.consumptionStatus()).isEqualTo(ModelCallJob.ConsumptionStatus.NOT_READY);
        assertThat(createdJob.rowVersion()).isZero();
        assertThat(createdJob.completedAt()).isEmpty();
        assertThat(modelCallJobRepository.findByIdAndUserId(createdJob.id(), userId)).contains(createdJob);
    }

    @Test
    void hidesJobFromAnotherUser() {
        UUID ownerId = userRepository.create();
        UUID otherUserId = userRepository.create();
        NewModelCallJob newJob = new NewModelCallJob(
                ownerId, Optional.empty(), ModelPurpose.PLANNING, ModelOperation.TEXT_GENERATION,
                Optional.empty(), Optional.empty(), UUID.randomUUID(), "GENERATE_TASK", 0,
                OffsetDateTime.now().plusHours(1));

        ModelCallJob createdJob = modelCallJobRepository.create(newJob);

        assertThat(modelCallJobRepository.findByIdAndUserId(createdJob.id(), otherUserId)).isEmpty();
        assertThat(modelCallJobRepository.findByIdAndUserId(createdJob.id(), ownerId)).contains(createdJob);
    }

    @Test
    void startsExecutionOnlyOnceForExpectedVersion() {
        UUID userId = userRepository.create();
        NewModelCallJob newJob = new NewModelCallJob(
                userId, Optional.empty(), ModelPurpose.PLANNING, ModelOperation.TEXT_GENERATION,
                Optional.empty(), Optional.empty(), UUID.randomUUID(), "GENERATE_TASK", 0,
                OffsetDateTime.now().plusHours(1));
        ModelCallJob createdJob = modelCallJobRepository.create(newJob);

        Optional<ModelCallJob> started = modelCallJobRepository.tryStartExecution(
                createdJob.id(), userId, createdJob.rowVersion());

        assertThat(started).isPresent().get()
                .satisfies(job -> {
                    assertThat(job.executionStatus()).isEqualTo(ModelCallJob.ExecutionStatus.RUNNING);
                    assertThat(job.consumptionStatus()).isEqualTo(ModelCallJob.ConsumptionStatus.NOT_READY);
                    assertThat(job.rowVersion()).isEqualTo(1);
                    assertThat(job.completedAt()).isEmpty();
                });
        assertThat(modelCallJobRepository.tryStartExecution(createdJob.id(), userId, createdJob.rowVersion()))
                .isEmpty();
        assertThat(modelCallJobRepository.findByIdAndUserId(createdJob.id(), userId))
                .isEqualTo(started);
    }

    @Test
    void doesNotStartExecutionForAnotherUser() {
        UUID ownerId = userRepository.create();
        UUID otherUserId = userRepository.create();
        NewModelCallJob newJob = new NewModelCallJob(
                ownerId, Optional.empty(), ModelPurpose.PLANNING, ModelOperation.TEXT_GENERATION,
                Optional.empty(), Optional.empty(), UUID.randomUUID(), "GENERATE_TASK", 0,
                OffsetDateTime.now().plusHours(1));
        ModelCallJob createdJob = modelCallJobRepository.create(newJob);

        assertThat(modelCallJobRepository.tryStartExecution(
                createdJob.id(), otherUserId, createdJob.rowVersion())).isEmpty();
        assertThat(modelCallJobRepository.findByIdAndUserId(createdJob.id(), ownerId)).contains(createdJob);
    }

    @Test
    void rejectsNegativeExpectedRowVersion() {
        assertThatThrownBy(() -> modelCallJobRepository.tryStartExecution(
                UUID.randomUUID(), UUID.randomUUID(), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expectedRowVersion must not be negative");
    }
}
