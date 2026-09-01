package com.dailylanguage.modelcalljob.infrastructure;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.domain.NewModelCallJob;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;

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
