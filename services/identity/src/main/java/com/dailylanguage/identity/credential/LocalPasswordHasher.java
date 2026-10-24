package com.dailylanguage.identity.credential;

import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Identity 内部的密码处理组件；邮箱与密码 policy 由后续注册流程在调用前完成。 */
@Component
public final class LocalPasswordHasher {

    private static final String CURRENT_PASSWORD_HASH_VERSION = "argon2id-v1";
    private static final int SALT_LENGTH_BYTES = 16;
    private static final int HASH_LENGTH_BYTES = 32;
    private static final int PARALLELISM = 1;
    private static final int MEMORY_KIB = 19 * 1024;
    private static final int ITERATIONS = 2;
    private static final String CURRENT_PASSWORD_HASH_PREFIX = "{" + CURRENT_PASSWORD_HASH_VERSION
            + "}$argon2id$v=19$m=" + MEMORY_KIB + ",t=" + ITERATIONS + ",p=" + PARALLELISM;
    private static final Pattern CURRENT_PASSWORD_HASH_PATTERN = Pattern.compile(
            Pattern.quote(CURRENT_PASSWORD_HASH_PREFIX)
                    + "\\$([A-Za-z0-9+/]{22})\\$([A-Za-z0-9+/]{43})");

    private final PasswordEncoder passwordEncoder;
    private final PasswordHashConcurrencyGate passwordHashConcurrencyGate;

    public LocalPasswordHasher(PasswordHashConcurrencyGate passwordHashConcurrencyGate) {
        this.passwordHashConcurrencyGate = Objects.requireNonNull(
                passwordHashConcurrencyGate, "passwordHashConcurrencyGate must not be null");
        PasswordEncoder argon2idPasswordEncoder = new Argon2PasswordEncoder(
                SALT_LENGTH_BYTES, HASH_LENGTH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS);
        this.passwordEncoder = new DelegatingPasswordEncoder(
                CURRENT_PASSWORD_HASH_VERSION, Map.of(CURRENT_PASSWORD_HASH_VERSION, argon2idPasswordEncoder));
    }

    /** 生成 verifier；保留原始密码的空格和大小写，不在这里开启数据库事务或写入凭据。 */
    public String hash(CharSequence submittedPassword) {
        Objects.requireNonNull(submittedPassword, "submittedPassword must not be null");
        String encodedPasswordHash = passwordHashConcurrencyGate.runWithAvailableSlot(
                () -> passwordEncoder.encode(submittedPassword));
        if (!isCurrentPasswordHash(encodedPasswordHash)) {
            throw new IllegalStateException("Argon2id encoder produced an unexpected password hash format");
        }
        return encodedPasswordHash;
    }

    /** 格式不受支持时拒绝校验；容量不足时传播异常，不能将其误报为密码不匹配。 */
    public boolean matches(CharSequence submittedPassword, String encodedPasswordHash) {
        if (submittedPassword == null || !isCurrentPasswordHash(encodedPasswordHash)) {
            return false;
        }
        return passwordHashConcurrencyGate.runWithAvailableSlot(
                () -> passwordEncoder.matches(submittedPassword, encodedPasswordHash));
    }

    private static boolean isCurrentPasswordHash(String encodedPasswordHash) {
        if (encodedPasswordHash == null) {
            return false;
        }
        // 在占用容量和执行 Argon2 前检查参数，避免存储值任意提高计算成本。
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
