package com.dailylanguage.modelgateway.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.dailylanguage.modelgateway.application.ProviderConnectionVerificationService.ProviderPreset;
import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.result.ModelFailure;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.result.ModelResult;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ModelRouteKey;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationPort;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;
import com.dailylanguage.modelgateway.text.execution.FixedTextGenerationRoutes;
import com.dailylanguage.modelgateway.text.execution.TextGenerationProviderAdapter;
import com.dailylanguage.modelgateway.text.execution.TextGenerationRoute;

class ProviderConnectionVerificationServiceTests {

    private static final ProviderId PROVIDER_ID = new ProviderId("deepseek");
    private static final ModelId MODEL_ID = new ModelId("deepseek-chat");

    @Test
    void exposesOnlyTheConfiguredVerificationPreset() {
        ProviderConnectionVerificationService service = new ProviderConnectionVerificationService(
                verificationRoutes(),
                mock(TextGenerationPort.class));

        assertThat(service.configuredPresets())
                .containsExactly(new ProviderPreset(PROVIDER_ID, MODEL_ID));
    }

    @Test
    void passesTransientCredentialIntoFixedVerificationRequestAndDiscardsGeneratedText() {
        TextGenerationPort port = mock(TextGenerationPort.class);
        TextGenerationResponse providerResponse = new TextGenerationResponse(
                PROVIDER_ID,
                MODEL_ID,
                "provider output must not cross the verification boundary",
                TextGenerationResponse.FinishReason.COMPLETED,
                Optional.empty());
        when(port.generateText(any(), any())).thenReturn(ModelResult.success(providerResponse));
        ProviderConnectionVerificationService service = new ProviderConnectionVerificationService(
                verificationRoutes(),
                port);

        ModelResult<ProviderPreset> result = service.verifyConnection(PROVIDER_ID, "transient-secret");

        assertThat(result).isEqualTo(ModelResult.success(new ProviderPreset(PROVIDER_ID, MODEL_ID)));
        ArgumentCaptor<TextGenerationRequest> requestCaptor =
                ArgumentCaptor.forClass(TextGenerationRequest.class);
        ArgumentCaptor<TransientProviderCredential> credentialCaptor =
                ArgumentCaptor.forClass(TransientProviderCredential.class);
        verify(port).generateText(requestCaptor.capture(), credentialCaptor.capture());
        assertThat(requestCaptor.getValue().purpose()).isEqualTo(ModelPurpose.CONNECTION_VERIFICATION);
        assertThat(requestCaptor.getValue().messages()).hasSize(2);
        assertThat(credentialCaptor.getValue().providerId()).isEqualTo(PROVIDER_ID);
        assertThat(credentialCaptor.getValue().secret()).isEqualTo("transient-secret");
        assertThat(credentialCaptor.getValue().toString()).doesNotContain("transient-secret");
    }

    @Test
    void preservesSafeTypedProviderFailure() {
        TextGenerationPort port = mock(TextGenerationPort.class);
        ModelFailure failure = ModelFailure.forRoute(
                ModelFailureKind.AUTHENTICATION_FAILED,
                PROVIDER_ID,
                MODEL_ID);
        when(port.generateText(any(), any())).thenReturn(ModelResult.failure(failure));
        ProviderConnectionVerificationService service = new ProviderConnectionVerificationService(
                verificationRoutes(),
                port);

        assertThat(service.verifyConnection(PROVIDER_ID, "rejected-secret"))
                .isEqualTo(ModelResult.failure(failure));
    }

    private static FixedTextGenerationRoutes verificationRoutes() {
        TextGenerationProviderAdapter unusedAdapter =
                (traceId, providerId, modelId, request, credential, executionTimeout) -> {
                    throw new AssertionError("preset lookup must not execute the adapter");
                };
        TextGenerationRoute route = new TextGenerationRoute(
                PROVIDER_ID,
                MODEL_ID,
                unusedAdapter,
                Duration.ofSeconds(30));
        return new FixedTextGenerationRoutes(Map.of(
                new ModelRouteKey(
                        ModelPurpose.CONNECTION_VERIFICATION,
                        ModelOperation.TEXT_GENERATION),
                route));
    }
}
