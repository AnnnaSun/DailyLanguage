package com.dailylanguage.modelcalljob.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dailylanguage.modelgateway.result.ModelFailure;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;

class ModelCallJobTests {

    @Test
    void rejectsPartialRouteBeforePersistence() {
        assertThatThrownBy(() -> new NewModelCallJob(
                        UUID.randomUUID(),
                        Optional.empty(),
                        ModelPurpose.PLANNING,
                        ModelOperation.TEXT_GENERATION,
                        Optional.of(new ProviderId("deepseek")),
                        Optional.empty(),
                        UUID.randomUUID(),
                        "GENERATE_TASK",
                        0,
                        OffsetDateTime.now().plusHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("providerId and modelId must both be present or absent");
    }

    @Test
    void rejectsInvalidWorkflowReferenceBeforePersistence() {
        assertThatThrownBy(() -> new NewModelCallJob(
                        UUID.randomUUID(), Optional.empty(), ModelPurpose.PLANNING,
                        ModelOperation.TEXT_GENERATION, Optional.empty(), Optional.empty(),
                        UUID.randomUUID(), " GENERATE_TASK", 0, OffsetDateTime.now().plusHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("workflowStepId must not be blank or contain surrounding whitespace");

        assertThatThrownBy(() -> new NewModelCallJob(
                        UUID.randomUUID(), Optional.empty(), ModelPurpose.PLANNING,
                        ModelOperation.TEXT_GENERATION, Optional.empty(), Optional.empty(),
                        UUID.randomUUID(), "GENERATE_TASK", -1, OffsetDateTime.now().plusHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("workflowVersion must not be negative");
    }

    @Test
    void requiresCompletionTimestampOnlyForTerminalExecution() {
        OffsetDateTime createdAt = OffsetDateTime.now();

        assertThatCode(() -> persistedJob(
                        ModelCallJob.ExecutionStatus.CREATED,
                        Optional.empty(),
                        createdAt,
                        createdAt.plusHours(1)))
                .doesNotThrowAnyException();
        assertThatCode(() -> persistedJob(
                        ModelCallJob.ExecutionStatus.SUCCEEDED,
                        Optional.of(createdAt.plusSeconds(1)),
                        createdAt,
                        createdAt.plusHours(1)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> persistedJob(
                        ModelCallJob.ExecutionStatus.SUCCEEDED,
                        Optional.empty(),
                        createdAt,
                        createdAt.plusHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("completedAt must match terminal executionStatus");
    }

    @Test
    void acceptsSubmissionRejectedAsTerminalWithoutModelFailure() {
        OffsetDateTime createdAt = OffsetDateTime.now();

        assertThatCode(() -> persistedJob(
                        ModelCallJob.ExecutionStatus.SUBMISSION_REJECTED,
                        Optional.empty(),
                        Optional.of(createdAt.plusSeconds(1)),
                        createdAt,
                        createdAt.plusHours(1)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> persistedJob(
                        ModelCallJob.ExecutionStatus.SUBMISSION_REJECTED,
                        Optional.empty(),
                        Optional.empty(),
                        createdAt,
                        createdAt.plusHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("completedAt must match terminal executionStatus");
    }

    @Test
    void rejectsInvalidPersistedTimestamps() {
        OffsetDateTime createdAt = OffsetDateTime.now();

        assertThatThrownBy(() -> persistedJob(
                        ModelCallJob.ExecutionStatus.CREATED,
                        Optional.empty(),
                        createdAt,
                        createdAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expiresAt must be after createdAt");
        assertThatThrownBy(() -> persistedJob(
                        ModelCallJob.ExecutionStatus.SUCCEEDED,
                        Optional.of(createdAt.minusSeconds(1)),
                        createdAt,
                        createdAt.plusHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("completedAt must not be before createdAt");
    }

    @Test
    void requiresTypedFailureToMatchTerminalExecutionStatus() {
        OffsetDateTime createdAt = OffsetDateTime.now();
        ModelFailure providerFailure = ModelFailure.forRoute(
                ModelFailureKind.PROVIDER_FAILURE,
                new ProviderId("deepseek"),
                new ModelId("deepseek-chat"));
        ModelFailure timeout = ModelFailure.forRoute(
                ModelFailureKind.TIMEOUT,
                new ProviderId("deepseek"),
                new ModelId("deepseek-chat"));

        assertThatCode(() -> persistedJob(
                        ModelCallJob.ExecutionStatus.FAILED,
                        Optional.of(providerFailure),
                        Optional.of(createdAt.plusSeconds(1)),
                        createdAt,
                        createdAt.plusHours(1)))
                .doesNotThrowAnyException();
        assertThatCode(() -> persistedJob(
                        ModelCallJob.ExecutionStatus.TIMED_OUT,
                        Optional.of(timeout),
                        Optional.of(createdAt.plusSeconds(1)),
                        createdAt,
                        createdAt.plusHours(1)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> persistedJob(
                        ModelCallJob.ExecutionStatus.FAILED,
                        Optional.empty(),
                        Optional.of(createdAt.plusSeconds(1)),
                        createdAt,
                        createdAt.plusHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("failure must match FAILED or TIMED_OUT executionStatus");
        assertThatThrownBy(() -> persistedJob(
                        ModelCallJob.ExecutionStatus.FAILED,
                        Optional.of(timeout),
                        Optional.of(createdAt.plusSeconds(1)),
                        createdAt,
                        createdAt.plusHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("TIMEOUT failure requires TIMED_OUT executionStatus");
    }

    private static ModelCallJob persistedJob(
            ModelCallJob.ExecutionStatus executionStatus,
            Optional<OffsetDateTime> completedAt,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt) {
        return persistedJob(
                executionStatus, Optional.empty(), completedAt, createdAt, expiresAt);
    }

    private static ModelCallJob persistedJob(
            ModelCallJob.ExecutionStatus executionStatus,
            Optional<ModelFailure> failure,
            Optional<OffsetDateTime> completedAt,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt) {
        return new ModelCallJob(
                UUID.randomUUID(), UUID.randomUUID(), Optional.empty(),
                ModelPurpose.PLANNING, ModelOperation.TEXT_GENERATION,
                Optional.of(new ProviderId("deepseek")), Optional.of(new ModelId("deepseek-chat")),
                UUID.randomUUID(), "GENERATE_TASK", 0,
                executionStatus, ModelCallJob.ConsumptionStatus.NOT_READY, failure, 0,
                createdAt, completedAt, expiresAt);
    }
}
