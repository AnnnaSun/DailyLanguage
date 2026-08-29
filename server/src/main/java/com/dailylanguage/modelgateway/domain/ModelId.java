package com.dailylanguage.modelgateway.domain;

import java.util.Objects;

/**
 * Model 的 Provider-defined 标识；拒绝外围空白而不自动修正，避免 route 指向意外名称。
 */
public record ModelId(String value) {

    public ModelId {
        Objects.requireNonNull(value, "modelId must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException("modelId must not contain surrounding whitespace");
        }
    }
}
