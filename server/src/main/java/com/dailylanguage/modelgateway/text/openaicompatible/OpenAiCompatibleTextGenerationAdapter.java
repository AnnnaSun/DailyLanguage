package com.dailylanguage.modelgateway.text.openaicompatible;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.execution.ModelProviderCallException;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.result.ModelResult;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;
import com.dailylanguage.modelgateway.text.execution.TextGenerationProviderAdapter;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAI-compatible Chat Completions 的最小 non-streaming Text Adapter。
 */
public final class OpenAiCompatibleTextGenerationAdapter implements TextGenerationProviderAdapter {

    private final OpenAiCompatibleProviderConfig providerConfig;
    private final HttpClient httpClient;
    private final OpenAiCompatibleTextPayloadMapper payloadMapper;

    public OpenAiCompatibleTextGenerationAdapter(
            OpenAiCompatibleProviderConfig providerConfig,
            HttpClient httpClient,
            JsonMapper jsonMapper) {
        this.providerConfig = Objects.requireNonNull(providerConfig, "providerConfig must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        if (httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
            // Credential 只能发送到已配置 endpoint，不能跟随 redirect 转发到另一个位置。
            throw new IllegalArgumentException("OpenAI-compatible HttpClient must not follow redirects");
        }
        this.payloadMapper = new OpenAiCompatibleTextPayloadMapper(jsonMapper);
    }

    @Override
    public ModelResult<TextGenerationResponse> generateText(
            ProviderId providerId,
            ModelId modelId,
            TextGenerationRequest request,
            TransientProviderCredential credential,
            Duration executionTimeout) throws ModelProviderCallException {
        validateCall(providerId, modelId, request, credential, executionTimeout);
        String requestBody = payloadMapper.writeRequest(modelId, request);
        HttpRequest providerRequest = buildProviderRequest(credential, executionTimeout, requestBody);
        HttpResponse<String> providerResponse = send(providerRequest);
        int statusCode = providerResponse.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw classifyHttpFailure(providerResponse);
        }

        String responseBody = providerResponse.body();
        if (responseBody == null) {
            throw new ModelProviderCallException(ModelFailureKind.PROVIDER_FAILURE);
        }
        TextGenerationResponse response = payloadMapper.readResponse(providerId, modelId, responseBody);
        return ModelResult.success(response);
    }

    private void validateCall(
            ProviderId providerId,
            ModelId modelId,
            TextGenerationRequest request,
            TransientProviderCredential credential,
            Duration executionTimeout) {
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(modelId, "modelId must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(credential, "credential must not be null");
        Objects.requireNonNull(executionTimeout, "executionTimeout must not be null");
        if (executionTimeout.isZero() || executionTimeout.isNegative()) {
            throw new IllegalArgumentException("executionTimeout must be positive");
        }
        if (!providerConfig.providerId().equals(providerId)) {
            throw new IllegalStateException("adapter provider does not match configured provider");
        }
        if (!providerId.equals(credential.providerId())) {
            throw new IllegalStateException("adapter credential does not match selected provider");
        }
    }

    private HttpRequest buildProviderRequest(
            TransientProviderCredential credential,
            Duration executionTimeout,
            String requestBody) throws ModelProviderCallException {
        try {
            return HttpRequest.newBuilder(providerConfig.chatCompletionsEndpoint())
                    .timeout(executionTimeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + credential.secret())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
        }
        catch (IllegalArgumentException exception) {
            // Header validation exception 可能包含 Credential，统一替换为无 detail 的 typed failure。
            throw new ModelProviderCallException(ModelFailureKind.AUTHENTICATION_FAILED);
        }
    }

    private HttpResponse<String> send(HttpRequest request) throws ModelProviderCallException {
        try {
            return Objects.requireNonNull(
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)),
                    "provider HTTP response must not be null");
        }
        catch (HttpTimeoutException exception) {
            throw new ModelProviderCallException(ModelFailureKind.TIMEOUT);
        }
        catch (IOException exception) {
            throw new ModelProviderCallException(ModelFailureKind.TEMPORARY_UNAVAILABLE);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("provider HTTP call was interrupted");
        }
    }

    private static ModelProviderCallException classifyHttpFailure(HttpResponse<String> response) {
        int statusCode = response.statusCode();
        ModelFailureKind kind;
        if (statusCode == 401 || statusCode == 403) {
            kind = ModelFailureKind.AUTHENTICATION_FAILED;
        }
        else if (statusCode == 408) {
            kind = ModelFailureKind.TIMEOUT;
        }
        else if (statusCode == 429) {
            kind = ModelFailureKind.RATE_LIMITED;
        }
        else if (statusCode >= 500 && statusCode <= 599) {
            kind = ModelFailureKind.TEMPORARY_UNAVAILABLE;
        }
        else if (statusCode >= 400 && statusCode <= 499) {
            kind = ModelFailureKind.REQUEST_REJECTED;
        }
        else {
            kind = ModelFailureKind.PROVIDER_FAILURE;
        }

        Optional<Duration> retryAfter = readRetryAfter(response, kind);
        return retryAfter
                .map(duration -> new ModelProviderCallException(kind, duration))
                .orElseGet(() -> new ModelProviderCallException(kind));
    }

    private static Optional<Duration> readRetryAfter(
            HttpResponse<String> response,
            ModelFailureKind kind) {
        if (kind != ModelFailureKind.RATE_LIMITED
                && kind != ModelFailureKind.TEMPORARY_UNAVAILABLE) {
            return Optional.empty();
        }
        return response.headers().firstValue("Retry-After").flatMap(value -> {
            try {
                long seconds = Long.parseLong(value);
                return seconds > 0 ? Optional.of(Duration.ofSeconds(seconds)) : Optional.empty();
            }
            catch (NumberFormatException | ArithmeticException exception) {
                return Optional.empty();
            }
        });
    }
}
