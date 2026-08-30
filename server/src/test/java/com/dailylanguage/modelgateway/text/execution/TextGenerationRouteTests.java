package com.dailylanguage.modelgateway.text.execution;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;

import com.dailylanguage.modelgateway.result.ModelFailure;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.result.ModelResult;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ProviderId;
import org.junit.jupiter.api.Test;

class TextGenerationRouteTests {

    private static final ProviderId PROVIDER_ID = new ProviderId("openai-compatible");
    private static final ModelId MODEL_ID = new ModelId("organization/model:v1");
    private static final TextGenerationProviderAdapter ADAPTER = (providerId, modelId, request, credential, timeout) ->
            ModelResult.failure(ModelFailure.forRoute(
                    ModelFailureKind.TEMPORARY_UNAVAILABLE,
                    providerId,
                    modelId));

    @Test
    void requiresANonNullPositiveExecutionTimeout() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TextGenerationRoute(PROVIDER_ID, MODEL_ID, ADAPTER, null))
                .withMessage("executionTimeout must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TextGenerationRoute(
                        PROVIDER_ID,
                        MODEL_ID,
                        ADAPTER,
                        Duration.ZERO))
                .withMessage("executionTimeout must be positive");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TextGenerationRoute(
                        PROVIDER_ID,
                        MODEL_ID,
                        ADAPTER,
                        Duration.ofNanos(-1)))
                .withMessage("executionTimeout must be positive");
    }
}
