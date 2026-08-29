package com.dailylanguage.authentication.infrastructure;

import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public final class LocalPasswordHasher {

    private static final String UNKNOWN_ACCOUNT_PLACEHOLDER_PASSWORD =
            "daily-language-local-authentication-placeholder";
    private static final String CURRENT_PASSWORD_HASH_VERSION = "argon2id-v1";
    private static final int SALT_LENGTH_BYTES = 16;
    private static final int HASH_LENGTH_BYTES = 32;
    private static final int PARALLELISM = 1;
    private static final int MEMORY_KIB = 19 * 1024;
    private static final int ITERATIONS = 2;
    private static final String CURRENT_PASSWORD_HASH_PREFIX = "{" + CURRENT_PASSWORD_HASH_VERSION
            + "}$argon2id$v=19$m=" + MEMORY_KIB
            + ",t=" + ITERATIONS
            + ",p=" + PARALLELISM;
    private static final Pattern CURRENT_PASSWORD_HASH_PATTERN = Pattern.compile(
            Pattern.quote(CURRENT_PASSWORD_HASH_PREFIX)
                    + "\\$([A-Za-z0-9+/]{22})\\$([A-Za-z0-9+/]{43})");

    private final PasswordEncoder passwordEncoder;
    private final PasswordHashConcurrencyGate passwordHashConcurrencyGate;

    public LocalPasswordHasher(PasswordHashConcurrencyGate passwordHashConcurrencyGate) {
        this.passwordHashConcurrencyGate = Objects.requireNonNull(
                passwordHashConcurrencyGate,
                "passwordHashConcurrencyGate must not be null");
        PasswordEncoder argon2idPasswordEncoder = new Argon2PasswordEncoder(
                SALT_LENGTH_BYTES,
                HASH_LENGTH_BYTES,
                PARALLELISM,
                MEMORY_KIB,
                ITERATIONS);
        this.passwordEncoder = new DelegatingPasswordEncoder(
                CURRENT_PASSWORD_HASH_VERSION,
                Map.of(CURRENT_PASSWORD_HASH_VERSION, argon2idPasswordEncoder));
    }

    /**
     * 使用当前 password-hash version 编码应用管理的 local password material。
     * Registration 只持久化正常结果；unknown-account hash 仅保留在内存。
     * Apple / OIDC provider 管理的密码不得进入该边界。
     */
    public String hash(CharSequence submittedPassword) {
        Objects.requireNonNull(submittedPassword, "submittedPassword must not be null");
        String encodedPasswordHash = passwordHashConcurrencyGate.runWithAvailableSlot(
                () -> passwordEncoder.encode(submittedPassword));
        if (!isCurrentPasswordHash(encodedPasswordHash)) {
            throw new IllegalStateException("Argon2id encoder produced an unexpected password hash format");
        }
        return encodedPasswordHash;
    }

    /**
     * 只验证应用管理的 local password 与对应 stored password hash。
     */
    public boolean matches(CharSequence submittedPassword, String encodedPasswordHash) {
        if (submittedPassword == null || !isCurrentPasswordHash(encodedPasswordHash)) {
            return false;
        }
        return passwordHashConcurrencyGate.runWithAvailableSlot(
                () -> passwordEncoder.matches(submittedPassword, encodedPasswordHash));
    }

    public boolean needsUpgrade(String encodedPasswordHash) {
        return isCurrentPasswordHash(encodedPasswordHash)
                && passwordEncoder.upgradeEncoding(encodedPasswordHash);
    }

    /**
     * 创建非持久化 placeholder hash，使 unknown-account login 也执行当前 Argon2id verification path。
     */
    String createUnknownAccountPasswordHash() {
        return hash(UNKNOWN_ACCOUNT_PLACEHOLDER_PASSWORD);
    }

    private static boolean isCurrentPasswordHash(String encodedPasswordHash) {
        if (encodedPasswordHash == null) {
            return false;
        }
        // encoded hash 不得自行选择未批准的 Argon2 resource parameter。
        Matcher matcher = CURRENT_PASSWORD_HASH_PATTERN.matcher(encodedPasswordHash);
        if (!matcher.matches()) {
            return false;
        }
        return isCanonicalBase64(matcher.group(1), SALT_LENGTH_BYTES)
                && isCanonicalBase64(matcher.group(2), HASH_LENGTH_BYTES);
    }

    private static boolean isCanonicalBase64(String encodedValue, int expectedDecodedByteCount) {
        try {
            byte[] decodedBytes = Base64.getDecoder().decode(encodedValue);
            return decodedBytes.length == expectedDecodedByteCount
                    && Base64.getEncoder().withoutPadding().encodeToString(decodedBytes).equals(encodedValue);
        }
        catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
