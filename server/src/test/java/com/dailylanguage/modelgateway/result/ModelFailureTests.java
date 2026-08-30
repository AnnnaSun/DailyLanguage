package com.dailylanguage.modelgateway.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.util.Optional;

import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ProviderId;
import org.junit.jupiter.api.Test;

class ModelFailureTests {

    private static final ProviderId PROVIDER_ID = new ProviderId("openai-compatible");
    private static final ModelId MODEL_ID = new ModelId("organization/model:v1");

    @Test
    void keepsTheApprovedFailureTaxonomyExplicit() {
        assertThat(ModelFailureKind.values()).containsExactly(
                ModelFailureKind.CAPABILITY_UNAVAILABLE,
                ModelFailureKind.REQUEST_REJECTED,
                ModelFailureKind.AUTHENTICATION_FAILED,
                ModelFailureKind.CREDENTIAL_UNAVAILABLE,
                ModelFailureKind.RATE_LIMITED,
                ModelFailureKind.TIMEOUT,
                ModelFailureKind.TEMPORARY_UNAVAILABLE,
                ModelFailureKind.PROVIDER_FAILURE);
    }

    @Test
    void supportsFailuresBeforeAndAfterRouteSelection() {
        var beforeRoute = ModelFailure.withoutRoute(ModelFailureKind.CAPABILITY_UNAVAILABLE);
        var afterRoute = ModelFailure.forRoute(
                ModelFailureKind.REQUEST_REJECTED,
                PROVIDER_ID,
                MODEL_ID);

        assertThat(beforeRoute.providerId()).isEmpty();
        assertThat(beforeRoute.modelId()).isEmpty();
        assertThat(afterRoute.providerId()).contains(PROVIDER_ID);
        assertThat(afterRoute.modelId()).contains(MODEL_ID);
    }

    @Test
    void requiresCompleteRouteIdentity() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ModelFailure(
                        ModelFailureKind.PROVIDER_FAILURE,
                        Optional.of(PROVIDER_ID),
                        Optional.empty(),
                        Optional.empty()))
                .withMessage("providerId and modelId must both be present or both be absent");
    }

    @Test
    void acceptsPositiveRetryAfterForRateLimitAndTemporaryUnavailability() {
        var rateLimited = ModelFailure.forRoute(
                ModelFailureKind.RATE_LIMITED,
                PROVIDER_ID,
                MODEL_ID,
                Duration.ofSeconds(30));
        var temporarilyUnavailable = ModelFailure.forRoute(
                ModelFailureKind.TEMPORARY_UNAVAILABLE,
                PROVIDER_ID,
                MODEL_ID,
                Duration.ofMinutes(1));

        assertThat(rateLimited.retryAfter()).contains(Duration.ofSeconds(30));
        assertThat(temporarilyUnavailable.retryAfter()).contains(Duration.ofMinutes(1));
    }

    @Test
    void rejectsInvalidRetryAfterMetadata() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ModelFailure.forRoute(
                        ModelFailureKind.RATE_LIMITED,
                        PROVIDER_ID,
                        MODEL_ID,
                        Duration.ZERO))
                .withMessage("retryAfter must be positive");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ModelFailure.forRoute(
                        ModelFailureKind.TIMEOUT,
                        PROVIDER_ID,
                        MODEL_ID,
                        Duration.ofSeconds(1)))
                .withMessage("retryAfter is only valid for rate-limited or temporarily unavailable failures");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ModelFailure(
                        ModelFailureKind.RATE_LIMITED,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(Duration.ofSeconds(30))))
                .withMessage("retryAfter requires providerId and modelId");
    }

    @Test
    void rejectsNullContractComponents() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ModelFailure(
                        null,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()))
                .withMessage("kind must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> new ModelFailure(
                        ModelFailureKind.PROVIDER_FAILURE,
                        null,
                        Optional.empty(),
                        Optional.empty()))
                .withMessage("providerId must not be null");
    }
}
