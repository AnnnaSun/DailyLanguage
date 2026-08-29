package com.dailylanguage.modelgateway.text.execution;

import java.util.Objects;

import com.dailylanguage.modelgateway.result.ModelFailure;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.result.ModelResult;
import com.dailylanguage.modelgateway.text.TextGenerationPort;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;

/**
 * 使用 fixed route 执行单次 Text Generation；不负责 timeout、retry、fallback 或 exception translation。
 */
public final class RoutedTextGenerationPort implements TextGenerationPort {

    private final FixedTextGenerationRoutes routes;

    public RoutedTextGenerationPort(FixedTextGenerationRoutes routes) {
        this.routes = Objects.requireNonNull(routes, "routes must not be null");
    }

    @Override
    public ModelResult<TextGenerationResponse> generateText(TextGenerationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        var route = routes.findRoute(request.purpose());
        if (route.isEmpty()) {
            return ModelResult.failure(ModelFailure.withoutRoute(ModelFailureKind.CAPABILITY_UNAVAILABLE));
        }

        var selectedRoute = route.orElseThrow();
        var result = Objects.requireNonNull(
                selectedRoute.adapter().generateText(
                        selectedRoute.providerId(),
                        selectedRoute.modelId(),
                        request),
                "adapter result must not be null");
        validateResultRoute(selectedRoute, result);
        return result;
    }

    private static void validateResultRoute(
            TextGenerationRoute selectedRoute,
            ModelResult<TextGenerationResponse> result) {
        switch (result) {
            case ModelResult.Success(var response) -> validateResponseRoute(selectedRoute, response);
            case ModelResult.Failure(var failure) -> validateFailureRoute(selectedRoute, failure);
        }
    }

    private static void validateResponseRoute(
            TextGenerationRoute selectedRoute,
            TextGenerationResponse response) {
        if (!selectedRoute.providerId().equals(response.providerId())
                || !selectedRoute.modelId().equals(response.modelId())) {
            throw new IllegalStateException("adapter response route does not match selected route");
        }
    }

    private static void validateFailureRoute(
            TextGenerationRoute selectedRoute,
            ModelFailure failure) {
        if (failure.providerId().isEmpty()) {
            throw new IllegalStateException("routed adapter failure must include providerId and modelId");
        }
        if (!failure.providerId().orElseThrow().equals(selectedRoute.providerId())
                || !failure.modelId().orElseThrow().equals(selectedRoute.modelId())) {
            throw new IllegalStateException("adapter failure route does not match selected route");
        }
    }
}
