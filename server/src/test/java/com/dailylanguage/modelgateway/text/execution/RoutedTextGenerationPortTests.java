package com.dailylanguage.modelgateway.text.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.dailylanguage.modelgateway.execution.ModelProviderCallException;
import com.dailylanguage.modelgateway.result.ModelFailure;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.result.ModelResult;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ModelRouteKey;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;
import com.dailylanguage.modelgateway.text.TextMessage;
import com.dailylanguage.modelgateway.text.TextOutputSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RoutedTextGenerationPortTests {

    private static final ProviderId PROVIDER_ID = new ProviderId("openai-compatible");
    private static final ModelId MODEL_ID = new ModelId("organization/model:v1");
    private static final Duration EXECUTION_TIMEOUT = Duration.ofSeconds(1);
    private static final TextGenerationRequest REQUEST = new TextGenerationRequest(
            ModelPurpose.CONVERSATION,
            List.of(new TextMessage(TextMessage.Role.USER, "Help me order food.")),
            TextOutputSpecification.plainText());

    private final ExecutorService modelCallExecutor = Executors.newCachedThreadPool();

    @AfterEach
    void stopModelCallExecutor() {
        modelCallExecutor.shutdownNow();
        Thread.interrupted();
    }

    @Test
    void resolvesTheFixedRouteAndDelegatesExactlyOnceWithItsTimeout() {
        var calls = new AtomicInteger();
        var receivedProviderId = new AtomicReference<ProviderId>();
        var receivedModelId = new AtomicReference<ModelId>();
        var receivedRequest = new AtomicReference<TextGenerationRequest>();
        var receivedTimeout = new AtomicReference<Duration>();
        TextGenerationProviderAdapter adapter = (providerId, modelId, request, timeout) -> {
            calls.incrementAndGet();
            receivedProviderId.set(providerId);
            receivedModelId.set(modelId);
            receivedRequest.set(request);
            receivedTimeout.set(timeout);
            return successfulResponse(providerId, modelId);
        };
        var port = routedPort(adapter);

        var result = port.generateText(REQUEST);

        assertThat(result).isEqualTo(successfulResponse(PROVIDER_ID, MODEL_ID));
        assertThat(calls).hasValue(1);
        assertThat(receivedProviderId).hasValue(PROVIDER_ID);
        assertThat(receivedModelId).hasValue(MODEL_ID);
        assertThat(receivedRequest).hasValue(REQUEST);
        assertThat(receivedTimeout).hasValue(EXECUTION_TIMEOUT);
    }

    @Test
    void returnsCapabilityUnavailableBeforeRouteSelection() {
        var port = new RoutedTextGenerationPort(
                new FixedTextGenerationRoutes(Map.of()),
                modelCallExecutor);

        assertThat(port.generateText(REQUEST)).isEqualTo(ModelResult.failure(
                ModelFailure.withoutRoute(ModelFailureKind.CAPABILITY_UNAVAILABLE)));
    }

    @Test
    void propagatesARouteAwareAdapterFailure() {
        TextGenerationProviderAdapter adapter = (providerId, modelId, request, timeout) -> ModelResult.failure(
                ModelFailure.forRoute(
                        ModelFailureKind.TEMPORARY_UNAVAILABLE,
                        providerId,
                        modelId));
        var port = routedPort(adapter);

        assertThat(port.generateText(REQUEST)).isEqualTo(ModelResult.failure(
                ModelFailure.forRoute(
                        ModelFailureKind.TEMPORARY_UNAVAILABLE,
                        PROVIDER_ID,
                        MODEL_ID)));
    }

    @Test
    void translatesTypedProviderFailureWithSelectedRouteAndRetryAfter() {
        var retryAfter = Duration.ofSeconds(12);
        TextGenerationProviderAdapter adapter = (providerId, modelId, request, timeout) -> {
            throw new ModelProviderCallException(ModelFailureKind.RATE_LIMITED, retryAfter);
        };

        assertThat(routedPort(adapter).generateText(REQUEST)).isEqualTo(ModelResult.failure(
                ModelFailure.forRoute(
                        ModelFailureKind.RATE_LIMITED,
                        PROVIDER_ID,
                        MODEL_ID,
                        retryAfter)));
    }

    @Test
    void translatesOnlyExplicitProviderFailureClassification() {
        TextGenerationProviderAdapter adapter = (providerId, modelId, request, timeout) -> {
            throw new ModelProviderCallException(ModelFailureKind.PROVIDER_FAILURE);
        };

        assertThat(routedPort(adapter).generateText(REQUEST)).isEqualTo(ModelResult.failure(
                ModelFailure.forRoute(
                        ModelFailureKind.PROVIDER_FAILURE,
                        PROVIDER_ID,
                        MODEL_ID)));
    }

    @Test
    void returnsRouteAwareTimeoutAndBestEffortInterruptsTheSlowTask() throws InterruptedException {
        var calls = new AtomicInteger();
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        TextGenerationProviderAdapter adapter = (providerId, modelId, request, timeout) -> {
            calls.incrementAndGet();
            try {
                release.await();
                return successfulResponse(providerId, modelId);
            } catch (InterruptedException exception) {
                interrupted.countDown();
                throw new ModelProviderCallException(ModelFailureKind.TEMPORARY_UNAVAILABLE);
            }
        };
        var port = routedPort(adapter, Duration.ofMillis(50), modelCallExecutor);

        var result = port.generateText(REQUEST);

        assertThat(result).isEqualTo(ModelResult.failure(ModelFailure.forRoute(
                ModelFailureKind.TIMEOUT,
                PROVIDER_ID,
                MODEL_ID)));
        assertThat(calls).hasValue(1);
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void rejectsAdapterResultsForADifferentOrMissingRoute() {
        TextGenerationProviderAdapter mismatchedSuccess = (providerId, modelId, request, timeout) ->
                ModelResult.success(new TextGenerationResponse(
                        new ProviderId("different-provider"),
                        modelId,
                        "text",
                        TextGenerationResponse.FinishReason.COMPLETED,
                        Optional.empty()));
        TextGenerationProviderAdapter missingFailureRoute = (providerId, modelId, request, timeout) ->
                ModelResult.failure(ModelFailure.withoutRoute(ModelFailureKind.PROVIDER_FAILURE));
        TextGenerationProviderAdapter mismatchedFailureRoute = (providerId, modelId, request, timeout) ->
                ModelResult.failure(ModelFailure.forRoute(
                        ModelFailureKind.PROVIDER_FAILURE,
                        new ProviderId("different-provider"),
                        modelId));

        assertThatIllegalStateException()
                .isThrownBy(() -> routedPort(mismatchedSuccess).generateText(REQUEST))
                .withMessage("adapter response route does not match selected route");
        assertThatIllegalStateException()
                .isThrownBy(() -> routedPort(missingFailureRoute).generateText(REQUEST))
                .withMessage("routed adapter failure must include providerId and modelId");
        assertThatIllegalStateException()
                .isThrownBy(() -> routedPort(mismatchedFailureRoute).generateText(REQUEST))
                .withMessage("adapter failure route does not match selected route");
    }

    @Test
    void rejectsNullAdapterResultAsAProgrammingError() {
        TextGenerationProviderAdapter adapter = (providerId, modelId, request, timeout) -> null;

        assertThatNullPointerException()
                .isThrownBy(() -> routedPort(adapter).generateText(REQUEST))
                .withMessage("adapter result must not be null");
    }

    @Test
    void hidesAnUnclassifiedRuntimeExceptionMessageAndCause() {
        TextGenerationProviderAdapter adapter = (providerId, modelId, request, timeout) -> {
            throw new IllegalArgumentException("raw provider response must not escape");
        };

        var failure = catchThrowable(() -> routedPort(adapter).generateText(REQUEST));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider adapter failed without a classified operational failure");
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getMessage()).doesNotContain("raw provider response");
    }

    @Test
    void restoresCallerInterruptAndFailsFast() {
        var release = new CountDownLatch(1);
        TextGenerationProviderAdapter adapter = (providerId, modelId, request, timeout) -> {
            try {
                release.await();
                return successfulResponse(providerId, modelId);
            } catch (InterruptedException exception) {
                throw new ModelProviderCallException(ModelFailureKind.TEMPORARY_UNAVAILABLE);
            }
        };
        Thread.currentThread().interrupt();

        var failure = catchThrowable(() -> routedPort(adapter).generateText(REQUEST));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("model call wait was interrupted");
        assertThat(failure.getCause()).isNull();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void failsFastWhenTheExecutorRejectsTheProviderTask() {
        var rejectingExecutor = Executors.newSingleThreadExecutor();
        rejectingExecutor.shutdown();
        TextGenerationProviderAdapter adapter = (providerId, modelId, request, timeout) ->
                successfulResponse(providerId, modelId);
        var port = routedPort(adapter, EXECUTION_TIMEOUT, rejectingExecutor);

        var failure = catchThrowable(() -> port.generateText(REQUEST));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("model call executor rejected the provider task");
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void doesNotConvertAnErrorIntoAnOperationalFailure() {
        var error = new AssertionError("fatal provider error");
        TextGenerationProviderAdapter adapter = (providerId, modelId, request, timeout) -> {
            throw error;
        };

        assertThat(catchThrowable(() -> routedPort(adapter).generateText(REQUEST))).isSameAs(error);
    }

    private RoutedTextGenerationPort routedPort(TextGenerationProviderAdapter adapter) {
        return routedPort(adapter, EXECUTION_TIMEOUT, modelCallExecutor);
    }

    private static RoutedTextGenerationPort routedPort(
            TextGenerationProviderAdapter adapter,
            Duration executionTimeout,
            ExecutorService executor) {
        var routeKey = new ModelRouteKey(ModelPurpose.CONVERSATION, ModelOperation.TEXT_GENERATION);
        var route = new TextGenerationRoute(PROVIDER_ID, MODEL_ID, adapter, executionTimeout);
        return new RoutedTextGenerationPort(
                new FixedTextGenerationRoutes(Map.of(routeKey, route)),
                executor);
    }

    private static ModelResult<TextGenerationResponse> successfulResponse(
            ProviderId providerId,
            ModelId modelId) {
        return ModelResult.success(new TextGenerationResponse(
                providerId,
                modelId,
                "What would you like to order?",
                TextGenerationResponse.FinishReason.COMPLETED,
                Optional.empty()));
    }
}
