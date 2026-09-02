package com.dailylanguage.modelcalljob.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.dailylanguage.modelcalljob.application.TextGenerationJobSubmission.SubmissionOutcome;
import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextMessage;
import com.dailylanguage.modelgateway.text.TextOutputSpecification;

class TextGenerationJobSubmissionTests {

    @Test
    void acceptedSubmissionDelegatesWorkerExecutionToExecutor() {
        AtomicReference<Runnable> submittedTask = new AtomicReference<>();
        Executor executor = submittedTask::set;
        TextGenerationJobWorker worker = mock(TextGenerationJobWorker.class);
        TextGenerationJobSubmission submission = new TextGenerationJobSubmission(executor, worker);
        TextGenerationJobWorkItem workItem = workItem();

        SubmissionOutcome outcome = submission.submit(workItem);

        assertThat(outcome).isEqualTo(SubmissionOutcome.ACCEPTED);
        assertThat(submittedTask.get()).isNotNull();
        verifyNoInteractions(worker);

        submittedTask.get().run();

        verify(worker).execute(workItem);
    }

    @Test
    void capacityRejectionDoesNotInvokeWorker() {
        Executor executor = ignored -> {
            throw new RejectedExecutionException("job executor is full");
        };
        TextGenerationJobWorker worker = mock(TextGenerationJobWorker.class);
        TextGenerationJobSubmission submission = new TextGenerationJobSubmission(executor, worker);

        SubmissionOutcome outcome = submission.submit(workItem());

        assertThat(outcome).isEqualTo(SubmissionOutcome.CAPACITY_UNAVAILABLE);
        verifyNoInteractions(worker);
    }

    @Test
    void nonRejectionFailureIsNotReportedAsCapacityUnavailable() {
        IllegalStateException executorFailure = new IllegalStateException("executor lifecycle failure");
        Executor executor = ignored -> {
            throw executorFailure;
        };
        TextGenerationJobSubmission submission = new TextGenerationJobSubmission(
                executor, mock(TextGenerationJobWorker.class));

        assertThatThrownBy(() -> submission.submit(workItem()))
                .isSameAs(executorFailure);
    }

    @Test
    void nullWorkItemIsRejectedBeforeExecutorSubmission() {
        AtomicReference<Runnable> submittedTask = new AtomicReference<>();
        TextGenerationJobSubmission submission = new TextGenerationJobSubmission(
                submittedTask::set, mock(TextGenerationJobWorker.class));

        assertThatThrownBy(() -> submission.submit(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("workItem must not be null");
        assertThat(submittedTask.get()).isNull();
    }

    private static TextGenerationJobWorkItem workItem() {
        ProviderId providerId = new ProviderId("deepseek");
        TextGenerationRequest request = new TextGenerationRequest(
                ModelPurpose.PLANNING,
                List.of(new TextMessage(TextMessage.Role.USER, "Plan today's practice.")),
                TextOutputSpecification.plainText());
        TransientProviderCredential credential = new TransientProviderCredential(
                providerId, "not-sent-to-provider");
        return new TextGenerationJobWorkItem(
                UUID.randomUUID(), UUID.randomUUID(), 0L, request, credential);
    }
}
