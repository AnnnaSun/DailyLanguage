package com.dailylanguage.modelgateway.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;

import com.dailylanguage.modelgateway.result.ModelFailureKind;
import org.junit.jupiter.api.Test;

class ModelProviderCallExceptionTests {

    @Test
    void exposesOnlyTheSafeFailureClassification() {
        var exception = new ModelProviderCallException(ModelFailureKind.AUTHENTICATION_FAILED);

        assertThat(exception.kind()).isEqualTo(ModelFailureKind.AUTHENTICATION_FAILED);
        assertThat(exception.retryAfter()).isEmpty();
        assertThat(exception.getMessage()).isNull();
        assertThat(exception.getCause()).isNull();
        assertThat(exception.getStackTrace()).isEmpty();
    }

    @Test
    void acceptsPositiveRetryAfterOnlyForSupportedFailureKinds() {
        var retryAfter = Duration.ofSeconds(15);
        var rateLimited = new ModelProviderCallException(ModelFailureKind.RATE_LIMITED, retryAfter);
        var temporarilyUnavailable = new ModelProviderCallException(
                ModelFailureKind.TEMPORARY_UNAVAILABLE,
                retryAfter);

        assertThat(rateLimited.retryAfter()).contains(retryAfter);
        assertThat(temporarilyUnavailable.retryAfter()).contains(retryAfter);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ModelProviderCallException(
                        ModelFailureKind.AUTHENTICATION_FAILED,
                        retryAfter))
                .withMessage("retryAfter is only valid for rate-limited or temporarily unavailable failures");
    }

    @Test
    void rejectsMissingOrNonPositiveFailureFields() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ModelProviderCallException(null))
                .withMessage("kind must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> new ModelProviderCallException(ModelFailureKind.RATE_LIMITED, null))
                .withMessage("retryAfter must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ModelProviderCallException(
                        ModelFailureKind.RATE_LIMITED,
                        Duration.ZERO))
                .withMessage("retryAfter must be positive");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ModelProviderCallException(
                        ModelFailureKind.TEMPORARY_UNAVAILABLE,
                        Duration.ofNanos(-1)))
                .withMessage("retryAfter must be positive");
    }
}
