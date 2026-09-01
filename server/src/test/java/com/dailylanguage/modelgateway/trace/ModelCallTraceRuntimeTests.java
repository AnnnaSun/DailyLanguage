package com.dailylanguage.modelgateway.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.result.ModelFailure;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.result.ModelResult;
import com.dailylanguage.modelgateway.result.ModelUsage;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ModelRouteKey;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;
import com.dailylanguage.modelgateway.text.TextMessage;
import com.dailylanguage.modelgateway.text.TextOutputSpecification;
import com.dailylanguage.modelgateway.text.execution.FixedTextGenerationRoutes;
import com.dailylanguage.modelgateway.text.execution.RoutedTextGenerationPort;
import com.dailylanguage.modelgateway.text.execution.TextGenerationProviderAdapter;
import com.dailylanguage.modelgateway.text.execution.TextGenerationRoute;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ModelCallTraceRuntimeTests {

    private static final ProviderId PROVIDER_ID = new ProviderId("deepseek");
    private static final ModelId MODEL_ID = new ModelId("deepseek-v4-flash");
    private static final String CREDENTIAL_SECRET = "must-not-enter-trace";
    private static final TextGenerationRequest REQUEST = new TextGenerationRequest(
            ModelPurpose.CONNECTION_VERIFICATION,
            List.of(new TextMessage(TextMessage.Role.USER, "verification prompt must not enter trace")),
            TextOutputSpecification.plainText());
    private static final TransientProviderCredential CREDENTIAL =
            new TransientProviderCredential(PROVIDER_ID, CREDENTIAL_SECRET);

    private final ExecutorService modelCallExecutor = Executors.newSingleThreadExecutor();

    @AfterEach
    void stopExecutor() {
        modelCallExecutor.shutdownNow();
    }

    @Test
    void recordsSafeSuccessMetadataWithoutChangingTheModelResult() {
        List<ModelCallTrace> traces = new ArrayList<>();
        TextGenerationProviderAdapter adapter = (providerId, modelId, request, credential, timeout) ->
                ModelResult.success(new TextGenerationResponse(
                        providerId,
                        modelId,
                        "generated verification text must not enter trace",
                        TextGenerationResponse.FinishReason.COMPLETED,
                        Optional.of(new ModelUsage(8, 3))));
        RoutedTextGenerationPort port = routedPort(adapter, traces::add);

        ModelResult<TextGenerationResponse> result = port.generateText(REQUEST, CREDENTIAL);

        assertThat(result).isInstanceOf(ModelResult.Success.class);
        assertThat(traces).hasSize(1);
        ModelCallTrace trace = traces.getFirst();
        assertThat(trace.purpose()).isEqualTo(ModelPurpose.CONNECTION_VERIFICATION);
        assertThat(trace.providerId()).contains(PROVIDER_ID);
        assertThat(trace.modelId()).contains(MODEL_ID);
        assertThat(trace.status()).isEqualTo(ModelCallTrace.Status.SUCCESS);
        assertThat(trace.finishReason()).contains(TextGenerationResponse.FinishReason.COMPLETED);
        assertThat(trace.usage()).contains(new ModelUsage(8, 3));
        assertThat(trace.gatewayLatency()).isGreaterThanOrEqualTo(Duration.ZERO);
        assertThat(trace.toString())
                .doesNotContain(CREDENTIAL_SECRET, "verification prompt", "generated verification text");
    }

    @Test
    void recordsPreRouteFailureWithoutInventingProviderOrModelIdentity() {
        List<ModelCallTrace> traces = new ArrayList<>();
        RoutedTextGenerationPort port = new RoutedTextGenerationPort(
                new FixedTextGenerationRoutes(Map.of()),
                modelCallExecutor,
                traces::add);

        ModelResult<TextGenerationResponse> result = port.generateText(REQUEST, CREDENTIAL);

        assertThat(result).isEqualTo(ModelResult.failure(
                ModelFailure.withoutRoute(ModelFailureKind.CAPABILITY_UNAVAILABLE)));
        assertThat(traces).singleElement().satisfies(trace -> {
            assertThat(trace.status()).isEqualTo(ModelCallTrace.Status.MODEL_FAILURE);
            assertThat(trace.failureKind()).contains(ModelFailureKind.CAPABILITY_UNAVAILABLE);
            assertThat(trace.providerId()).isEmpty();
            assertThat(trace.modelId()).isEmpty();
        });
    }

    @Test
    void recordsInternalFailureAndPreservesTheOriginalExceptionContract() {
        List<ModelCallTrace> traces = new ArrayList<>();
        TextGenerationProviderAdapter adapter = (providerId, modelId, request, credential, timeout) -> {
            throw new IllegalArgumentException(credential.secret());
        };
        RoutedTextGenerationPort port = routedPort(adapter, traces::add);

        Throwable failure = catchThrowable(() -> port.generateText(REQUEST, CREDENTIAL));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider adapter failed without a classified operational failure");
        assertThat(traces).singleElement().satisfies(trace -> {
            assertThat(trace.status()).isEqualTo(ModelCallTrace.Status.INTERNAL_FAILURE);
            assertThat(trace.failureKind()).isEmpty();
            assertThat(trace.toString()).doesNotContain(CREDENTIAL_SECRET);
        });
    }

    @Test
    void traceRecorderFailureDoesNotReplaceASuccessfulModelResult() {
        TextGenerationProviderAdapter adapter = (providerId, modelId, request, credential, timeout) ->
                ModelResult.success(new TextGenerationResponse(
                        providerId,
                        modelId,
                        "ok",
                        TextGenerationResponse.FinishReason.COMPLETED,
                        Optional.empty()));
        ModelCallTraceRecorder failingRecorder = trace -> {
            throw new IllegalStateException(CREDENTIAL_SECRET);
        };
        RoutedTextGenerationPort port = routedPort(adapter, failingRecorder);

        ModelResult<TextGenerationResponse> result = port.generateText(REQUEST, CREDENTIAL);

        assertThat(result).isInstanceOf(ModelResult.Success.class);
    }

    private RoutedTextGenerationPort routedPort(
            TextGenerationProviderAdapter adapter,
            ModelCallTraceRecorder traceRecorder) {
        ModelRouteKey routeKey = new ModelRouteKey(
                ModelPurpose.CONNECTION_VERIFICATION,
                ModelOperation.TEXT_GENERATION);
        TextGenerationRoute route = new TextGenerationRoute(
                PROVIDER_ID,
                MODEL_ID,
                adapter,
                Duration.ofSeconds(1));
        return new RoutedTextGenerationPort(
                new FixedTextGenerationRoutes(Map.of(routeKey, route)),
                modelCallExecutor,
                traceRecorder);
    }
}
