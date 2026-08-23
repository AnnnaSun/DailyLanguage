package com.dailylanguage.authentication;

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

    private static final String CURRENT_VERSION = "argon2id-v1";
    private static final int SALT_LENGTH_BYTES = 16;
    private static final int HASH_LENGTH_BYTES = 32;
    private static final int PARALLELISM = 1;
    private static final int MEMORY_KIB = 19 * 1024;
    private static final int ITERATIONS = 2;
    private static final String CURRENT_VERIFIER_PREFIX = "{" + CURRENT_VERSION
            + "}$argon2id$v=19$m=" + MEMORY_KIB
            + ",t=" + ITERATIONS
            + ",p=" + PARALLELISM;
    private static final Pattern CURRENT_VERIFIER_PATTERN = Pattern.compile(
            Pattern.quote(CURRENT_VERIFIER_PREFIX)
                    + "\\$([A-Za-z0-9+/]{22})\\$([A-Za-z0-9+/]{43})");

    private final PasswordEncoder passwordEncoder;

    public LocalPasswordHasher() {
        PasswordEncoder argon2idV1 = new Argon2PasswordEncoder(
                SALT_LENGTH_BYTES,
                HASH_LENGTH_BYTES,
                PARALLELISM,
                MEMORY_KIB,
                ITERATIONS);
        this.passwordEncoder = new DelegatingPasswordEncoder(
                CURRENT_VERSION,
                Map.of(CURRENT_VERSION, argon2idV1));
    }

    /**
     * Encodes only an application-managed local password for persistence.
     * Passwords owned by Apple/OIDC providers must never reach this boundary.
     */
    public String hash(CharSequence rawPassword) {
        Objects.requireNonNull(rawPassword, "rawPassword must not be null");
        String encodedVerifier = passwordEncoder.encode(rawPassword);
        if (!isCurrentVerifier(encodedVerifier)) {
            throw new IllegalStateException("Argon2id encoder produced an unexpected verifier format");
        }
        return encodedVerifier;
    }

    /**
     * Verifies only an application-managed local password against its stored verifier.
     */
    public boolean matches(CharSequence rawPassword, String encodedVerifier) {
        if (rawPassword == null || !isCurrentVerifier(encodedVerifier)) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, encodedVerifier);
    }

    public boolean needsUpgrade(String encodedVerifier) {
        return isCurrentVerifier(encodedVerifier)
                && passwordEncoder.upgradeEncoding(encodedVerifier);
    }

    private static boolean isCurrentVerifier(String encodedVerifier) {
        if (encodedVerifier == null) {
            return false;
        }
        // A verifier must not be allowed to select unexpected Argon2 resource parameters.
        Matcher matcher = CURRENT_VERIFIER_PATTERN.matcher(encodedVerifier);
        if (!matcher.matches()) {
            return false;
        }
        return isCanonicalBase64(matcher.group(1), SALT_LENGTH_BYTES)
                && isCanonicalBase64(matcher.group(2), HASH_LENGTH_BYTES);
    }

    private static boolean isCanonicalBase64(String encoded, int expectedBytes) {
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            return decoded.length == expectedBytes
                    && Base64.getEncoder().withoutPadding().encodeToString(decoded).equals(encoded);
        }
        catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
