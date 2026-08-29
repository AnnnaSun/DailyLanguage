package com.dailylanguage.modelgateway.text;

import java.util.List;
import java.util.Objects;

import com.dailylanguage.modelgateway.routing.ModelPurpose;

/**
 * 单次 Text Generation 的 Provider-neutral 输入；Provider、Model 与 Credential 不属于业务 request。
 */
public record TextGenerationRequest(
        ModelPurpose purpose,
        List<TextMessage> messages,
        TextOutputSpecification outputSpecification) {

    public TextGenerationRequest {
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(outputSpecification, "outputSpecification must not be null");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        if (messages.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("messages must not contain null");
        }
        messages = List.copyOf(messages);
    }
}
