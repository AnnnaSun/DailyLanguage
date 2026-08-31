package com.dailylanguage.modelgateway.text.openaicompatible;

import java.util.List;
import java.util.Optional;

import com.dailylanguage.modelgateway.execution.ModelProviderCallException;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.result.ModelUsage;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;
import com.dailylanguage.modelgateway.text.TextMessage;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 只映射当前 portable Text contract 与 OpenAI-compatible Chat Completions 的共同子集。
 */
final class OpenAiCompatibleTextPayloadMapper {

    private final JsonMapper jsonMapper;

    OpenAiCompatibleTextPayloadMapper(JsonMapper jsonMapper) {
        JsonMapper sourceMapper = java.util.Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
        this.jsonMapper = sourceMapper.rebuild()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    String writeRequest(ModelId modelId, TextGenerationRequest request) {
        List<ChatMessage> messages = request.messages().stream()
                .map(message -> new ChatMessage(mapRole(message.role()), message.content()))
                .toList();
        try {
            return jsonMapper.writeValueAsString(new ChatRequest(modelId.value(), false, messages));
        }
        catch (JacksonException exception) {
            // Prompt 可能出现在 serialization exception 中，因此不传播 message 或 cause。
            throw new IllegalStateException("failed to encode OpenAI-compatible text request");
        }
    }

    TextGenerationResponse readResponse(
            ProviderId selectedProviderId,
            ModelId selectedModelId,
            String responseBody) throws ModelProviderCallException {
        ChatResponse response;
        try {
            response = jsonMapper.readValue(responseBody, ChatResponse.class);
        }
        catch (JacksonException exception) {
            throw malformedProviderResponse();
        }
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw malformedProviderResponse();
        }
        ChatChoice choice = response.choices().getFirst();
        if (choice == null || choice.message() == null) {
            throw malformedProviderResponse();
        }

        String text = choice.message().content() == null ? "" : choice.message().content();
        TextGenerationResponse.FinishReason finishReason = mapFinishReason(choice.finish_reason());
        Optional<ModelUsage> usage = readUsage(response.usage());
        return new TextGenerationResponse(
                selectedProviderId,
                selectedModelId,
                text,
                finishReason,
                usage);
    }

    private static String mapRole(TextMessage.Role role) {
        return switch (role) {
            case INSTRUCTION -> "system";
            case USER -> "user";
            case MODEL -> "assistant";
        };
    }

    private static TextGenerationResponse.FinishReason mapFinishReason(String finishReason) {
        if (finishReason == null) {
            return TextGenerationResponse.FinishReason.UNKNOWN;
        }
        return switch (finishReason) {
            case "stop" -> TextGenerationResponse.FinishReason.COMPLETED;
            case "length" -> TextGenerationResponse.FinishReason.LENGTH_LIMIT;
            case "content_filter" -> TextGenerationResponse.FinishReason.CONTENT_FILTERED;
            default -> TextGenerationResponse.FinishReason.UNKNOWN;
        };
    }

    private static Optional<ModelUsage> readUsage(ChatUsage usage) throws ModelProviderCallException {
        if (usage == null) {
            return Optional.empty();
        }
        if (usage.prompt_tokens() == null
                || usage.completion_tokens() == null
                || usage.prompt_tokens() < 0
                || usage.completion_tokens() < 0) {
            throw malformedProviderResponse();
        }
        return Optional.of(new ModelUsage(usage.prompt_tokens(), usage.completion_tokens()));
    }

    private static ModelProviderCallException malformedProviderResponse() {
        return new ModelProviderCallException(ModelFailureKind.PROVIDER_FAILURE);
    }

    private record ChatRequest(String model, boolean stream, List<ChatMessage> messages) {
    }

    private record ChatMessage(String role, String content) {
    }

    private record ChatResponse(List<ChatChoice> choices, ChatUsage usage) {
    }

    private record ChatChoice(ChatResponseMessage message, String finish_reason) {
    }

    private record ChatResponseMessage(String content) {
    }

    private record ChatUsage(Long prompt_tokens, Long completion_tokens) {
    }
}
