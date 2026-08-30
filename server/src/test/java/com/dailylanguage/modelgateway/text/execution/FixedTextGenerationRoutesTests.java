package com.dailylanguage.modelgateway.text.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.dailylanguage.modelgateway.result.ModelFailure;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.result.ModelResult;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ModelRouteKey;
import com.dailylanguage.modelgateway.routing.ProviderId;
import org.junit.jupiter.api.Test;

class FixedTextGenerationRoutesTests {

    private static final ProviderId PROVIDER_ID = new ProviderId("openai-compatible");
    private static final ModelId MODEL_ID = new ModelId("organization/model:v1");
    private static final Duration EXECUTION_TIMEOUT = Duration.ofSeconds(30);
    private static final TextGenerationProviderAdapter ADAPTER = (providerId, modelId, request, timeout) ->
            ModelResult.failure(ModelFailure.forRoute(
                    ModelFailureKind.TEMPORARY_UNAVAILABLE,
                    providerId,
                    modelId));

    @Test
    void resolvesAnImmutableFixedRouteByPurposeAndTextOperation() {
        var routeKey = new ModelRouteKey(ModelPurpose.CONVERSATION, ModelOperation.TEXT_GENERATION);
        var route = new TextGenerationRoute(PROVIDER_ID, MODEL_ID, ADAPTER, EXECUTION_TIMEOUT);
        var configuredRoutes = new HashMap<>(Map.of(routeKey, route));
        var routes = new FixedTextGenerationRoutes(configuredRoutes);
        configuredRoutes.clear();

        assertThat(routes.findRoute(ModelPurpose.CONVERSATION)).contains(route);
        assertThat(routes.findRoute(ModelPurpose.PLANNING)).isEmpty();
    }

    @Test
    void rejectsNonTextOperationAtConfigurationTime() {
        var speechRouteKey = new ModelRouteKey(
                ModelPurpose.CONVERSATION,
                ModelOperation.SPEECH_SYNTHESIS);
        var route = new TextGenerationRoute(PROVIDER_ID, MODEL_ID, ADAPTER, EXECUTION_TIMEOUT);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FixedTextGenerationRoutes(Map.of(speechRouteKey, route)))
                .withMessage("fixed text routes only accept TEXT_GENERATION operation");
    }
}
