package com.dailylanguage.modelgateway.execution;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import com.dailylanguage.modelgateway.result.ModelFailureKind;

/**
 * Provider Adapter 可安全暴露给 Gateway 的 operational failure，不携带底层响应、异常或敏感内容。
 */
public final class ModelProviderCallException extends Exception {

    private final ModelFailureKind kind;
    private final Optional<Duration> retryAfter;

    public ModelProviderCallException(ModelFailureKind kind) {
        this(kind, Optional.empty());
    }

    public ModelProviderCallException(ModelFailureKind kind, Duration retryAfter) {
        this(kind, Optional.of(Objects.requireNonNull(retryAfter, "retryAfter must not be null")));
    }

    private ModelProviderCallException(
            ModelFailureKind kind,
            Optional<Duration> retryAfter) {
        super(null, null, false, false);
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter must not be null");
        retryAfter.ifPresent(duration -> validateRetryAfter(kind, duration));
    }

    public ModelFailureKind kind() {
        return kind;
    }

    public Optional<Duration> retryAfter() {
        return retryAfter;
    }

    private static void validateRetryAfter(ModelFailureKind kind, Duration retryAfter) {
        if (retryAfter.isZero() || retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter must be positive");
        }
        if (kind != ModelFailureKind.RATE_LIMITED
                && kind != ModelFailureKind.TEMPORARY_UNAVAILABLE) {
            throw new IllegalArgumentException(
                    "retryAfter is only valid for rate-limited or temporarily unavailable failures");
        }
    }
}
