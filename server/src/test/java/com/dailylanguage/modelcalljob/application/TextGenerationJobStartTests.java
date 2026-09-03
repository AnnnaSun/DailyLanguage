package com.dailylanguage.modelcalljob.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.dailylanguage.modelcalljob.application.TextGenerationJobStart.StartCommand;
import com.dailylanguage.modelcalljob.application.TextGenerationJobStart.StartResult;
import com.dailylanguage.modelcalljob.application.TextGenerationJobSubmission.SubmissionOutcome;
import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.domain.NewModelCallJob;
import com.dailylanguage.modelcalljob.infrastructure.ModelCallJobRepository;
import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextMessage;
import com.dailylanguage.modelgateway.text.TextOutputSpecification;

class TextGenerationJobStartTests {

    private static final ProviderId PROVIDER_ID = new ProviderId("deepseek");
    private static final TextGenerationRequest REQUEST = new TextGenerationRequest(
            ModelPurpose.PLANNING,
            List.of(new TextMessage(TextMessage.Role.USER, "Plan today's practice.")),
            TextOutputSpecification.plainText());
    private static final TransientProviderCredential CREDENTIAL =
            new TransientProviderCredential(PROVIDER_ID, "not-sent-to-provider");

    private final ModelCallJobRepository modelCallJobRepository = mock(ModelCallJobRepository.class);
    private final TextGenerationJobSubmission submission = mock(TextGenerationJobSubmission.class);
    private final TextGenerationJobStart jobStart =
            new TextGenerationJobStart(modelCallJobRepository, submission);

    @Test
    void acceptedStartCreatesJobBeforeSubmittingTransientWorkItem() {
        StartCommand command = command();
        ModelCallJob createdJob = job(command, ModelCallJob.ExecutionStatus.CREATED, 0L);
        when(modelCallJobRepository.create(any(NewModelCallJob.class))).thenReturn(createdJob);
        when(submission.submit(any(TextGenerationJobWorkItem.class)))
                .thenReturn(SubmissionOutcome.ACCEPTED);

        StartResult result = jobStart.start(command);

        assertThat(result).isEqualTo(new StartResult(createdJob.id(), SubmissionOutcome.ACCEPTED));
        ArgumentCaptor<NewModelCallJob> newJobCaptor = ArgumentCaptor.forClass(NewModelCallJob.class);
        ArgumentCaptor<TextGenerationJobWorkItem> workItemCaptor =
                ArgumentCaptor.forClass(TextGenerationJobWorkItem.class);
        InOrder callOrder = inOrder(modelCallJobRepository, submission);
        callOrder.verify(modelCallJobRepository).create(newJobCaptor.capture());
        callOrder.verify(submission).submit(workItemCaptor.capture());

        NewModelCallJob newJob = newJobCaptor.getValue();
        assertThat(newJob.userId()).isEqualTo(command.userId());
        assertThat(newJob.languageProfileId()).isEqualTo(command.languageProfileId());
        assertThat(newJob.modelPurpose()).isEqualTo(REQUEST.purpose());
        assertThat(newJob.modelOperation()).isEqualTo(ModelOperation.TEXT_GENERATION);
        assertThat(newJob.providerId()).isEmpty();
        assertThat(newJob.modelId()).isEmpty();
        assertThat(newJob.workflowId()).isEqualTo(command.workflowId());
        assertThat(newJob.workflowStepId()).isEqualTo(command.workflowStepId());
        assertThat(newJob.workflowVersion()).isEqualTo(command.workflowVersion());
        assertThat(newJob.expiresAt()).isEqualTo(command.expiresAt());

        TextGenerationJobWorkItem workItem = workItemCaptor.getValue();
        assertThat(workItem.jobId()).isEqualTo(createdJob.id());
        assertThat(workItem.userId()).isEqualTo(createdJob.userId());
        assertThat(workItem.expectedRowVersion()).isEqualTo(createdJob.rowVersion());
        assertThat(workItem.request()).isSameAs(REQUEST);
        assertThat(workItem.credential()).isSameAs(CREDENTIAL);
    }

    @Test
    void capacityRejectionIsPersistedBeforeItIsReturned() {
        StartCommand command = command();
        ModelCallJob createdJob = job(command, ModelCallJob.ExecutionStatus.CREATED, 0L);
        ModelCallJob rejectedJob = job(command, ModelCallJob.ExecutionStatus.SUBMISSION_REJECTED, 1L);
        when(modelCallJobRepository.create(any(NewModelCallJob.class))).thenReturn(createdJob);
        when(submission.submit(any(TextGenerationJobWorkItem.class)))
                .thenReturn(SubmissionOutcome.CAPACITY_UNAVAILABLE);
        when(modelCallJobRepository.tryRecordSubmissionRejection(
                createdJob.id(), createdJob.userId(), createdJob.rowVersion()))
                .thenReturn(Optional.of(rejectedJob));

        StartResult result = jobStart.start(command);

        assertThat(result).isEqualTo(
                new StartResult(createdJob.id(), SubmissionOutcome.CAPACITY_UNAVAILABLE));
        InOrder callOrder = inOrder(modelCallJobRepository, submission);
        callOrder.verify(modelCallJobRepository).create(any(NewModelCallJob.class));
        callOrder.verify(submission).submit(any(TextGenerationJobWorkItem.class));
        callOrder.verify(modelCallJobRepository).tryRecordSubmissionRejection(
                createdJob.id(), createdJob.userId(), createdJob.rowVersion());
    }

    @Test
    void lostCapacityRejectionWriteIsNotReportedAsACompletedStart() {
        StartCommand command = command();
        ModelCallJob createdJob = job(command, ModelCallJob.ExecutionStatus.CREATED, 0L);
        when(modelCallJobRepository.create(any(NewModelCallJob.class))).thenReturn(createdJob);
        when(submission.submit(any(TextGenerationJobWorkItem.class)))
                .thenReturn(SubmissionOutcome.CAPACITY_UNAVAILABLE);
        when(modelCallJobRepository.tryRecordSubmissionRejection(
                createdJob.id(), createdJob.userId(), createdJob.rowVersion()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobStart.start(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("model call job submission rejection was not recorded");
    }

    @Test
    void createFailureDoesNotSubmitWork() {
        RuntimeException createFailure = new RuntimeException("database unavailable");
        when(modelCallJobRepository.create(any(NewModelCallJob.class))).thenThrow(createFailure);

        assertThatThrownBy(() -> jobStart.start(command())).isSameAs(createFailure);

        verifyNoInteractions(submission);
    }

    @Test
    void unexpectedSubmissionFailureIsPropagatedWithoutCapacityCompensation() {
        StartCommand command = command();
        ModelCallJob createdJob = job(command, ModelCallJob.ExecutionStatus.CREATED, 0L);
        RuntimeException submissionFailure = new RuntimeException("executor lifecycle failure");
        when(modelCallJobRepository.create(any(NewModelCallJob.class))).thenReturn(createdJob);
        when(submission.submit(any(TextGenerationJobWorkItem.class))).thenThrow(submissionFailure);

        assertThatThrownBy(() -> jobStart.start(command)).isSameAs(submissionFailure);

        verify(modelCallJobRepository, never()).tryRecordSubmissionRejection(
                any(UUID.class), any(UUID.class), anyLong());
    }

    private static StartCommand command() {
        return new StartCommand(
                UUID.randomUUID(),
                Optional.of(UUID.randomUUID()),
                UUID.randomUUID(),
                "GENERATE_TASK",
                3L,
                OffsetDateTime.now().plusHours(1),
                REQUEST,
                CREDENTIAL);
    }

    private static ModelCallJob job(
            StartCommand command,
            ModelCallJob.ExecutionStatus executionStatus,
            long rowVersion) {
        OffsetDateTime createdAt = OffsetDateTime.now();
        Optional<OffsetDateTime> completedAt = executionStatus == ModelCallJob.ExecutionStatus.CREATED
                ? Optional.empty()
                : Optional.of(createdAt.plusSeconds(1));
        return new ModelCallJob(
                UUID.randomUUID(),
                command.userId(),
                command.languageProfileId(),
                command.request().purpose(),
                ModelOperation.TEXT_GENERATION,
                Optional.empty(),
                Optional.empty(),
                command.workflowId(),
                command.workflowStepId(),
                command.workflowVersion(),
                executionStatus,
                ModelCallJob.ConsumptionStatus.NOT_READY,
                Optional.empty(),
                rowVersion,
                createdAt,
                completedAt,
                command.expiresAt());
    }
}
