package com.dailylanguage.modelgateway.text.execution;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.dailylanguage.modelgateway.execution.ModelProviderCallException;
import com.dailylanguage.modelgateway.result.ModelFailure;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.result.ModelResult;
import com.dailylanguage.modelgateway.text.TextGenerationPort;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;

/**
 * 使用 fixed route 执行单次 Text Generation，并在最终 deadline 内归一化已知 Provider operational failure。
 */
public final class RoutedTextGenerationPort implements TextGenerationPort {

    private final FixedTextGenerationRoutes routes;
    private final ExecutorService modelCallExecutor;

    public RoutedTextGenerationPort(
            FixedTextGenerationRoutes routes,
            ExecutorService modelCallExecutor) {
        this.routes = Objects.requireNonNull(routes, "routes must not be null");
        this.modelCallExecutor = Objects.requireNonNull(
                modelCallExecutor,
                "modelCallExecutor must not be null");
    }

    @Override
    public ModelResult<TextGenerationResponse> generateText(TextGenerationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        var route = routes.findRoute(request.purpose());
        if (route.isEmpty()) {
            return ModelResult.failure(ModelFailure.withoutRoute(ModelFailureKind.CAPABILITY_UNAVAILABLE));
        }

        var selectedRoute = route.orElseThrow();
        var result = executeAdapter(selectedRoute, request);
        validateResultRoute(selectedRoute, result);
        return result;
    }

    private ModelResult<TextGenerationResponse> executeAdapter(
            TextGenerationRoute selectedRoute,
            TextGenerationRequest request) {
        Future<ModelResult<TextGenerationResponse>> future;
        try {
            future = modelCallExecutor.submit(() -> selectedRoute.adapter().generateText(
                    selectedRoute.providerId(),
                    selectedRoute.modelId(),
                    request,
                    selectedRoute.executionTimeout()));
        } catch (RejectedExecutionException exception) {
            throw new IllegalStateException("model call executor rejected the provider task");
        }

        try {
            var timeoutNanos = TimeUnit.NANOSECONDS.convert(selectedRoute.executionTimeout());
            return Objects.requireNonNull(
                    future.get(timeoutNanos, TimeUnit.NANOSECONDS),
                    "adapter result must not be null");
        } catch (TimeoutException exception) {
            // 本地取消只是 best effort，不能据此认定 Provider 未执行或未计费。
            future.cancel(true);
            return timeoutFailure(selectedRoute);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("model call wait was interrupted");
        } catch (CancellationException exception) {
            throw new IllegalStateException("model call task was cancelled unexpectedly");
        } catch (ExecutionException exception) {
            return translateExecutionFailure(selectedRoute, exception.getCause());
        }
    }

    private static ModelResult<TextGenerationResponse> translateExecutionFailure(
            TextGenerationRoute selectedRoute,
            Throwable failure) {
        if (failure instanceof ModelProviderCallException providerFailure) {
            var modelFailure = providerFailure.retryAfter()
                    .map(retryAfter -> ModelFailure.forRoute(
                            providerFailure.kind(),
                            selectedRoute.providerId(),
                            selectedRoute.modelId(),
                            retryAfter))
                    .orElseGet(() -> ModelFailure.forRoute(
                            providerFailure.kind(),
                            selectedRoute.providerId(),
                            selectedRoute.modelId()));
            return ModelResult.failure(modelFailure);
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("provider adapter failed without a classified operational failure");
    }

    private static ModelResult<TextGenerationResponse> timeoutFailure(TextGenerationRoute selectedRoute) {
        return ModelResult.failure(ModelFailure.forRoute(
                ModelFailureKind.TIMEOUT,
                selectedRoute.providerId(),
                selectedRoute.modelId()));
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
