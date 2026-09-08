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

import com.dailylanguage.modelcalljob.application.TextGenerationJobDispatch.DispatchCommand;
import com.dailylanguage.modelcalljob.application.TextGenerationJobDispatch.DispatchResult;
import com.dailylanguage.modelcalljob.application.TextGenerationJobSubmission.SubmissionOutcome;
import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.infrastructure.ModelCallJobRepository;
import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextMessage;
import com.dailylanguage.modelgateway.text.TextOutputSpecification;

class TextGenerationJobDispatchTests {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PROFILE_ID = UUID.randomUUID();
    private static final UUID JOB_ID = UUID.randomUUID();
    private static final TextGenerationRequest REQUEST = new TextGenerationRequest(
            ModelPurpose.EVALUATION,
            List.of(new TextMessage(TextMessage.Role.USER, "evaluate")),
            TextOutputSpecification.jsonObject());
    private static final TransientProviderCredential CREDENTIAL =
            new TransientProviderCredential(new ProviderId("deepseek"), "not-persisted");

    private final ModelCallJobRepository repository = mock(ModelCallJobRepository.class);
    private final TextGenerationJobSubmission submission = mock(TextGenerationJobSubmission.class);
    private final TextGenerationJobDispatch dispatch =
            new TextGenerationJobDispatch(repository, submission);

    @Test
    void acceptedDispatchSubmitsMemoryOnlyWorkItemForTheExistingJob() {
        ModelCallJob job = job(ModelPurpose.EVALUATION, ModelOperation.TEXT_GENERATION,
                ModelCallJob.ExecutionStatus.CREATED, ModelCallJob.ConsumptionStatus.NOT_READY, 3L);
        when(submission.submit(any(TextGenerationJobWorkItem.class)))
                .thenReturn(SubmissionOutcome.ACCEPTED);

        DispatchResult result = dispatch.dispatchCreated(new DispatchCommand(job, REQUEST, CREDENTIAL));

        assertThat(result).isEqualTo(new DispatchResult(JOB_ID, SubmissionOutcome.ACCEPTED));
        ArgumentCaptor<TextGenerationJobWorkItem> captor =
                ArgumentCaptor.forClass(TextGenerationJobWorkItem.class);
        verify(submission).submit(captor.capture());
        TextGenerationJobWorkItem workItem = captor.getValue();
        assertThat(workItem.jobId()).isEqualTo(JOB_ID);
        assertThat(workItem.userId()).isEqualTo(USER_ID);
        assertThat(workItem.expectedRowVersion()).isEqualTo(3L);
        assertThat(workItem.request()).isSameAs(REQUEST);
        assertThat(workItem.credential()).isSameAs(CREDENTIAL);
        verifyNoInteractions(repository);
    }

    @Test
    void capacityRejectionIsPersistedBeforeItIsReturned() {
        ModelCallJob created = createdJob();
        ModelCallJob rejected = job(ModelPurpose.EVALUATION, ModelOperation.TEXT_GENERATION,
                ModelCallJob.ExecutionStatus.SUBMISSION_REJECTED,
                ModelCallJob.ConsumptionStatus.NOT_READY, 1L);
        when(submission.submit(any(TextGenerationJobWorkItem.class)))
                .thenReturn(SubmissionOutcome.CAPACITY_UNAVAILABLE);
        when(repository.tryRecordSubmissionRejection(JOB_ID, USER_ID, 0L))
                .thenReturn(Optional.of(rejected));

        DispatchResult result = dispatch.dispatchCreated(new DispatchCommand(created, REQUEST, CREDENTIAL));

        assertThat(result).isEqualTo(
                new DispatchResult(JOB_ID, SubmissionOutcome.CAPACITY_UNAVAILABLE));
        InOrder order = inOrder(submission, repository);
        order.verify(submission).submit(any(TextGenerationJobWorkItem.class));
        order.verify(repository).tryRecordSubmissionRejection(JOB_ID, USER_ID, 0L);
    }

    @Test
    void lostCapacityRejectionWriteFailsClosed() {
        when(submission.submit(any(TextGenerationJobWorkItem.class)))
                .thenReturn(SubmissionOutcome.CAPACITY_UNAVAILABLE);
        when(repository.tryRecordSubmissionRejection(JOB_ID, USER_ID, 0L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> dispatch.dispatchCreated(
                new DispatchCommand(createdJob(), REQUEST, CREDENTIAL)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("model call job submission rejection was not recorded");
    }

    @Test
    void unexpectedSubmissionFailureDoesNotGuessWhetherExecutorAcceptedTheWork() {
        RuntimeException failure = new RuntimeException("executor lifecycle failure");
        when(submission.submit(any(TextGenerationJobWorkItem.class))).thenThrow(failure);

        assertThatThrownBy(() -> dispatch.dispatchCreated(
                new DispatchCommand(createdJob(), REQUEST, CREDENTIAL)))
                .isSameAs(failure);

        verify(repository, never()).tryRecordSubmissionRejection(
                any(UUID.class), any(UUID.class), anyLong());
    }

    @Test
    void rejectsNonDispatchableStateBeforeSubmission() {
        ModelCallJob running = job(ModelPurpose.EVALUATION, ModelOperation.TEXT_GENERATION,
                ModelCallJob.ExecutionStatus.RUNNING, ModelCallJob.ConsumptionStatus.NOT_READY, 1L);

        assertThatThrownBy(() -> dispatch.dispatchCreated(
                new DispatchCommand(running, REQUEST, CREDENTIAL)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("only a CREATED / NOT_READY model call job can be dispatched");
        verifyNoInteractions(repository, submission);
    }

    @Test
    void rejectsRequestThatDoesNotMatchJobPurpose() {
        ModelCallJob planningJob = job(ModelPurpose.PLANNING, ModelOperation.TEXT_GENERATION,
                ModelCallJob.ExecutionStatus.CREATED, ModelCallJob.ConsumptionStatus.NOT_READY, 0L);

        assertThatThrownBy(() -> dispatch.dispatchCreated(
                new DispatchCommand(planningJob, REQUEST, CREDENTIAL)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("text generation request does not match model call job identity");
        verifyNoInteractions(repository, submission);
    }

    private static ModelCallJob createdJob() {
        return job(ModelPurpose.EVALUATION, ModelOperation.TEXT_GENERATION,
                ModelCallJob.ExecutionStatus.CREATED, ModelCallJob.ConsumptionStatus.NOT_READY, 0L);
    }

    private static ModelCallJob job(
            ModelPurpose purpose,
            ModelOperation operation,
            ModelCallJob.ExecutionStatus executionStatus,
            ModelCallJob.ConsumptionStatus consumptionStatus,
            long rowVersion) {
        OffsetDateTime createdAt = OffsetDateTime.now();
        Optional<OffsetDateTime> completedAt = executionStatus == ModelCallJob.ExecutionStatus.CREATED
                || executionStatus == ModelCallJob.ExecutionStatus.RUNNING
                        ? Optional.empty()
                        : Optional.of(createdAt.plusSeconds(1));
        return new ModelCallJob(
                JOB_ID, USER_ID, Optional.of(PROFILE_ID), purpose, operation,
                Optional.empty(), Optional.empty(), UUID.randomUUID(), "SEMANTIC_EVALUATION", 0L,
                executionStatus, consumptionStatus, Optional.empty(), rowVersion,
                createdAt, completedAt, createdAt.plusDays(7));
    }
}
