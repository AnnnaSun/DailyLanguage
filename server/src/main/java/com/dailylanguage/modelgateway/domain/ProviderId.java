package com.dailylanguage.modelgateway.domain;

import java.util.Objects;

/**
 * Provider 的外部可配置标识；拒绝外围空白而不自动修正，避免配置产生歧义。
 */
public record ProviderId(String value) {

    public ProviderId {
        Objects.requireNonNull(value, "providerId must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException("providerId must not contain surrounding whitespace");
        }
    }
}
