package com.dailylanguage.modelgateway.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Optional;

import com.dailylanguage.modelgateway.result.ModelUsage;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ProviderId;
import org.junit.jupiter.api.Test;

class TextGenerationResponseTests {

    private static final ProviderId PROVIDER_ID = new ProviderId("openai-compatible");
    private static final ModelId MODEL_ID = new ModelId("organization/model:v1");

    @Test
    void exposesOnlyPortableResponseInformation() {
        var usage = new ModelUsage(18, 7);
        var response = new TextGenerationResponse(
                PROVIDER_ID,
                MODEL_ID,
                "Generated text",
                TextGenerationResponse.FinishReason.COMPLETED,
                Optional.of(usage));

        assertThat(response.providerId()).isEqualTo(PROVIDER_ID);
        assertThat(response.modelId()).isEqualTo(MODEL_ID);
        assertThat(response.text()).isEqualTo("Generated text");
        assertThat(response.finishReason()).isEqualTo(TextGenerationResponse.FinishReason.COMPLETED);
        assertThat(response.usage()).contains(usage);
        assertThat(TextGenerationResponse.FinishReason.values()).containsExactly(
                TextGenerationResponse.FinishReason.COMPLETED,
                TextGenerationResponse.FinishReason.LENGTH_LIMIT,
                TextGenerationResponse.FinishReason.CONTENT_FILTERED,
                TextGenerationResponse.FinishReason.UNKNOWN);
    }

    @Test
    void allowsEmptyTextAndMissingUsageWhenProviderReturnsNoVisibleOutput() {
        var response = new TextGenerationResponse(
                PROVIDER_ID,
                MODEL_ID,
                "",
                TextGenerationResponse.FinishReason.CONTENT_FILTERED,
                Optional.empty());

        assertThat(response.text()).isEmpty();
        assertThat(response.usage()).isEmpty();
    }

    @Test
    void rejectsMissingResponseComponents() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TextGenerationResponse(
                        null,
                        MODEL_ID,
                        "text",
                        TextGenerationResponse.FinishReason.COMPLETED,
                        Optional.empty()))
                .withMessage("providerId must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> new TextGenerationResponse(
                        PROVIDER_ID,
                        MODEL_ID,
                        null,
                        TextGenerationResponse.FinishReason.COMPLETED,
                        Optional.empty()))
                .withMessage("text must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> new TextGenerationResponse(
                        PROVIDER_ID,
                        MODEL_ID,
                        "text",
                        null,
                        Optional.empty()))
                .withMessage("finishReason must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> new TextGenerationResponse(
                        PROVIDER_ID,
                        MODEL_ID,
                        "text",
                        TextGenerationResponse.FinishReason.COMPLETED,
                        null))
                .withMessage("usage must not be null");
    }

    @Test
    void rejectsNegativePortableUsage() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ModelUsage(-1, 0))
                .withMessage("inputTokens must not be negative");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ModelUsage(0, -1))
                .withMessage("outputTokens must not be negative");
    }
}
