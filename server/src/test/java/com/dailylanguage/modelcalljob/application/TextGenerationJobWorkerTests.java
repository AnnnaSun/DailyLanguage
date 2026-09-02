package com.dailylanguage.modelcalljob.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dailylanguage.modelcalljob.application.TextGenerationJobWorker.WorkerOutcome;
import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.infrastructure.ModelCallJobRepository;
import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.result.ModelFailure;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.result.ModelResult;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationPort;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;
import com.dailylanguage.modelgateway.text.TextMessage;
import com.dailylanguage.modelgateway.text.TextOutputSpecification;

class TextGenerationJobWorkerTests {

    private static final ProviderId PROVIDER_ID = new ProviderId("deepseek");
    private static final ModelId MODEL_ID = new ModelId("deepseek-chat");
    // TransientProviderCredential 不实现 equals（BYOK 语义），stub 必须复用同一实例。
    private static final TextGenerationRequest REQUEST = new TextGenerationRequest(
            ModelPurpose.PLANNING,
            List.of(new TextMessage(TextMessage.Role.USER, "Plan today's practice.")),
            TextOutputSpecification.plainText());
    private static final TransientProviderCredential CREDENTIAL =
            new TransientProviderCredential(PROVIDER_ID, "not-sent-to-provider");

    private final ModelCallJobRepository modelCallJobRepository =
            org.mockito.Mockito.mock(ModelCallJobRepository.class);
    private final TextGenerationPort textGenerationPort =
            org.mockito.Mockito.mock(TextGenerationPort.class);
    private final TextGenerationJobWorker worker =
            new TextGenerationJobWorker(modelCallJobRepository, textGenerationPort);

    @Test
    void claimLostSkipsProviderCallAndTerminalWrites() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(modelCallJobRepository.tryStartExecution(jobId, userId, 0L)).thenReturn(Optional.empty());
        TextGenerationJobWorkItem workItem = workItem(jobId, userId);

        WorkerOutcome outcome = worker.execute(workItem);

        assertThat(outcome).isEqualTo(WorkerOutcome.CLAIM_LOST);
        verifyNoInteractions(textGenerationPort);
        verify(modelCallJobRepository).tryStartExecution(jobId, userId, 0L);
        verifyNoMoreInteractions(modelCallJobRepository);
    }

    @Test
    void recordsTypedSuccessWithRunningRowVersion() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ModelCallJob runningJob = job(jobId, userId, ModelCallJob.ExecutionStatus.RUNNING, 1L);
        when(modelCallJobRepository.tryStartExecution(jobId, userId, 0L))
                .thenReturn(Optional.of(runningJob));
        TextGenerationResponse response = textGenerationResponse();
        when(textGenerationPort.generateText(REQUEST, CREDENTIAL))
                .thenReturn(ModelResult.success(response));
        when(modelCallJobRepository.tryRecordTextGenerationSuccess(
                jobId, userId, 1L, response))
                .thenReturn(Optional.of(
                        job(jobId, userId, ModelCallJob.ExecutionStatus.SUCCEEDED, 2L)));

        WorkerOutcome outcome = worker.execute(workItem(jobId, userId));

        assertThat(outcome).isEqualTo(WorkerOutcome.SUCCEEDED);
        verify(modelCallJobRepository).tryRecordTextGenerationSuccess(jobId, userId, 1L, response);
    }

    @Test
    void recordsTypedFailureAsFailed() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(modelCallJobRepository.tryStartExecution(jobId, userId, 0L))
                .thenReturn(Optional.of(job(jobId, userId, ModelCallJob.ExecutionStatus.RUNNING, 1L)));
        ModelFailure failure = ModelFailure.forRoute(
                ModelFailureKind.PROVIDER_FAILURE, PROVIDER_ID, MODEL_ID);
        when(textGenerationPort.generateText(REQUEST, CREDENTIAL))
                .thenReturn(ModelResult.failure(failure));
        when(modelCallJobRepository.tryRecordFailure(jobId, userId, 1L, failure))
                .thenReturn(Optional.of(job(
                        jobId, userId, ModelCallJob.ExecutionStatus.FAILED, 2L, Optional.of(failure))));

        WorkerOutcome outcome = worker.execute(workItem(jobId, userId));

        assertThat(outcome).isEqualTo(WorkerOutcome.FAILED);
        verify(modelCallJobRepository).tryRecordFailure(jobId, userId, 1L, failure);
    }

    @Test
    void recordsTimeoutFailureAsTimedOut() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(modelCallJobRepository.tryStartExecution(jobId, userId, 0L))
                .thenReturn(Optional.of(job(jobId, userId, ModelCallJob.ExecutionStatus.RUNNING, 1L)));
        ModelFailure timeout = ModelFailure.forRoute(
                ModelFailureKind.TIMEOUT, PROVIDER_ID, MODEL_ID);
        when(textGenerationPort.generateText(REQUEST, CREDENTIAL))
                .thenReturn(ModelResult.failure(timeout));
        when(modelCallJobRepository.tryRecordFailure(jobId, userId, 1L, timeout))
                .thenReturn(Optional.of(job(
                        jobId, userId, ModelCallJob.ExecutionStatus.TIMED_OUT, 2L, Optional.of(timeout))));

        WorkerOutcome outcome = worker.execute(workItem(jobId, userId));

        assertThat(outcome).isEqualTo(WorkerOutcome.TIMED_OUT);
    }

    @Test
    void unexpectedPortExceptionBecomesOutcomeUnknown() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(modelCallJobRepository.tryStartExecution(jobId, userId, 0L))
                .thenReturn(Optional.of(job(jobId, userId, ModelCallJob.ExecutionStatus.RUNNING, 1L)));
        when(textGenerationPort.generateText(REQUEST, CREDENTIAL))
                .thenThrow(new IllegalStateException("model call wait was interrupted"));
        when(modelCallJobRepository.tryRecordOutcomeUnknown(jobId, userId, 1L))
                .thenReturn(Optional.of(
                        job(jobId, userId, ModelCallJob.ExecutionStatus.OUTCOME_UNKNOWN, 2L)));

        WorkerOutcome outcome = worker.execute(workItem(jobId, userId));

        assertThat(outcome).isEqualTo(WorkerOutcome.OUTCOME_UNKNOWN);
        verify(modelCallJobRepository).tryRecordOutcomeUnknown(jobId, userId, 1L);
    }

    @Test
    void outcomeUnknownWriteFailureLeavesJobRunningAndIsReported() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(modelCallJobRepository.tryStartExecution(jobId, userId, 0L))
                .thenReturn(Optional.of(job(jobId, userId, ModelCallJob.ExecutionStatus.RUNNING, 1L)));
        when(textGenerationPort.generateText(REQUEST, CREDENTIAL))
                .thenThrow(new IllegalStateException("model call executor rejected the provider task"));
        when(modelCallJobRepository.tryRecordOutcomeUnknown(jobId, userId, 1L))
                .thenThrow(new RuntimeException("database unavailable"));

        WorkerOutcome outcome = worker.execute(workItem(jobId, userId));

        assertThat(outcome).isEqualTo(WorkerOutcome.OUTCOME_UNKNOWN_UNRECORDED);
    }

    @Test
    void outcomeUnknownWriteLostWhenVersionRaceDropsTransition() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(modelCallJobRepository.tryStartExecution(jobId, userId, 0L))
                .thenReturn(Optional.of(job(jobId, userId, ModelCallJob.ExecutionStatus.RUNNING, 1L)));
        when(textGenerationPort.generateText(REQUEST, CREDENTIAL))
                .thenThrow(new IllegalStateException("model call outcome is unknown"));
        when(modelCallJobRepository.tryRecordOutcomeUnknown(jobId, userId, 1L))
                .thenReturn(Optional.empty());

        WorkerOutcome outcome = worker.execute(workItem(jobId, userId));

        assertThat(outcome).isEqualTo(WorkerOutcome.TERMINAL_WRITE_LOST);
        verify(modelCallJobRepository).tryRecordOutcomeUnknown(jobId, userId, 1L);
    }

    @Test
    void errorBecomesOutcomeUnknownAndIsRethrown() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(modelCallJobRepository.tryStartExecution(jobId, userId, 0L))
                .thenReturn(Optional.of(job(jobId, userId, ModelCallJob.ExecutionStatus.RUNNING, 1L)));
        when(textGenerationPort.generateText(REQUEST, CREDENTIAL))
                .thenThrow(new AssertionError("worker exhausted"));
        when(modelCallJobRepository.tryRecordOutcomeUnknown(jobId, userId, 1L))
                .thenReturn(Optional.of(
                        job(jobId, userId, ModelCallJob.ExecutionStatus.OUTCOME_UNKNOWN, 2L)));

        TextGenerationJobWorkItem workItem = workItem(jobId, userId);
        assertThatThrownBy(() -> worker.execute(workItem))
                .isInstanceOf(AssertionError.class)
                .hasMessage("worker exhausted");
        verify(modelCallJobRepository).tryRecordOutcomeUnknown(jobId, userId, 1L);
    }

    @Test
    void terminalWriteLostWhenVersionRaceDropsResult() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(modelCallJobRepository.tryStartExecution(jobId, userId, 0L))
                .thenReturn(Optional.of(job(jobId, userId, ModelCallJob.ExecutionStatus.RUNNING, 1L)));
        TextGenerationResponse response = textGenerationResponse();
        when(textGenerationPort.generateText(REQUEST, CREDENTIAL))
                .thenReturn(ModelResult.success(response));
        when(modelCallJobRepository.tryRecordTextGenerationSuccess(jobId, userId, 1L, response))
                .thenReturn(Optional.empty());

        WorkerOutcome outcome = worker.execute(workItem(jobId, userId));

        assertThat(outcome).isEqualTo(WorkerOutcome.TERMINAL_WRITE_LOST);
    }

    @Test
    void knownResultPersistenceFailureDegradesToOutcomeUnknown() {
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(modelCallJobRepository.tryStartExecution(jobId, userId, 0L))
                .thenReturn(Optional.of(job(jobId, userId, ModelCallJob.ExecutionStatus.RUNNING, 1L)));
        when(textGenerationPort.generateText(REQUEST, CREDENTIAL))
                .thenReturn(ModelResult.success(textGenerationResponse()));
        when(modelCallJobRepository.tryRecordTextGenerationSuccess(
                jobId, userId, 1L, textGenerationResponse()))
                .thenThrow(new RuntimeException("database unavailable"));
        when(modelCallJobRepository.tryRecordOutcomeUnknown(jobId, userId, 1L))
                .thenReturn(Optional.of(
                        job(jobId, userId, ModelCallJob.ExecutionStatus.OUTCOME_UNKNOWN, 2L)));

        WorkerOutcome outcome = worker.execute(workItem(jobId, userId));

        assertThat(outcome).isEqualTo(WorkerOutcome.OUTCOME_UNKNOWN);
        verify(modelCallJobRepository).tryRecordOutcomeUnknown(jobId, userId, 1L);
    }

    private static TextGenerationJobWorkItem workItem(UUID jobId, UUID userId) {
        return new TextGenerationJobWorkItem(jobId, userId, 0L, REQUEST, CREDENTIAL);
    }

    private static TextGenerationResponse textGenerationResponse() {
        return new TextGenerationResponse(
                PROVIDER_ID,
                MODEL_ID,
                "Generated text.",
                TextGenerationResponse.FinishReason.COMPLETED,
                Optional.empty());
    }

    private static ModelCallJob job(
            UUID jobId, UUID userId, ModelCallJob.ExecutionStatus executionStatus, long rowVersion) {
        return job(jobId, userId, executionStatus, rowVersion, Optional.empty());
    }

    private static ModelCallJob job(
            UUID jobId,
            UUID userId,
            ModelCallJob.ExecutionStatus executionStatus,
            long rowVersion,
            Optional<ModelFailure> failure) {
        return new ModelCallJob(
                jobId,
                userId,
                Optional.empty(),
                ModelPurpose.PLANNING,
                ModelOperation.TEXT_GENERATION,
                Optional.empty(),
                Optional.empty(),
                jobId,
                "GENERATE_TASK",
                0,
                executionStatus,
                ModelCallJob.ConsumptionStatus.NOT_READY,
                failure,
                rowVersion,
                OffsetDateTime.now().minusMinutes(1),
                executionStatus == ModelCallJob.ExecutionStatus.CREATED
                        || executionStatus == ModelCallJob.ExecutionStatus.RUNNING
                        ? Optional.empty()
                        : Optional.of(OffsetDateTime.now()),
                OffsetDateTime.now().plusHours(1));
    }
}
