package com.dailylanguage.modelgateway.trace;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.dailylanguage.modelgateway.result.ModelFailure;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.result.ModelResult;
import com.dailylanguage.modelgateway.result.ModelUsage;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;

/**
 * 单次 Model Gateway 调用的安全 metadata；不携带 request、generated text、Credential 或底层异常。
 */
public record ModelCallTrace(
        UUID traceId,
        ModelPurpose purpose,
        Optional<ProviderId> providerId,
        Optional<ModelId> modelId,
        Duration gatewayLatency,
        Status status,
        Optional<ModelFailureKind> failureKind,
        Optional<TextGenerationResponse.FinishReason> finishReason,
        Optional<ModelUsage> usage) {

    public ModelCallTrace {
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(modelId, "modelId must not be null");
        Objects.requireNonNull(gatewayLatency, "gatewayLatency must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(failureKind, "failureKind must not be null");
        Objects.requireNonNull(finishReason, "finishReason must not be null");
        Objects.requireNonNull(usage, "usage must not be null");

        if (providerId.isPresent() != modelId.isPresent()) {
            throw new IllegalArgumentException("providerId and modelId must both be present or both be absent");
        }
        if (gatewayLatency.isNegative()) {
            throw new IllegalArgumentException("gatewayLatency must not be negative");
        }
        validateStatus(status, providerId, failureKind, finishReason, usage);
    }

    public static ModelCallTrace fromResult(
            UUID traceId,
            ModelPurpose purpose,
            Duration gatewayLatency,
            ModelResult<TextGenerationResponse> result) {
        Objects.requireNonNull(result, "result must not be null");
        return switch (result) {
            case ModelResult.Success(TextGenerationResponse response) -> new ModelCallTrace(
                    traceId,
                    purpose,
                    Optional.of(response.providerId()),
                    Optional.of(response.modelId()),
                    gatewayLatency,
                    Status.SUCCESS,
                    Optional.empty(),
                    Optional.of(response.finishReason()),
                    response.usage());
            case ModelResult.Failure(ModelFailure failure) -> new ModelCallTrace(
                    traceId,
                    purpose,
                    failure.providerId(),
                    failure.modelId(),
                    gatewayLatency,
                    Status.MODEL_FAILURE,
                    Optional.of(failure.kind()),
                    Optional.empty(),
                    Optional.empty());
        };
    }

    public static ModelCallTrace internalFailure(
            UUID traceId,
            ModelPurpose purpose,
            Duration gatewayLatency) {
        return new ModelCallTrace(
                traceId,
                purpose,
                Optional.empty(),
                Optional.empty(),
                gatewayLatency,
                Status.INTERNAL_FAILURE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static void validateStatus(
            Status status,
            Optional<ProviderId> providerId,
            Optional<ModelFailureKind> failureKind,
            Optional<TextGenerationResponse.FinishReason> finishReason,
            Optional<ModelUsage> usage) {
        switch (status) {
            case SUCCESS -> {
                if (providerId.isEmpty() || failureKind.isPresent() || finishReason.isEmpty()) {
                    throw new IllegalArgumentException("success trace has invalid metadata");
                }
            }
            case MODEL_FAILURE -> {
                if (failureKind.isEmpty() || finishReason.isPresent() || usage.isPresent()) {
                    throw new IllegalArgumentException("model failure trace has invalid metadata");
                }
            }
            case INTERNAL_FAILURE -> {
                if (providerId.isPresent()
                        || failureKind.isPresent()
                        || finishReason.isPresent()
                        || usage.isPresent()) {
                    throw new IllegalArgumentException("internal failure trace has invalid metadata");
                }
            }
        }
    }

    public enum Status {
        SUCCESS,
        MODEL_FAILURE,
        INTERNAL_FAILURE
    }
}
