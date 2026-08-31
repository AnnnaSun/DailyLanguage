package com.dailylanguage.modelgateway.text.openaicompatible;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.net.URI;

import com.dailylanguage.modelgateway.routing.ProviderId;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleProviderConfigTests {

    private static final ProviderId DEEPSEEK_PROVIDER_ID = new ProviderId("deepseek");

    @Test
    void acceptsADeepSeekChatCompletionsEndpoint() {
        URI endpoint = URI.create("https://api.deepseek.com/chat/completions");

        OpenAiCompatibleProviderConfig config =
                new OpenAiCompatibleProviderConfig(DEEPSEEK_PROVIDER_ID, endpoint);

        assertThat(config.providerId()).isEqualTo(DEEPSEEK_PROVIDER_ID);
        assertThat(config.chatCompletionsEndpoint()).isEqualTo(endpoint);
    }

    @Test
    void rejectsMissingOrUntrustedEndpointParts() {
        assertThatNullPointerException()
                .isThrownBy(() -> new OpenAiCompatibleProviderConfig(null, validEndpoint()));
        assertThatNullPointerException()
                .isThrownBy(() -> new OpenAiCompatibleProviderConfig(DEEPSEEK_PROVIDER_ID, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> config("http://api.deepseek.com/chat/completions"))
                .withMessage("chatCompletionsEndpoint must be an absolute HTTPS URI");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> config("https://user@api.deepseek.com/chat/completions"))
                .withMessage("chatCompletionsEndpoint must not contain user info, query, or fragment");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> config("https://api.deepseek.com/chat/completions?debug=true"))
                .withMessage("chatCompletionsEndpoint must not contain user info, query, or fragment");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> config("https://api.deepseek.com/v1/models"))
                .withMessage("chatCompletionsEndpoint path must end with /chat/completions");
    }

    private static OpenAiCompatibleProviderConfig config(String endpoint) {
        return new OpenAiCompatibleProviderConfig(DEEPSEEK_PROVIDER_ID, URI.create(endpoint));
    }

    private static URI validEndpoint() {
        return URI.create("https://api.deepseek.com/chat/completions");
    }
}
