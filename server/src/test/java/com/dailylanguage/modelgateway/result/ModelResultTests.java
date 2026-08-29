package com.dailylanguage.modelgateway.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class ModelResultTests {

    @Test
    void createsTypedSuccess() {
        ModelResult<String> result = ModelResult.success("generated text");

        assertThat(result).isEqualTo(new ModelResult.Success<>("generated text"));
    }

    @Test
    void createsTypedFailure() {
        var failure = ModelFailure.withoutRoute(ModelFailureKind.CAPABILITY_UNAVAILABLE);
        ModelResult<String> result = ModelResult.failure(failure);

        assertThat(result).isEqualTo(new ModelResult.Failure<>(failure));
    }

    @Test
    void rejectsNullSuccessOrFailurePayload() {
        assertThatNullPointerException()
                .isThrownBy(() -> ModelResult.success(null))
                .withMessage("success value must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> ModelResult.failure(null))
                .withMessage("failure must not be null");
    }
}
