package com.dailylanguage.authentication.domain;

import java.util.Locale;
import java.util.Objects;

public final class LocalEmailNormalizer {

    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_LOCAL_PART_LENGTH = 64;
    private static final int MAX_DOMAIN_LABEL_LENGTH = 63;

    private LocalEmailNormalizer() {
    }

    /**
     * 规范化 LOCAL_EMAIL login subject，而不是 Apple / OIDC identity token 中的 email claim。
     * External provider 必须以稳定 provider subject 为 key，不能复用这里的 email identity。
     */
    public static String normalize(String submittedEmail) {
        Objects.requireNonNull(submittedEmail, "submittedEmail must not be null");
        String normalizedEmail = submittedEmail.strip();
        if (!normalizedEmail.chars().allMatch(character -> character <= 0x7f)) {
            throw invalidEmail();
        }

        normalizedEmail = normalizedEmail.toLowerCase(Locale.ROOT);
        int atSignIndex = normalizedEmail.indexOf('@');

        if (normalizedEmail.isEmpty()
                || normalizedEmail.length() > MAX_EMAIL_LENGTH
                || atSignIndex <= 0
                || atSignIndex != normalizedEmail.lastIndexOf('@')
                || atSignIndex == normalizedEmail.length() - 1) {
            throw invalidEmail();
        }

        String localPart = normalizedEmail.substring(0, atSignIndex);
        String domain = normalizedEmail.substring(atSignIndex + 1);
        if (localPart.length() > MAX_LOCAL_PART_LENGTH
                || localPart.startsWith(".")
                || localPart.endsWith(".")
                || localPart.contains("..")
                || !localPart.chars().allMatch(LocalEmailNormalizer::isLocalPartCharacter)
                || !isValidDomain(domain)) {
            throw invalidEmail();
        }

        return normalizedEmail;
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
