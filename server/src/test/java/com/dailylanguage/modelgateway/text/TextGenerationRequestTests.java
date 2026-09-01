package com.dailylanguage.modelgateway.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import com.dailylanguage.modelgateway.routing.ModelPurpose;
import org.junit.jupiter.api.Test;

class TextGenerationRequestTests {

    @Test
    void preservesOrderedMessagesWithInternalRoles() {
        var messages = new ArrayList<>(List.of(
                new TextMessage(TextMessage.Role.INSTRUCTION, "Act as a language tutor."),
                new TextMessage(TextMessage.Role.USER, "Help me practice ordering food."),
                new TextMessage(TextMessage.Role.MODEL, "What would you like to order?")));

        var request = new TextGenerationRequest(
                ModelPurpose.CONVERSATION,
                messages,
                TextOutputSpecification.plainText());
        messages.add(new TextMessage(TextMessage.Role.USER, "A bowl of noodles, please."));

        assertThat(request.messages()).extracting(TextMessage::role).containsExactly(
                TextMessage.Role.INSTRUCTION,
                TextMessage.Role.USER,
                TextMessage.Role.MODEL);
        assertThat(request.outputSpecification())
                .isEqualTo(TextOutputSpecification.PlainText.INSTANCE);
        assertThatThrownBy(() -> request.messages().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void exposesJsonObjectAsAProviderNeutralOutputSpecification() {
        TextGenerationRequest request = new TextGenerationRequest(
                ModelPurpose.CONVERSATION,
                List.of(new TextMessage(TextMessage.Role.USER, "Return a JSON object.")),
                TextOutputSpecification.jsonObject());

        assertThat(request.outputSpecification())
                .isEqualTo(TextOutputSpecification.JsonObject.INSTANCE);
    }

    @Test
    void rejectsMissingOrInvalidRequestComponents() {
        var message = new TextMessage(TextMessage.Role.USER, "Hello");

        assertThatNullPointerException()
                .isThrownBy(() -> new TextGenerationRequest(
                        null,
                        List.of(message),
                        TextOutputSpecification.plainText()))
                .withMessage("purpose must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> new TextGenerationRequest(
                        ModelPurpose.CONVERSATION,
                        null,
                        TextOutputSpecification.plainText()))
                .withMessage("messages must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TextGenerationRequest(
                        ModelPurpose.CONVERSATION,
                        List.of(),
                        TextOutputSpecification.plainText()))
                .withMessage("messages must not be empty");
        assertThatNullPointerException()
                .isThrownBy(() -> new TextGenerationRequest(
                        ModelPurpose.CONVERSATION,
                        new ArrayList<>(java.util.Arrays.asList(message, null)),
                        TextOutputSpecification.plainText()))
                .withMessage("messages must not contain null");
        assertThatNullPointerException()
                .isThrownBy(() -> new TextGenerationRequest(
                        ModelPurpose.CONVERSATION,
                        List.of(message),
                        null))
                .withMessage("outputSpecification must not be null");
    }

    @Test
    void rejectsInvalidMessagesWithoutNormalizingValidContent() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TextMessage(null, "Hello"))
                .withMessage("role must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> new TextMessage(TextMessage.Role.USER, null))
                .withMessage("content must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TextMessage(TextMessage.Role.USER, " \t"))
                .withMessage("content must not be blank");

        var message = new TextMessage(TextMessage.Role.USER, "  keep prompt spacing  ");
        assertThat(message.content()).isEqualTo("  keep prompt spacing  ");
    }
}
