package com.dailylanguage.modelgateway.text.openaicompatible;

import java.net.URI;
import java.util.Objects;

import com.dailylanguage.modelgateway.routing.ProviderId;

/**
 * 一个 OpenAI-compatible Provider 的可信 Text endpoint 配置，不接受调用方动态提供 URL。
 */
public record OpenAiCompatibleProviderConfig(
        ProviderId providerId,
        URI chatCompletionsEndpoint) {

    public OpenAiCompatibleProviderConfig {
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(chatCompletionsEndpoint, "chatCompletionsEndpoint must not be null");
        if (!chatCompletionsEndpoint.isAbsolute()
                || chatCompletionsEndpoint.isOpaque()
                || !"https".equalsIgnoreCase(chatCompletionsEndpoint.getScheme())
                || chatCompletionsEndpoint.getHost() == null) {
            throw new IllegalArgumentException("chatCompletionsEndpoint must be an absolute HTTPS URI");
        }
        if (chatCompletionsEndpoint.getUserInfo() != null
                || chatCompletionsEndpoint.getQuery() != null
                || chatCompletionsEndpoint.getFragment() != null) {
            throw new IllegalArgumentException(
                    "chatCompletionsEndpoint must not contain user info, query, or fragment");
        }
        if (!chatCompletionsEndpoint.getPath().endsWith("/chat/completions")) {
            throw new IllegalArgumentException(
                    "chatCompletionsEndpoint path must end with /chat/completions");
        }
    }
}
