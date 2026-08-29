package com.dailylanguage.languageprofile.application;

import java.util.Objects;

public final class LanguageProfileCreationException extends RuntimeException {

    private final FailureReason failureReason;

    LanguageProfileCreationException(FailureReason failureReason) {
        super(Objects.requireNonNull(failureReason, "failureReason must not be null").name());
        this.failureReason = failureReason;
    }

    public FailureReason failureReason() {
        return failureReason;
    }

    public enum FailureReason {
        INVALID_LANGUAGE_CODE,
        LANGUAGE_PROFILE_ALREADY_EXISTS
    }
}
