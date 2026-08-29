package com.dailylanguage.modelgateway.text.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import org.junit.jupiter.api.Test;

class RoutedTextGenerationPortTests {

    private static final ProviderId PROVIDER_ID = new ProviderId("openai-compatible");
    private static final ModelId MODEL_ID = new ModelId("organization/model:v1");
    private static final TextGenerationRequest REQUEST = new TextGenerationRequest(
            ModelPurpose.CONVERSATION,
            List.of(new TextMessage(TextMessage.Role.USER, "Help me order food.")),
            TextOutputSpecification.plainText());

    @Test
    void resolvesTheFixedRouteAndDelegatesExactlyOnce() {
        var calls = new AtomicInteger();
        var receivedProviderId = new AtomicReference<ProviderId>();
        var receivedModelId = new AtomicReference<ModelId>();
        var receivedRequest = new AtomicReference<TextGenerationRequest>();
        TextGenerationProviderAdapter adapter = (providerId, modelId, request) -> {
            calls.incrementAndGet();
            receivedProviderId.set(providerId);
            receivedModelId.set(modelId);
            receivedRequest.set(request);
            return ModelResult.success(new TextGenerationResponse(
                    providerId,
                    modelId,
                    "What would you like to order?",
                    TextGenerationResponse.FinishReason.COMPLETED,
                    Optional.empty()));
        };
        var port = routedPort(adapter);

        var result = port.generateText(REQUEST);

        assertThat(result).isEqualTo(ModelResult.success(new TextGenerationResponse(
                PROVIDER_ID,
                MODEL_ID,
                "What would you like to order?",
                TextGenerationResponse.FinishReason.COMPLETED,
                Optional.empty())));
        assertThat(calls).hasValue(1);
        assertThat(receivedProviderId).hasValue(PROVIDER_ID);
        assertThat(receivedModelId).hasValue(MODEL_ID);
        assertThat(receivedRequest).hasValue(REQUEST);
    }

    @Test
    void returnsCapabilityUnavailableBeforeRouteSelection() {
        var port = new RoutedTextGenerationPort(new FixedTextGenerationRoutes(Map.of()));

        assertThat(port.generateText(REQUEST)).isEqualTo(ModelResult.failure(
                ModelFailure.withoutRoute(ModelFailureKind.CAPABILITY_UNAVAILABLE)));
    }

    @Test
    void propagatesARouteAwareAdapterFailure() {
        TextGenerationProviderAdapter adapter = (providerId, modelId, request) -> ModelResult.failure(
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
    void rejectsAdapterResultsForADifferentOrMissingRoute() {
        TextGenerationProviderAdapter mismatchedSuccess = (providerId, modelId, request) -> ModelResult.success(
                new TextGenerationResponse(
                        new ProviderId("different-provider"),
                        modelId,
                        "text",
                        TextGenerationResponse.FinishReason.COMPLETED,
                        Optional.empty()));
        TextGenerationProviderAdapter missingFailureRoute = (providerId, modelId, request) -> ModelResult.failure(
                ModelFailure.withoutRoute(ModelFailureKind.PROVIDER_FAILURE));
        TextGenerationProviderAdapter mismatchedFailureRoute = (providerId, modelId, request) -> ModelResult.failure(
                ModelFailure.forRoute(
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
        TextGenerationProviderAdapter adapter = (providerId, modelId, request) -> null;

        assertThatNullPointerException()
                .isThrownBy(() -> routedPort(adapter).generateText(REQUEST))
                .withMessage("adapter result must not be null");
    }

    private static RoutedTextGenerationPort routedPort(TextGenerationProviderAdapter adapter) {
        var routeKey = new ModelRouteKey(ModelPurpose.CONVERSATION, ModelOperation.TEXT_GENERATION);
        var route = new TextGenerationRoute(PROVIDER_ID, MODEL_ID, adapter);
        return new RoutedTextGenerationPort(new FixedTextGenerationRoutes(Map.of(routeKey, route)));
    }
}
