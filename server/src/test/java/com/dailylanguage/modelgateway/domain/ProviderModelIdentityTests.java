package com.dailylanguage.modelgateway.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class ProviderModelIdentityTests {

    @Test
    void preservesProviderDefinedIdentifiers() {
        assertThat(new ProviderId("openai-compatible").value()).isEqualTo("openai-compatible");
        assertThat(new ModelId("organization/model:v1").value()).isEqualTo("organization/model:v1");
    }

    @Test
    void rejectsNullOrBlankIdentifiers() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ProviderId(null))
                .withMessage("providerId must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProviderId(" \t"))
                .withMessage("providerId must not be blank");
        assertThatNullPointerException()
                .isThrownBy(() -> new ModelId(null))
                .withMessage("modelId must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ModelId(" \t"))
                .withMessage("modelId must not be blank");
    }

    @Test
    void rejectsSurroundingWhitespaceInsteadOfSilentlyNormalizingIt() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProviderId(" openai-compatible"))
                .withMessage("providerId must not contain surrounding whitespace");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ModelId("organization/model:v1 "))
                .withMessage("modelId must not contain surrounding whitespace");
    }
}
