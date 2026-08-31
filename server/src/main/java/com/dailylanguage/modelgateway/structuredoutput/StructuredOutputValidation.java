package com.dailylanguage.modelgateway.structuredoutput;

import java.util.Objects;

/**
 * Provider 成功输出经过 Java validation 后的互斥结果；Invalid 分支不能提供可消费的 typed value。
 */
public sealed interface StructuredOutputValidation<T> permits
        StructuredOutputValidation.Valid,
        StructuredOutputValidation.Invalid {

    static <T> StructuredOutputValidation<T> valid(T value) {
        return new Valid<>(value);
    }

    static <T> StructuredOutputValidation<T> invalid(StructuredOutputFailure failure) {
        return new Invalid<>(failure);
    }

    record Valid<T>(T value) implements StructuredOutputValidation<T> {

        public Valid {
            Objects.requireNonNull(value, "value must not be null");
        }
    }

    record Invalid<T>(StructuredOutputFailure failure) implements StructuredOutputValidation<T> {

        public Invalid {
            Objects.requireNonNull(failure, "failure must not be null");
        }
    }
}
