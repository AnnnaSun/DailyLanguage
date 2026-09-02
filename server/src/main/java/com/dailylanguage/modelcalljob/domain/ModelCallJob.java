package com.dailylanguage.modelcalljob.domain;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.dailylanguage.modelgateway.result.ModelFailure;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;

public record ModelCallJob(
        UUID id,
        UUID userId,
        Optional<UUID> languageProfileId,
        ModelPurpose modelPurpose,
        ModelOperation modelOperation,
        Optional<ProviderId> providerId,
        Optional<ModelId> modelId,
        UUID workflowId,
        String workflowStepId,
        long workflowVersion,
        ExecutionStatus executionStatus,
        ConsumptionStatus consumptionStatus,
        Optional<ModelFailure> failure,
        long rowVersion,
        OffsetDateTime createdAt,
        Optional<OffsetDateTime> completedAt,
        OffsetDateTime expiresAt) {

    public ModelCallJob {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
        Objects.requireNonNull(modelPurpose, "modelPurpose must not be null");
        Objects.requireNonNull(modelOperation, "modelOperation must not be null");
        validateRoutePair(providerId, modelId);
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        validateWorkflow(workflowStepId, workflowVersion);
        Objects.requireNonNull(executionStatus, "executionStatus must not be null");
        Objects.requireNonNull(consumptionStatus, "consumptionStatus must not be null");
        validateFailure(executionStatus, failure);
        if (rowVersion < 0) {
            throw new IllegalArgumentException("rowVersion must not be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        if (completedAt.filter(value -> value.isBefore(createdAt)).isPresent()) {
            throw new IllegalArgumentException("completedAt must not be before createdAt");
        }
        if (executionStatus.isTerminal() != completedAt.isPresent()) {
            throw new IllegalArgumentException("completedAt must match terminal executionStatus");
        }
    }

    static void validateRoutePair(Optional<ProviderId> providerId, Optional<ModelId> modelId) {
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(modelId, "modelId must not be null");
        if (providerId.isPresent() != modelId.isPresent()) {
            throw new IllegalArgumentException("providerId and modelId must both be present or absent");
        }
    }

    static void validateWorkflow(String workflowStepId, long workflowVersion) {
        Objects.requireNonNull(workflowStepId, "workflowStepId must not be null");
        if (workflowStepId.isBlank() || !workflowStepId.equals(workflowStepId.strip())) {
            throw new IllegalArgumentException("workflowStepId must not be blank or contain surrounding whitespace");
        }
        if (workflowVersion < 0) {
            throw new IllegalArgumentException("workflowVersion must not be negative");
        }
    }

    private static void validateFailure(ExecutionStatus executionStatus, Optional<ModelFailure> failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        boolean requiresFailure = executionStatus == ExecutionStatus.FAILED
                || executionStatus == ExecutionStatus.TIMED_OUT;
        if (requiresFailure != failure.isPresent()) {
            throw new IllegalArgumentException("failure must match FAILED or TIMED_OUT executionStatus");
        }
        if (executionStatus == ExecutionStatus.TIMED_OUT
                && failure.orElseThrow().kind() != ModelFailureKind.TIMEOUT) {
            throw new IllegalArgumentException("TIMED_OUT executionStatus requires TIMEOUT failure");
        }
        if (executionStatus == ExecutionStatus.FAILED
                && failure.orElseThrow().kind() == ModelFailureKind.TIMEOUT) {
            throw new IllegalArgumentException("TIMEOUT failure requires TIMED_OUT executionStatus");
        }
    }

    public enum ExecutionStatus {
        CREATED, RUNNING, SUCCEEDED, FAILED, TIMED_OUT, OUTCOME_UNKNOWN;

        boolean isTerminal() {
            return this != CREATED && this != RUNNING;
        }
    }

    public enum ConsumptionStatus {
        NOT_READY, PENDING_CONFIRMATION, CONSUMED, DISCARDED, EXPIRED, STALE
    }
}
