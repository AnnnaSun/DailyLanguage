package com.dailylanguage.evaluator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.dailylanguage.evaluator.application.EvaluationDispatchService.DispatchResult;
import com.dailylanguage.evaluator.application.EvaluationRunCreationService.CreationResult;
import com.dailylanguage.evaluator.application.EvaluationTextRequestFactory.BuildResult;
import com.dailylanguage.evaluator.application.EvaluationTextRequestFactory.UnavailabilityReason;
import com.dailylanguage.modelcalljob.application.TextGenerationJobDispatch;
import com.dailylanguage.modelcalljob.application.TextGenerationJobDispatch.DispatchCommand;
import com.dailylanguage.modelcalljob.application.TextGenerationJobSubmission.SubmissionOutcome;
import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextMessage;
import com.dailylanguage.modelgateway.text.TextOutputSpecification;
import com.dailylanguage.security.domain.UserContext;

class EvaluationDispatchServiceTests {

    private static final Duration RESULT_TTL = Duration.ofDays(7);
    private static final TextGenerationRequest REQUEST = new TextGenerationRequest(
            ModelPurpose.EVALUATION,
            List.of(new TextMessage(TextMessage.Role.USER, "evaluation input")),
            TextOutputSpecification.jsonObject());
    private static final UserContext USER = new UserContext(EvaluationDispatchTestFixtures.USER_ID);

    private final EvaluationTextRequestFactory requestFactory = mock(EvaluationTextRequestFactory.class);
    private final EvaluationRunCreationService creationService = mock(EvaluationRunCreationService.class);
    private final TextGenerationJobDispatch jobDispatch = mock(TextGenerationJobDispatch.class);
    private final EvaluationDispatchService service = new EvaluationDispatchService(
            requestFactory, creationService, jobDispatch, RESULT_TTL);

    @Test
    void preparesRequestCreatesDurableRunThenDispatchesItsExistingJob() {
        TransientProviderCredential credential = EvaluationDispatchTestFixtures.credential();
        when(requestFactory.build(
                EvaluationDispatchTestFixtures.ready(), EvaluationRunCreationService.WORKFLOW_VERSION))
                .thenReturn(new BuildResult.ReadyRequest(REQUEST));
        when(creationService.createForReadyInput(
                eq(EvaluationDispatchTestFixtures.ready()), eq(USER), any(OffsetDateTime.class)))
                .thenReturn(new CreationResult.Created(
                        EvaluationDispatchTestFixtures.pendingRun(),
                        EvaluationDispatchTestFixtures.createdJob()));
        when(jobDispatch.dispatchCreated(any(DispatchCommand.class)))
                .thenReturn(new TextGenerationJobDispatch.DispatchResult(
                        EvaluationDispatchTestFixtures.JOB_ID, SubmissionOutcome.ACCEPTED));
        OffsetDateTime before = OffsetDateTime.now().plus(RESULT_TTL);

        DispatchResult result = service.dispatchForReadyInput(
                EvaluationDispatchTestFixtures.ready(), USER, credential);

        OffsetDateTime after = OffsetDateTime.now().plus(RESULT_TTL);
        assertThat(result).isEqualTo(new DispatchResult.Created(
                EvaluationDispatchTestFixtures.pendingRun(),
                EvaluationDispatchTestFixtures.JOB_ID,
                SubmissionOutcome.ACCEPTED));
        ArgumentCaptor<OffsetDateTime> expiryCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<DispatchCommand> dispatchCaptor = ArgumentCaptor.forClass(DispatchCommand.class);
        InOrder order = inOrder(requestFactory, creationService, jobDispatch);
        order.verify(requestFactory).build(
                EvaluationDispatchTestFixtures.ready(), EvaluationRunCreationService.WORKFLOW_VERSION);
        order.verify(creationService).createForReadyInput(
                eq(EvaluationDispatchTestFixtures.ready()), eq(USER), expiryCaptor.capture());
        order.verify(jobDispatch).dispatchCreated(dispatchCaptor.capture());
        assertThat(expiryCaptor.getValue().toInstant())
                .isBetween(before.toInstant(), after.toInstant());
        assertThat(dispatchCaptor.getValue().job())
                .isEqualTo(EvaluationDispatchTestFixtures.createdJob());
        assertThat(dispatchCaptor.getValue().request()).isSameAs(REQUEST);
        assertThat(dispatchCaptor.getValue().credential()).isSameAs(credential);
    }

    @Test
    void existingRunIsReturnedWithoutRedispatch() {
        readyRequest();
        when(creationService.createForReadyInput(
                eq(EvaluationDispatchTestFixtures.ready()), eq(USER), any(OffsetDateTime.class)))
                .thenReturn(new CreationResult.Existing(
                        EvaluationDispatchTestFixtures.pendingRun(),
                        EvaluationDispatchTestFixtures.createdJob()));

        DispatchResult result = service.dispatchForReadyInput(
                EvaluationDispatchTestFixtures.ready(), USER,
                EvaluationDispatchTestFixtures.credential());

        assertThat(result).isEqualTo(new DispatchResult.Existing(
                EvaluationDispatchTestFixtures.pendingRun(),
                EvaluationDispatchTestFixtures.createdJob()));
        verifyNoInteractions(jobDispatch);
    }

    @Test
    void unavailablePromptCreatesNoDurableState() {
        when(requestFactory.build(
                EvaluationDispatchTestFixtures.ready(), EvaluationRunCreationService.WORKFLOW_VERSION))
                .thenReturn(new BuildResult.Unavailable(UnavailabilityReason.PROMPT_UNAVAILABLE));

        DispatchResult result = service.dispatchForReadyInput(
                EvaluationDispatchTestFixtures.ready(), USER,
                EvaluationDispatchTestFixtures.credential());

        assertThat(result).isEqualTo(
                new DispatchResult.Unavailable(UnavailabilityReason.PROMPT_UNAVAILABLE));
        verifyNoInteractions(creationService, jobDispatch);
    }

    @Test
    void creationBusinessFailuresAreMappedWithoutDispatch() {
        readyRequest();
        when(creationService.createForReadyInput(
                eq(EvaluationDispatchTestFixtures.ready()), eq(USER), any(OffsetDateTime.class)))
                .thenReturn(new CreationResult.NotCompleted());

        DispatchResult result = service.dispatchForReadyInput(
                EvaluationDispatchTestFixtures.ready(), USER,
                EvaluationDispatchTestFixtures.credential());

        assertThat(result).isEqualTo(new DispatchResult.NotCompleted());
        verifyNoInteractions(jobDispatch);
    }

    @Test
    void capacityOutcomeIsPreservedAfterGenericDispatchPersistsIt() {
        readyRequest();
        when(creationService.createForReadyInput(
                eq(EvaluationDispatchTestFixtures.ready()), eq(USER), any(OffsetDateTime.class)))
                .thenReturn(new CreationResult.Created(
                        EvaluationDispatchTestFixtures.pendingRun(),
                        EvaluationDispatchTestFixtures.createdJob()));
        when(jobDispatch.dispatchCreated(any(DispatchCommand.class)))
                .thenReturn(new TextGenerationJobDispatch.DispatchResult(
                        EvaluationDispatchTestFixtures.JOB_ID,
                        SubmissionOutcome.CAPACITY_UNAVAILABLE));

        DispatchResult result = service.dispatchForReadyInput(
                EvaluationDispatchTestFixtures.ready(), USER,
                EvaluationDispatchTestFixtures.credential());

        assertThat(result).isEqualTo(new DispatchResult.Created(
                EvaluationDispatchTestFixtures.pendingRun(),
                EvaluationDispatchTestFixtures.JOB_ID,
                SubmissionOutcome.CAPACITY_UNAVAILABLE));
    }

    @Test
    void unexpectedDispatchFailureIsNotConvertedIntoACompletedOutcome() {
        readyRequest();
        when(creationService.createForReadyInput(
                eq(EvaluationDispatchTestFixtures.ready()), eq(USER), any(OffsetDateTime.class)))
                .thenReturn(new CreationResult.Created(
                        EvaluationDispatchTestFixtures.pendingRun(),
                        EvaluationDispatchTestFixtures.createdJob()));
        RuntimeException failure = new RuntimeException("executor state unknown");
        when(jobDispatch.dispatchCreated(any(DispatchCommand.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.dispatchForReadyInput(
                EvaluationDispatchTestFixtures.ready(), USER,
                EvaluationDispatchTestFixtures.credential()))
                .isSameAs(failure);
    }

    @Test
    void rejectsNonPositiveResultTtl() {
        assertThatThrownBy(() -> new EvaluationDispatchService(
                requestFactory, creationService, jobDispatch, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("resultTtl must be positive");
        verify(requestFactory, never()).build(any(), anyLong());
    }

    private void readyRequest() {
        when(requestFactory.build(
                EvaluationDispatchTestFixtures.ready(), EvaluationRunCreationService.WORKFLOW_VERSION))
                .thenReturn(new BuildResult.ReadyRequest(REQUEST));
    }
}
