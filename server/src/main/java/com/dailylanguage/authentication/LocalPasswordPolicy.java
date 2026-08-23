package com.dailylanguage.authentication;

import java.util.Objects;

import org.springframework.stereotype.Component;

@Component
public final class LocalPasswordPolicy {

    private static final int MINIMUM_LENGTH = 12;
    private static final int MAXIMUM_LENGTH = 64;

    private final LocalPasswordBlocklist blocklist;

    public LocalPasswordPolicy(LocalPasswordBlocklist blocklist) {
        this.blocklist = Objects.requireNonNull(blocklist, "blocklist must not be null");
    }

    public ValidationResult validate(String candidate, String normalizedEmail) {
        if (candidate == null) {
            return ValidationResult.INVALID_LENGTH;
        }
        // Unsupported characters take precedence so callers can return the actionable ASCII-only error.
        if (!containsOnlyPrintableAscii(candidate)) {
            return ValidationResult.INVALID_CHARACTER;
        }
        if (candidate.length() < MINIMUM_LENGTH || candidate.length() > MAXIMUM_LENGTH) {
            return ValidationResult.INVALID_LENGTH;
        }

        Objects.requireNonNull(normalizedEmail, "normalizedEmail must not be null");
        if (matchesLoginIdentity(candidate, normalizedEmail) || blocklist.contains(candidate)) {
            return ValidationResult.COMMON_OR_COMPROMISED;
        }
        return ValidationResult.ACCEPTED;
    }

    private static boolean containsOnlyPrintableAscii(String candidate) {
        for (int index = 0; index < candidate.length(); index++) {
            char character = candidate.charAt(index);
            if (character < 0x20 || character > 0x7e) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesLoginIdentity(String candidate, String normalizedEmail) {
        // Identity normalization must not case-fold, trim, or otherwise change the password candidate.
        if (candidate.equals(normalizedEmail)) {
            return true;
        }
        int separator = normalizedEmail.indexOf('@');
        return separator > 0 && candidate.regionMatches(0, normalizedEmail, 0, separator)
                && candidate.length() == separator;
    }

    public enum ValidationResult {
        ACCEPTED,
        INVALID_LENGTH,
        INVALID_CHARACTER,
        COMMON_OR_COMPROMISED
    }
}
