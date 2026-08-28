package com.dailylanguage.authentication.domain;

import java.util.Objects;

import org.springframework.stereotype.Component;

@Component
public final class LocalPasswordPolicy {

    private static final int MINIMUM_LENGTH = 12;
    private static final int MAXIMUM_LENGTH = 64;

    private final LocalPasswordBlocklist passwordBlocklist;

    public LocalPasswordPolicy(LocalPasswordBlocklist passwordBlocklist) {
        this.passwordBlocklist = Objects.requireNonNull(
                passwordBlocklist,
                "passwordBlocklist must not be null");
    }

    /**
     * Validates a submitted password for an application-managed password credential.
     * Upstream provider passwords and phone OTP values must never be passed into this policy.
     */
    public ValidationResult validate(String submittedPassword, String normalizedEmail) {
        if (submittedPassword == null) {
            return ValidationResult.INVALID_LENGTH;
        }
        // Unsupported characters take precedence so callers can return the actionable ASCII-only error.
        if (!containsOnlyPrintableAscii(submittedPassword)) {
            return ValidationResult.INVALID_CHARACTER;
        }
        if (submittedPassword.length() < MINIMUM_LENGTH || submittedPassword.length() > MAXIMUM_LENGTH) {
            return ValidationResult.INVALID_LENGTH;
        }

        Objects.requireNonNull(normalizedEmail, "normalizedEmail must not be null");
        if (matchesLoginIdentity(submittedPassword, normalizedEmail)
                || passwordBlocklist.contains(submittedPassword)) {
            return ValidationResult.COMMON_OR_COMPROMISED;
        }
        return ValidationResult.ACCEPTED;
    }

    private static boolean containsOnlyPrintableAscii(String submittedPassword) {
        for (int index = 0; index < submittedPassword.length(); index++) {
            char character = submittedPassword.charAt(index);
            if (character < 0x20 || character > 0x7e) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesLoginIdentity(String submittedPassword, String normalizedEmail) {
        // Identity normalization must not case-fold, trim, or otherwise change the submitted password.
        if (submittedPassword.equals(normalizedEmail)) {
            return true;
        }
        int emailAtSignIndex = normalizedEmail.indexOf('@');
        return emailAtSignIndex > 0
                && submittedPassword.regionMatches(0, normalizedEmail, 0, emailAtSignIndex)
                && submittedPassword.length() == emailAtSignIndex;
    }

    public enum ValidationResult {
        ACCEPTED,
        INVALID_LENGTH,
        INVALID_CHARACTER,
        COMMON_OR_COMPROMISED
    }
}
