package com.dailylanguage.modelgateway.text.openaicompatible;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.dailylanguage.modelgateway.execution.ModelProviderCallException;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.result.ModelUsage;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;
import com.dailylanguage.modelgateway.text.TextMessage;
import com.dailylanguage.modelgateway.text.TextOutputSpecification;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OpenAiCompatibleTextPayloadMapperTests {

    private static final ProviderId DEEPSEEK_PROVIDER_ID = new ProviderId("deepseek");
    private static final ModelId SELECTED_MODEL_ID = new ModelId("deepseek-model");
    private static final UUID TRACE_ID = UUID.fromString("6e699faf-f09b-46cf-9657-1b302296c71c");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final OpenAiCompatibleTextPayloadMapper payloadMapper =
            new OpenAiCompatibleTextPayloadMapper(jsonMapper);

    @Test
    void writesThePortableMessageSubsetAsANonStreamingChatCompletion() throws Exception {
        TextGenerationRequest request = new TextGenerationRequest(
                ModelPurpose.CONVERSATION,
                List.of(
                        new TextMessage(TextMessage.Role.INSTRUCTION, "Reply naturally."),
                        new TextMessage(TextMessage.Role.USER, "Hello"),
                        new TextMessage(TextMessage.Role.MODEL, "Hi")),
                TextOutputSpecification.plainText());

        JsonNode payload = jsonMapper.readTree(payloadMapper.writeRequest(SELECTED_MODEL_ID, request));

        assertThat(payload.get("model").stringValue()).isEqualTo(SELECTED_MODEL_ID.value());
        assertThat(payload.get("stream").asBoolean()).isFalse();
        assertThat(payload.get("messages")).hasSize(3);
        assertThat(payload.at("/messages/0/role").stringValue()).isEqualTo("system");
        assertThat(payload.at("/messages/1/role").stringValue()).isEqualTo("user");
        assertThat(payload.at("/messages/2/role").stringValue()).isEqualTo("assistant");
        assertThat(payload.at("/messages/0/content").stringValue()).isEqualTo("Reply naturally.");
        assertThat(payload.has("response_format")).isFalse();
    }

    @Test
    void requestsOpenAiCompatibleJsonObjectModeForThePortableSpecification() throws Exception {
        TextGenerationRequest request = new TextGenerationRequest(
                ModelPurpose.CONVERSATION,
                List.of(new TextMessage(TextMessage.Role.USER, "Return a JSON object.")),
                TextOutputSpecification.jsonObject());

        JsonNode payload = jsonMapper.readTree(payloadMapper.writeRequest(SELECTED_MODEL_ID, request));

        assertThat(payload.at("/response_format/type").stringValue()).isEqualTo("json_object");
        assertThat(payload.get("model").stringValue()).isEqualTo(SELECTED_MODEL_ID.value());
        assertThat(payload.get("stream").asBoolean()).isFalse();
        assertThat(payload.get("messages")).hasSize(1);
    }

    @Test
    void readsPortableTextFinishReasonAndUsageUsingSelectedRouteIdentity() throws Exception {
        String responseBody = """
                {
                  "model": "provider-returned-model-version",
                  "choices": [{
                    "message": {"role": "assistant", "content": "How can I help?"},
                    "finish_reason": "stop"
                  }],
                  "usage": {"prompt_tokens": 12, "completion_tokens": 5, "total_tokens": 17}
                }
                """;

        TextGenerationResponse response = payloadMapper.readResponse(
                TRACE_ID,
                DEEPSEEK_PROVIDER_ID,
                SELECTED_MODEL_ID,
                responseBody);

        assertThat(response).isEqualTo(new TextGenerationResponse(
                DEEPSEEK_PROVIDER_ID,
                SELECTED_MODEL_ID,
                "How can I help?",
                TextGenerationResponse.FinishReason.COMPLETED,
                Optional.of(new ModelUsage(12, 5))));
    }

    @Test
    void normalizesKnownAndUnknownFinishReasonsWithoutKeepingTheRawValue() throws Exception {
        assertThat(readFinishReason("length"))
                .isEqualTo(TextGenerationResponse.FinishReason.LENGTH_LIMIT);
        assertThat(readFinishReason("content_filter"))
                .isEqualTo(TextGenerationResponse.FinishReason.CONTENT_FILTERED);
        assertThat(readFinishReason("insufficient_system_resource"))
                .isEqualTo(TextGenerationResponse.FinishReason.UNKNOWN);
    }

    @Test
    void acceptsAValidTextResultWhenTheProviderOmitsUsage() throws Exception {
        String responseBody = """
                {
                  "choices": [{"message": {"content": "text"}, "finish_reason": "stop"}]
                }
                """;

        TextGenerationResponse response = payloadMapper.readResponse(
                TRACE_ID,
                DEEPSEEK_PROVIDER_ID,
                SELECTED_MODEL_ID,
                responseBody);

        assertThat(response.usage()).isEmpty();
    }

    @Test
    void rejectsMalformedUsageAsAMalformedProviderPayload() {
        String unsafeResponseBody = """
                {
                  "choices": [{"message": {"content": "text"}, "finish_reason": "stop"}],
                  "usage": {"prompt_tokens": -1, "completion_tokens": 2}
                }
                """;

        ModelProviderCallException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                ModelProviderCallException.class,
                () -> payloadMapper.readResponse(
                        TRACE_ID,
                        DEEPSEEK_PROVIDER_ID,
                        SELECTED_MODEL_ID,
                        unsafeResponseBody));

        assertThat(failure.kind()).isEqualTo(ModelFailureKind.PROVIDER_FAILURE);
        assertThat(failure.getMessage()).isNull();
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void rejectsMalformedProviderPayloadWithoutExposingIt() {
        String unsafeResponseBody = "{\"provider_detail\":\"private-response-value\"}";

        ModelProviderCallException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                ModelProviderCallException.class,
                () -> payloadMapper.readResponse(
                        TRACE_ID,
                        DEEPSEEK_PROVIDER_ID,
                        SELECTED_MODEL_ID,
                        unsafeResponseBody));

        assertThat(failure.kind()).isEqualTo(ModelFailureKind.PROVIDER_FAILURE);
        assertThat(failure.getMessage()).isNull();
        assertThat(failure.getCause()).isNull();
    }

    private TextGenerationResponse.FinishReason readFinishReason(String rawFinishReason)
            throws ModelProviderCallException {
        String responseBody = """
                {"choices": [{"message": {"content": "text"}, "finish_reason": "%s"}]}
                """.formatted(rawFinishReason);
        return payloadMapper.readResponse(
                TRACE_ID,
                DEEPSEEK_PROVIDER_ID,
                SELECTED_MODEL_ID,
                responseBody).finishReason();
    }
}
