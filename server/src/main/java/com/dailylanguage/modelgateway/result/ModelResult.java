package com.dailylanguage.modelgateway.result;

import java.util.Objects;

/**
 * 一次 Model 调用的显式结果，调用方必须区分成功和可预期的 operational failure。
 */
public sealed interface ModelResult<T> permits ModelResult.Success, ModelResult.Failure {

    static <T> ModelResult<T> success(T value) {
        return new Success<>(value);
    }

    static <T> ModelResult<T> failure(ModelFailure failure) {
        return new Failure<>(failure);
    }

    record Success<T>(T value) implements ModelResult<T> {

        public Success {
            Objects.requireNonNull(value, "success value must not be null");
        }
    }

    record Failure<T>(ModelFailure failure) implements ModelResult<T> {

        public Failure {
            Objects.requireNonNull(failure, "failure must not be null");
        }
    }
}
