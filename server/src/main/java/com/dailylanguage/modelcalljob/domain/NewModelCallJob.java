package com.dailylanguage.modelcalljob.domain;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;

public record NewModelCallJob(
        UUID userId,
        Optional<UUID> languageProfileId,
        ModelPurpose modelPurpose,
        ModelOperation modelOperation,
        Optional<ProviderId> providerId,
        Optional<ModelId> modelId,
        UUID workflowId,
        String workflowStepId,
        long workflowVersion,
        OffsetDateTime expiresAt) {

    public NewModelCallJob {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
        Objects.requireNonNull(modelPurpose, "modelPurpose must not be null");
        Objects.requireNonNull(modelOperation, "modelOperation must not be null");
        ModelCallJob.validateRoutePair(providerId, modelId);
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        ModelCallJob.validateWorkflow(workflowStepId, workflowVersion);
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }
}
