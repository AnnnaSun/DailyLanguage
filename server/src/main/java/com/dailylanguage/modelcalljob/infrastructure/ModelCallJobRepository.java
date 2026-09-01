package com.dailylanguage.modelcalljob.infrastructure;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.domain.NewModelCallJob;
import com.dailylanguage.modelgateway.result.ModelUsage;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;

@Repository
public class ModelCallJobRepository {

    private final ModelCallJobMapper modelCallJobMapper;

    public ModelCallJobRepository(ModelCallJobMapper modelCallJobMapper) {
        this.modelCallJobMapper = modelCallJobMapper;
    }

    public ModelCallJob create(NewModelCallJob newJob) {
        Objects.requireNonNull(newJob, "newJob must not be null");
        NewModelCallJobRow row = new NewModelCallJobRow(
                newJob.userId(),
                newJob.languageProfileId().orElse(null),
                newJob.modelPurpose().name(),
                newJob.modelOperation().name(),
                newJob.providerId().map(ProviderId::value).orElse(null),
                newJob.modelId().map(ModelId::value).orElse(null),
                newJob.workflowId(),
                newJob.workflowStepId(),
                newJob.workflowVersion(),
                newJob.expiresAt());
        return toDomain(modelCallJobMapper.insertAndReturn(row));
    }

    public Optional<ModelCallJob> findByIdAndUserId(UUID jobId, UUID userId) {
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        return modelCallJobMapper.findByIdAndUserId(jobId, userId)
                .map(ModelCallJobRepository::toDomain);
    }

    public Optional<ModelCallJob> tryStartExecution(UUID jobId, UUID userId, long expectedRowVersion) {
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        if (expectedRowVersion < 0) {
            throw new IllegalArgumentException("expectedRowVersion must not be negative");
        }
        return modelCallJobMapper.tryStartExecution(jobId, userId, expectedRowVersion)
                .map(ModelCallJobRepository::toDomain);
    }

    @Transactional
    public Optional<ModelCallJob> tryRecordTextGenerationSuccess(
            UUID jobId,
            UUID userId,
            long expectedRowVersion,
            TextGenerationResponse response) {
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        if (expectedRowVersion < 0) {
            throw new IllegalArgumentException("expectedRowVersion must not be negative");
        }
        Objects.requireNonNull(response, "response must not be null");

        TextGenerationSuccessRow success = new TextGenerationSuccessRow(
                jobId,
                userId,
                expectedRowVersion,
                response.providerId().value(),
                response.modelId().value(),
                response.text(),
                response.finishReason().name(),
                response.usage().map(ModelUsage::inputTokens).orElse(null),
                response.usage().map(ModelUsage::outputTokens).orElse(null));
        Optional<StoredModelCallJob> completedJob = modelCallJobMapper.tryCompleteTextGeneration(success);
        if (completedJob.isEmpty()) {
            return Optional.empty();
        }
        if (modelCallJobMapper.insertTextGenerationResult(success) != 1) {
            throw new IllegalStateException("text generation result was not inserted");
        }
        return completedJob.map(ModelCallJobRepository::toDomain);
    }

    private static ModelCallJob toDomain(StoredModelCallJob job) {
        return new ModelCallJob(
                job.id(), job.userId(), Optional.ofNullable(job.languageProfileId()),
                ModelPurpose.valueOf(job.modelPurpose()), ModelOperation.valueOf(job.modelOperation()),
                Optional.ofNullable(job.providerId()).map(ProviderId::new),
                Optional.ofNullable(job.modelId()).map(ModelId::new),
                job.workflowId(), job.workflowStepId(), job.workflowVersion(),
                ModelCallJob.ExecutionStatus.valueOf(job.executionStatus()),
                ModelCallJob.ConsumptionStatus.valueOf(job.consumptionStatus()),
                job.rowVersion(), job.createdAt(), Optional.ofNullable(job.completedAt()), job.expiresAt());
    }
}

record NewModelCallJobRow(
        UUID userId, UUID languageProfileId, String modelPurpose, String modelOperation,
        String providerId, String modelId, UUID workflowId, String workflowStepId,
        long workflowVersion, OffsetDateTime expiresAt) {
}

record StoredModelCallJob(
        UUID id, UUID userId, UUID languageProfileId, String modelPurpose, String modelOperation,
        String providerId, String modelId, UUID workflowId, String workflowStepId, long workflowVersion,
        String executionStatus, String consumptionStatus, long rowVersion,
        OffsetDateTime createdAt, OffsetDateTime completedAt, OffsetDateTime expiresAt) {
}

record TextGenerationSuccessRow(
        UUID jobId, UUID userId, long expectedRowVersion,
        String providerId, String modelId, String generatedText, String finishReason,
        Long inputTokens, Long outputTokens) {
}
