package com.dailylanguage.modelgateway.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import com.dailylanguage.modelgateway.result.ModelFailure;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.result.ModelResult;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import org.junit.jupiter.api.Test;

class TextGenerationPortTests {

    @Test
    void keepsSuccessAndOperationalFailureExplicitAtTheTypedPort() {
        var request = new TextGenerationRequest(
                ModelPurpose.PLANNING,
                List.of(new TextMessage(TextMessage.Role.USER, "Plan today's practice.")),
                TextOutputSpecification.plainText());
        var response = new TextGenerationResponse(
                new ProviderId("openai-compatible"),
                new ModelId("organization/model:v1"),
                "Practice ordering food.",
                TextGenerationResponse.FinishReason.COMPLETED,
                Optional.empty());
        TextGenerationPort successfulPort = ignored -> ModelResult.success(response);
        TextGenerationPort unavailablePort = ignored -> ModelResult.failure(
                ModelFailure.withoutRoute(ModelFailureKind.CAPABILITY_UNAVAILABLE));

        assertThat(successfulPort.generateText(request))
                .isEqualTo(ModelResult.success(response));
        assertThat(unavailablePort.generateText(request))
                .isEqualTo(ModelResult.failure(
                        ModelFailure.withoutRoute(ModelFailureKind.CAPABILITY_UNAVAILABLE)));
    }
}
