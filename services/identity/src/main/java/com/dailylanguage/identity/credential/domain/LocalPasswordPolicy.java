package com.dailylanguage.identity.credential.domain;

import java.util.Objects;

import org.springframework.stereotype.Component;

@Component
public final class LocalPasswordPolicy {

    private static final int MINIMUM_LENGTH = 12;
    private static final int MAXIMUM_LENGTH = 64;

    private final LocalPasswordBlocklist passwordBlocklist;

    public LocalPasswordPolicy(LocalPasswordBlocklist passwordBlocklist) {
        this.passwordBlocklist = Objects.requireNonNull(passwordBlocklist, "passwordBlocklist must not be null");
    }

    /**
     * Human Core：只校验 Identity 管理的注册密码，normalizedEmail 由调用方预先规范化。
     * 密码保留原样；null 返回 INVALID_LENGTH，非法字符优先于长度检查。
     * 语法和长度合法后要求 normalizedEmail 非 null，再检查邮箱关联与固定 blocklist。
     * 不在此执行散列、数据库写入或输出密码；返回值仅描述输入规则的校验结果。
     */
    public ValidationResult validate(String submittedPassword, String normalizedEmail) {
        if (submittedPassword == null){
            return ValidationResult.INVALID_LENGTH;
        }
        if (!containsOnlyPrintableAscii(submittedPassword)){
            return ValidationResult.INVALID_CHARACTER;
        }
        if (submittedPassword.length() < MINIMUM_LENGTH || submittedPassword.length() > MAXIMUM_LENGTH) {
            return ValidationResult.INVALID_LENGTH;
        }
        Objects.requireNonNull(normalizedEmail, "normalizedEmail must not be null");
        if (matchesLoginIdentity(submittedPassword, normalizedEmail)||
                passwordBlocklist.contains(submittedPassword)) {
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
        // 邮箱规范化不能反向用于密码；仅比较完整原值，不 trim 或改写大小写。
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
