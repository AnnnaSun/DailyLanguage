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
     * 校验应用管理 password credential 的 submitted password。
     * Upstream provider password 与 phone OTP value 不得进入该 policy。
     */
    public ValidationResult validate(String submittedPassword, String normalizedEmail) {
        if (submittedPassword == null) {
            return ValidationResult.INVALID_LENGTH;
        }
        // Unsupported character 优先于长度判断，使调用方返回更可操作的 ASCII-only 错误。
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
        // Identity normalization 不能反向用于 password；不得 case-fold、trim 或改写 submitted password。
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
