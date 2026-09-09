package com.dailylanguage.modelcalljob.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
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
    private final TextGenerationJobDispatch dispatch = mock(TextGenerationJobDispatch.class);
    private final TextGenerationJobStart jobStart =
            new TextGenerationJobStart(modelCallJobRepository, dispatch);

    @Test
    void createsJobBeforeDelegatingItsTransientDispatch() {
        StartCommand command = command();
        ModelCallJob createdJob = job(command);
        when(modelCallJobRepository.create(any(NewModelCallJob.class))).thenReturn(createdJob);
        when(dispatch.dispatchCreated(any(DispatchCommand.class)))
                .thenReturn(new DispatchResult(createdJob.id(), SubmissionOutcome.ACCEPTED));

        StartResult result = jobStart.start(command);

        assertThat(result).isEqualTo(new StartResult(createdJob.id(), SubmissionOutcome.ACCEPTED));
        ArgumentCaptor<NewModelCallJob> newJobCaptor = ArgumentCaptor.forClass(NewModelCallJob.class);
        ArgumentCaptor<DispatchCommand> dispatchCaptor = ArgumentCaptor.forClass(DispatchCommand.class);
        InOrder callOrder = inOrder(modelCallJobRepository, dispatch);
        callOrder.verify(modelCallJobRepository).create(newJobCaptor.capture());
        callOrder.verify(dispatch).dispatchCreated(dispatchCaptor.capture());

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

        DispatchCommand dispatchCommand = dispatchCaptor.getValue();
        assertThat(dispatchCommand.job()).isSameAs(createdJob);
        assertThat(dispatchCommand.request()).isSameAs(REQUEST);
        assertThat(dispatchCommand.credential()).isSameAs(CREDENTIAL);
    }

    @Test
    void createFailureDoesNotDispatchWork() {
        RuntimeException createFailure = new RuntimeException("database unavailable");
        when(modelCallJobRepository.create(any(NewModelCallJob.class))).thenThrow(createFailure);

        assertThatThrownBy(() -> jobStart.start(command())).isSameAs(createFailure);

        verifyNoInteractions(dispatch);
    }

    @Test
    void dispatchFailureIsPropagatedAfterJobCreation() {
        StartCommand command = command();
        ModelCallJob createdJob = job(command);
        RuntimeException dispatchFailure = new RuntimeException("executor lifecycle failure");
        when(modelCallJobRepository.create(any(NewModelCallJob.class))).thenReturn(createdJob);
        when(dispatch.dispatchCreated(any(DispatchCommand.class))).thenThrow(dispatchFailure);

        assertThatThrownBy(() -> jobStart.start(command)).isSameAs(dispatchFailure);
    }

    private static StartCommand command() {
        return new StartCommand(
                UUID.randomUUID(), Optional.of(UUID.randomUUID()), UUID.randomUUID(),
                "GENERATE_TASK", 3L, OffsetDateTime.now().plusHours(1), REQUEST, CREDENTIAL);
    }

    private static ModelCallJob job(StartCommand command) {
        OffsetDateTime createdAt = OffsetDateTime.now();
        return new ModelCallJob(
                UUID.randomUUID(), command.userId(), command.languageProfileId(),
                command.request().purpose(), ModelOperation.TEXT_GENERATION,
                Optional.empty(), Optional.empty(), command.workflowId(), command.workflowStepId(),
                command.workflowVersion(), ModelCallJob.ExecutionStatus.CREATED,
                ModelCallJob.ConsumptionStatus.NOT_READY, Optional.empty(), 0L,
                createdAt, Optional.empty(), command.expiresAt());
    }
}
