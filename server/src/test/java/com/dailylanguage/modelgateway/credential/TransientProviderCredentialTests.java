package com.dailylanguage.modelgateway.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.dailylanguage.modelgateway.routing.ProviderId;
import org.junit.jupiter.api.Test;

class TransientProviderCredentialTests {

    private static final ProviderId PROVIDER_ID = new ProviderId("openai-compatible");

    @Test
    void preservesTheOpaqueSecretWithoutExposingItThroughToString() {
        String secret = "  provider-secret-with-whitespace  ";
        TransientProviderCredential credential = new TransientProviderCredential(PROVIDER_ID, secret);

        assertThat(credential.providerId()).isEqualTo(PROVIDER_ID);
        assertThat(credential.secret()).isEqualTo(secret);
        assertThat(credential.toString())
                .contains(PROVIDER_ID.value(), "REDACTED")
                .doesNotContain(secret, "provider-secret-with-whitespace");
    }

    @Test
    void rejectsMissingOrBlankCredentialPartsWithoutEchoingTheSecret() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TransientProviderCredential(null, "provider-secret"))
                .withMessage("providerId must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> new TransientProviderCredential(PROVIDER_ID, null))
                .withMessage("secret must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TransientProviderCredential(PROVIDER_ID, " \t\n"))
                .withMessage("secret must not be blank")
                .withMessageNotContaining("provider-secret");
    }
}
