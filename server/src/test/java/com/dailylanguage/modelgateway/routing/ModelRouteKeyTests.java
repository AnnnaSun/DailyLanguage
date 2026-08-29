package com.dailylanguage.modelgateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class ModelRouteKeyTests {

    @Test
    void keepsPurposeAndOperationAsIndependentRouteDimensions() {
        var textConversation = new ModelRouteKey(
                ModelPurpose.CONVERSATION,
                ModelOperation.TEXT_GENERATION);
        var speechConversation = new ModelRouteKey(
                ModelPurpose.CONVERSATION,
                ModelOperation.SPEECH_TRANSCRIPTION);
        var textPlanning = new ModelRouteKey(
                ModelPurpose.PLANNING,
                ModelOperation.TEXT_GENERATION);

        assertThat(textConversation)
                .isNotEqualTo(speechConversation)
                .isNotEqualTo(textPlanning);
    }

    @Test
    void requiresBothRouteDimensions() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ModelRouteKey(null, ModelOperation.TEXT_GENERATION))
                .withMessage("purpose must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> new ModelRouteKey(ModelPurpose.PLANNING, null))
                .withMessage("operation must not be null");
    }
}
