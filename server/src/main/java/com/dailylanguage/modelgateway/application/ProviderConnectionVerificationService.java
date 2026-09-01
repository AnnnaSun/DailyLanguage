package com.dailylanguage.modelgateway.application;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.result.ModelResult;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationPort;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;
import com.dailylanguage.modelgateway.text.TextMessage;
import com.dailylanguage.modelgateway.text.TextOutputSpecification;
import com.dailylanguage.modelgateway.text.execution.FixedTextGenerationRoutes;
import com.dailylanguage.modelgateway.text.execution.TextGenerationRoute;

/**
 * 验证当前 fixed Provider route 是否能使用一次性 Credential 完成最小 Text Generation 调用。
 */
@Service
public final class ProviderConnectionVerificationService {

    private static final TextGenerationRequest VERIFICATION_REQUEST = new TextGenerationRequest(
            ModelPurpose.CONNECTION_VERIFICATION,
            List.of(
                    new TextMessage(
                            TextMessage.Role.INSTRUCTION,
                            "This request only verifies model provider connectivity."),
                    new TextMessage(TextMessage.Role.USER, "Reply with exactly OK.")),
            TextOutputSpecification.plainText());

    private final FixedTextGenerationRoutes routes;
    private final TextGenerationPort textGenerationPort;

    public ProviderConnectionVerificationService(
            FixedTextGenerationRoutes routes,
            TextGenerationPort textGenerationPort) {
        this.routes = Objects.requireNonNull(routes, "routes must not be null");
        this.textGenerationPort = Objects.requireNonNull(
                textGenerationPort,
                "textGenerationPort must not be null");
    }

    public List<ProviderPreset> configuredPresets() {
        return routes.findRoute(ModelPurpose.CONNECTION_VERIFICATION)
                .map(route -> List.of(toPreset(route)))
                .orElseGet(List::of);
    }

    public ModelResult<ProviderPreset> verifyConnection(
            ProviderId providerId,
            String credentialSecret) {
        TransientProviderCredential credential = new TransientProviderCredential(
                providerId,
                credentialSecret);
        ModelResult<TextGenerationResponse> result = textGenerationPort.generateText(
                VERIFICATION_REQUEST,
                credential);
        return switch (result) {
            case ModelResult.Success(TextGenerationResponse response) -> ModelResult.success(
                    new ProviderPreset(response.providerId(), response.modelId()));
            case ModelResult.Failure(var failure) -> ModelResult.failure(failure);
        };
    }

    private static ProviderPreset toPreset(TextGenerationRoute route) {
        return new ProviderPreset(route.providerId(), route.modelId());
    }

    public record ProviderPreset(ProviderId providerId, ModelId modelId) {

        public ProviderPreset {
            Objects.requireNonNull(providerId, "providerId must not be null");
            Objects.requireNonNull(modelId, "modelId must not be null");
        }
    }
}
