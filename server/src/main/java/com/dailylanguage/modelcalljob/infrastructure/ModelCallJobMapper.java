package com.dailylanguage.modelcalljob.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface ModelCallJobMapper {

    StoredModelCallJob insertAndReturn(NewModelCallJobRow job);

    Optional<StoredModelCallJob> findByIdAndUserId(
            @Param("jobId") UUID jobId,
            @Param("userId") UUID userId);

    Optional<StoredTextGenerationResult> findTextGenerationResultByJobIdAndUserId(
            @Param("jobId") UUID jobId,
            @Param("userId") UUID userId);

    Optional<StoredModelCallJob> tryStartExecution(
            @Param("jobId") UUID jobId,
            @Param("userId") UUID userId,
            @Param("expectedRowVersion") long expectedRowVersion);

    Optional<StoredModelCallJob> tryCompleteTextGeneration(TextGenerationSuccessRow success);

    int insertTextGenerationResult(TextGenerationSuccessRow success);

    Optional<StoredModelCallJob> tryCompleteFailure(ModelCallJobFailureRow failure);

    Optional<StoredModelCallJob> tryConsumeSucceededResult(
            @Param("jobId") UUID jobId,
            @Param("userId") UUID userId,
            @Param("currentWorkflowVersion") long currentWorkflowVersion,
            @Param("expectedRowVersion") long expectedRowVersion);

    Optional<StoredModelCallJob> tryMarkSucceededResultStale(
            @Param("jobId") UUID jobId,
            @Param("userId") UUID userId,
            @Param("currentWorkflowVersion") long currentWorkflowVersion,
            @Param("expectedRowVersion") long expectedRowVersion);
}
