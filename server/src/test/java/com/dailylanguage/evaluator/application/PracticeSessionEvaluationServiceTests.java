package com.dailylanguage.evaluator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.evaluator.application.EvaluationDispatchService.DispatchResult;
import com.dailylanguage.evaluator.application.EvaluationResultConsumptionService.ConsumptionResult;
import com.dailylanguage.evaluator.application.PracticeSessionEvaluationService.EvaluationResult;
import com.dailylanguage.evaluator.domain.EvaluationRun;
import com.dailylanguage.modelcalljob.application.TextGenerationJobSubmission.SubmissionOutcome;
import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.execution.FixedTextGenerationRoutes;
import com.dailylanguage.modelgateway.text.execution.TextGenerationProviderAdapter;
import com.dailylanguage.modelgateway.text.execution.TextGenerationRoute;
import com.dailylanguage.security.domain.UserContext;

class PracticeSessionEvaluationServiceTests {

    private static final UserContext USER = new UserContext(EvaluationDispatchTestFixtures.USER_ID);
    private static final ProviderId PROVIDER_ID = new ProviderId("deepseek");
    private static final String CREDENTIAL = "browser-only-secret";

    private final GroundedEvaluationInputReader inputReader = mock(GroundedEvaluationInputReader.class);
    private final EvaluationDispatchService dispatchService = mock(EvaluationDispatchService.class);
    private final EvaluationResultConsumptionService consumptionService =
            mock(EvaluationResultConsumptionService.class);
    private final FixedTextGenerationRoutes routes = mock(FixedTextGenerationRoutes.class);
    private final PracticeSessionEvaluationService service = new PracticeSessionEvaluationService(
            inputReader, dispatchService, consumptionService, routes);

    @Test
    void startsWithConfiguredTransientCredentialThenReturnsCurrentPendingRun() {
        readyInput();
        configuredRoute();
        when(dispatchService.dispatchForReadyInput(
                any(GroundedEvaluationInputResult.Ready.class), any(UserContext.class),
                any(TransientProviderCredential.class)))
                .thenReturn(new DispatchResult.Created(
                        EvaluationDispatchTestFixtures.pendingRun(),
                        EvaluationDispatchTestFixtures.JOB_ID,
                        SubmissionOutcome.ACCEPTED));
        when(consumptionService.consumeForReadyInput(EvaluationDispatchTestFixtures.ready(), USER))
                .thenReturn(new ConsumptionResult.Pending(EvaluationDispatchTestFixtures.pendingRun()));

        EvaluationResult result = service.start(
                EvaluationDispatchTestFixtures.PROFILE_ID,
                EvaluationDispatchTestFixtures.SESSION_ID,
                USER,
                PROVIDER_ID.value(),
                CREDENTIAL);

        assertThat(result).isEqualTo(new EvaluationResult.Pending(
                EvaluationDispatchTestFixtures.pendingRun()));
        ArgumentCaptor<TransientProviderCredential> credentialCaptor =
                ArgumentCaptor.forClass(TransientProviderCredential.class);
        var order = inOrder(inputReader, routes, dispatchService, consumptionService);
        order.verify(inputReader).readOwned(
                EvaluationDispatchTestFixtures.PROFILE_ID,
                EvaluationDispatchTestFixtures.SESSION_ID,
                USER);
        order.verify(routes).findRoute(ModelPurpose.EVALUATION);
        order.verify(dispatchService).dispatchForReadyInput(
                eq(EvaluationDispatchTestFixtures.ready()), eq(USER), credentialCaptor.capture());
        order.verify(consumptionService).consumeForReadyInput(
                EvaluationDispatchTestFixtures.ready(), USER);
        assertThat(credentialCaptor.getValue().providerId()).isEqualTo(PROVIDER_ID);
        assertThat(credentialCaptor.getValue().secret()).isEqualTo(CREDENTIAL);
        assertThat(credentialCaptor.getValue().toString()).doesNotContain(CREDENTIAL);
    }

    @Test
    void inputFailureIsReturnedBeforeCredentialOrRouteIsRead() {
        when(inputReader.readOwned(
                EvaluationDispatchTestFixtures.PROFILE_ID,
                EvaluationDispatchTestFixtures.SESSION_ID,
                USER)).thenReturn(new GroundedEvaluationInputResult.NotFound());

        assertThat(service.start(
                EvaluationDispatchTestFixtures.PROFILE_ID,
                EvaluationDispatchTestFixtures.SESSION_ID,
                USER,
                "bad provider ",
                CREDENTIAL)).isEqualTo(new EvaluationResult.SessionNotFound());

        verifyNoInteractions(routes, dispatchService, consumptionService);
    }

    @Test
    void validatesProviderCredentialAndConfiguredRouteBeforeDispatch() {
        readyInput();

        assertThat(service.start(
                EvaluationDispatchTestFixtures.PROFILE_ID,
                EvaluationDispatchTestFixtures.SESSION_ID,
                USER,
                " deepseek",
                CREDENTIAL)).isEqualTo(new EvaluationResult.InvalidProviderId());
        assertThat(service.start(
                EvaluationDispatchTestFixtures.PROFILE_ID,
                EvaluationDispatchTestFixtures.SESSION_ID,
                USER,
                PROVIDER_ID.value(),
                " ")).isEqualTo(new EvaluationResult.InvalidProviderCredential());

        when(routes.findRoute(ModelPurpose.EVALUATION)).thenReturn(Optional.empty());
        assertThat(service.start(
                EvaluationDispatchTestFixtures.PROFILE_ID,
                EvaluationDispatchTestFixtures.SESSION_ID,
                USER,
                PROVIDER_ID.value(),
                CREDENTIAL)).isEqualTo(new EvaluationResult.ConfigurationUnavailable());

        when(routes.findRoute(ModelPurpose.EVALUATION)).thenReturn(Optional.of(route("openai")));
        assertThat(service.start(
                EvaluationDispatchTestFixtures.PROFILE_ID,
                EvaluationDispatchTestFixtures.SESSION_ID,
                USER,
                PROVIDER_ID.value(),
                CREDENTIAL)).isEqualTo(new EvaluationResult.ProviderMismatch());
        verifyNoInteractions(dispatchService, consumptionService);
    }

    @Test
    void dispatchConfigurationFailureDoesNotEnterConsumption() {
        readyInput();
        configuredRoute();
        when(dispatchService.dispatchForReadyInput(any(), any(), any()))
                .thenReturn(new DispatchResult.Unavailable(
                        EvaluationTextRequestFactory.UnavailabilityReason.PROMPT_UNAVAILABLE));

        assertThat(service.start(
                EvaluationDispatchTestFixtures.PROFILE_ID,
                EvaluationDispatchTestFixtures.SESSION_ID,
                USER,
                PROVIDER_ID.value(),
                CREDENTIAL)).isEqualTo(new EvaluationResult.ConfigurationUnavailable());

        verifyNoInteractions(consumptionService);
    }

    @Test
    void reconcileUsesNoRouteOrDispatchAndReturnsTerminalOutcome() {
        readyInput();
        EvaluationRun failedRun = failedRun(EvaluationRun.FailureReason.MODEL_CALL_FAILED);
        var outcome = new EvaluationResultConsumptionService.DurableOutcome(
                failedRun, Optional.empty());
        when(consumptionService.consumeForReadyInput(EvaluationDispatchTestFixtures.ready(), USER))
                .thenReturn(new ConsumptionResult.Existing(outcome));

        assertThat(service.reconcile(
                EvaluationDispatchTestFixtures.PROFILE_ID,
                EvaluationDispatchTestFixtures.SESSION_ID,
                USER)).isEqualTo(new EvaluationResult.Terminal(outcome));

        verifyNoInteractions(routes, dispatchService);
    }

    @Test
    void reconcileDistinguishesAnOwnedSessionWithoutEvaluationRun() {
        readyInput();
        when(consumptionService.consumeForReadyInput(EvaluationDispatchTestFixtures.ready(), USER))
                .thenReturn(new ConsumptionResult.NotFound());

        assertThat(service.reconcile(
                EvaluationDispatchTestFixtures.PROFILE_ID,
                EvaluationDispatchTestFixtures.SESSION_ID,
                USER)).isEqualTo(new EvaluationResult.EvaluationNotFound());
    }

    @Test
    void inconsistentConsumptionAfterDispatchFailsClosed() {
        readyInput();
        configuredRoute();
        when(dispatchService.dispatchForReadyInput(any(), any(), any()))
                .thenReturn(new DispatchResult.Existing(
                        EvaluationDispatchTestFixtures.pendingRun(),
                        EvaluationDispatchTestFixtures.createdJob()));
        when(consumptionService.consumeForReadyInput(EvaluationDispatchTestFixtures.ready(), USER))
                .thenReturn(new ConsumptionResult.InconsistentInput());

        assertThatThrownBy(() -> service.start(
                EvaluationDispatchTestFixtures.PROFILE_ID,
                EvaluationDispatchTestFixtures.SESSION_ID,
                USER,
                PROVIDER_ID.value(),
                CREDENTIAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("evaluation input no longer matches its durable run");
    }

    @Test
    void publicWorkflowMethodsForbidAnOuterTransaction() throws NoSuchMethodException {
        Transactional startTransaction = PracticeSessionEvaluationService.class
                .getMethod("start", java.util.UUID.class, java.util.UUID.class,
                        UserContext.class, String.class, String.class)
                .getAnnotation(Transactional.class);
        Transactional reconciliationTransaction = PracticeSessionEvaluationService.class
                .getMethod("reconcile", java.util.UUID.class, java.util.UUID.class, UserContext.class)
                .getAnnotation(Transactional.class);

        assertThat(startTransaction.propagation()).isEqualTo(Propagation.NEVER);
        assertThat(reconciliationTransaction.propagation()).isEqualTo(Propagation.NEVER);
    }

    private void readyInput() {
        when(inputReader.readOwned(
                EvaluationDispatchTestFixtures.PROFILE_ID,
                EvaluationDispatchTestFixtures.SESSION_ID,
                USER)).thenReturn(EvaluationDispatchTestFixtures.ready());
    }

    private void configuredRoute() {
        when(routes.findRoute(ModelPurpose.EVALUATION)).thenReturn(Optional.of(route(PROVIDER_ID.value())));
    }

    private static TextGenerationRoute route(String providerId) {
        return new TextGenerationRoute(
                new ProviderId(providerId),
                new ModelId("model"),
                mock(TextGenerationProviderAdapter.class),
                Duration.ofSeconds(30));
    }

    private static EvaluationRun failedRun(EvaluationRun.FailureReason failureReason) {
        return new EvaluationRun(
                EvaluationDispatchTestFixtures.RUN_ID,
                EvaluationDispatchTestFixtures.SESSION_ID,
                EvaluationDispatchTestFixtures.JOB_ID,
                EvaluationRun.Status.FAILED,
                EvaluationRun.CURRENT_WORKFLOW_VERSION,
                1L,
                EvaluationDispatchTestFixtures.CREATED_AT,
                Optional.of(EvaluationDispatchTestFixtures.COMPLETED_AT),
                Optional.of(failureReason),
                Optional.empty());
    }
}
