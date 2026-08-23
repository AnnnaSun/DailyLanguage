package com.dailylanguage.authentication;

import java.util.Objects;

public final class LocalRegistrationException extends RuntimeException {

    private final Reason reason;

    LocalRegistrationException(Reason reason) {
        super(messageFor(reason));
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public Reason reason() {
        return reason;
    }

    private static String messageFor(Reason reason) {
        return switch (Objects.requireNonNull(reason, "reason must not be null")) {
            case INVALID_EMAIL -> "Email address is invalid";
            case INVALID_PASSWORD_LENGTH -> "Password length is invalid";
            case INVALID_PASSWORD_CHARACTER -> "Password contains an unsupported character";
            case COMMON_OR_COMPROMISED_PASSWORD -> "Password is too common or compromised";
            case IDENTITY_UNAVAILABLE -> "Registration identity is unavailable";
            case REGISTRATION_FAILED -> "Local registration failed";
        };
    }

    public enum Reason {
        INVALID_EMAIL,
        INVALID_PASSWORD_LENGTH,
        INVALID_PASSWORD_CHARACTER,
        COMMON_OR_COMPROMISED_PASSWORD,
        IDENTITY_UNAVAILABLE,
        REGISTRATION_FAILED
    }
}
