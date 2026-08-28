package com.dailylanguage.authentication.application;

import java.util.Objects;

public final class LocalRegistrationException extends RuntimeException {

    private final FailureReason failureReason;

    LocalRegistrationException(FailureReason failureReason) {
        super(messageFor(failureReason));
        this.failureReason = Objects.requireNonNull(
                failureReason,
                "failureReason must not be null");
    }

    public FailureReason failureReason() {
        return failureReason;
    }

    private static String messageFor(FailureReason failureReason) {
        return switch (Objects.requireNonNull(failureReason, "failureReason must not be null")) {
            case INVALID_EMAIL -> "Email address is invalid";
            case INVALID_PASSWORD_LENGTH -> "Password length is invalid";
            case INVALID_PASSWORD_CHARACTER -> "Password contains an unsupported character";
            case COMMON_OR_COMPROMISED_PASSWORD -> "Password is too common or compromised";
            case IDENTITY_UNAVAILABLE -> "Registration identity is unavailable";
            case REGISTRATION_FAILED -> "Local registration failed";
        };
    }

    public enum FailureReason {
        INVALID_EMAIL,
        INVALID_PASSWORD_LENGTH,
        INVALID_PASSWORD_CHARACTER,
        COMMON_OR_COMPROMISED_PASSWORD,
        IDENTITY_UNAVAILABLE,
        REGISTRATION_FAILED
    }
}
