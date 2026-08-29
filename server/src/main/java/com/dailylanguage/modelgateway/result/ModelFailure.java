package com.dailylanguage.modelgateway.result;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ProviderId;

/**
 * Model operational failure 的安全、Provider-neutral 表示，不携带底层异常或原始响应。
 * Route identity 同时缺失表示失败发生在 Provider / Model 选定之前；{@code retryAfter} 只是
 * Provider 给出的等待提示，不授权 Gateway 自动重试。
 */
public record ModelFailure(
        ModelFailureKind kind,
        Optional<ProviderId> providerId,
        Optional<ModelId> modelId,
        Optional<Duration> retryAfter) {

    public ModelFailure {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(modelId, "modelId must not be null");
        Objects.requireNonNull(retryAfter, "retryAfter must not be null");

        if (providerId.isPresent() != modelId.isPresent()) {
            throw new IllegalArgumentException("providerId and modelId must both be present or both be absent");
        }
        retryAfter.ifPresent(duration -> validateRetryAfter(kind, providerId, duration));
    }

    public static ModelFailure withoutRoute(ModelFailureKind kind) {
        return new ModelFailure(kind, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static ModelFailure forRoute(
            ModelFailureKind kind,
            ProviderId providerId,
            ModelId modelId) {
        return new ModelFailure(
                kind,
                Optional.of(Objects.requireNonNull(providerId, "providerId must not be null")),
                Optional.of(Objects.requireNonNull(modelId, "modelId must not be null")),
                Optional.empty());
    }

    public static ModelFailure forRoute(
            ModelFailureKind kind,
            ProviderId providerId,
            ModelId modelId,
            Duration retryAfter) {
        return new ModelFailure(
                kind,
                Optional.of(Objects.requireNonNull(providerId, "providerId must not be null")),
                Optional.of(Objects.requireNonNull(modelId, "modelId must not be null")),
                Optional.of(Objects.requireNonNull(retryAfter, "retryAfter must not be null")));
    }

    private static void validateRetryAfter(
            ModelFailureKind kind,
            Optional<ProviderId> providerId,
            Duration retryAfter) {
        if (retryAfter.isZero() || retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter must be positive");
        }
        if (kind != ModelFailureKind.RATE_LIMITED
                && kind != ModelFailureKind.TEMPORARY_UNAVAILABLE) {
            throw new IllegalArgumentException(
                    "retryAfter is only valid for rate-limited or temporarily unavailable failures");
        }
        if (providerId.isEmpty()) {
            throw new IllegalArgumentException("retryAfter requires providerId and modelId");
        }
    }
}
