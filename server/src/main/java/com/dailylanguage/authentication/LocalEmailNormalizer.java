package com.dailylanguage.authentication;

import java.util.Locale;
import java.util.Objects;

public final class LocalEmailNormalizer {

    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_LOCAL_PART_LENGTH = 64;
    private static final int MAX_DOMAIN_LABEL_LENGTH = 63;

    private LocalEmailNormalizer() {
    }

    /**
     * Normalizes a LOCAL_EMAIL login subject, not an email claim from Apple/OIDC identity tokens.
     * External providers must be keyed by their stable provider subject rather than this email value.
     */
    public static String normalize(String email) {
        Objects.requireNonNull(email, "email must not be null");
        String candidate = email.strip();
        if (!candidate.chars().allMatch(character -> character <= 0x7f)) {
            throw invalidEmail();
        }

        candidate = candidate.toLowerCase(Locale.ROOT);
        int atIndex = candidate.indexOf('@');

        if (candidate.isEmpty()
                || candidate.length() > MAX_EMAIL_LENGTH
                || atIndex <= 0
                || atIndex != candidate.lastIndexOf('@')
                || atIndex == candidate.length() - 1) {
            throw invalidEmail();
        }

        String localPart = candidate.substring(0, atIndex);
        String domain = candidate.substring(atIndex + 1);
        if (localPart.length() > MAX_LOCAL_PART_LENGTH
                || localPart.startsWith(".")
                || localPart.endsWith(".")
                || localPart.contains("..")
                || !localPart.chars().allMatch(LocalEmailNormalizer::isLocalPartCharacter)
                || !isValidDomain(domain)) {
            throw invalidEmail();
        }

        return candidate;
    }

    private static boolean isLocalPartCharacter(int character) {
        return character >= 'a' && character <= 'z'
                || character >= '0' && character <= '9'
                || ".!#$%&'*+-/=?^_`{|}~".indexOf(character) >= 0;
    }

    private static boolean isValidDomain(String domain) {
        for (String label : domain.split("\\.", -1)) {
            if (label.isEmpty()
                    || label.length() > MAX_DOMAIN_LABEL_LENGTH
                    || label.startsWith("-")
                    || label.endsWith("-")
                    || !label.chars().allMatch(LocalEmailNormalizer::isDomainCharacter)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDomainCharacter(int character) {
        return character >= 'a' && character <= 'z'
                || character >= '0' && character <= '9'
                || character == '-';
    }

    private static IllegalArgumentException invalidEmail() {
        return new IllegalArgumentException("email must be a valid ASCII address");
    }
}
