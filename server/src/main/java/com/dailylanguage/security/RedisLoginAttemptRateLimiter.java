package com.dailylanguage.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.dailylanguage.authentication.LocalEmailNormalizer;

@Component
public final class RedisLoginAttemptRateLimiter {

    private static final DefaultRedisScript<Long> RECORD_ATTEMPT_SCRIPT = new DefaultRedisScript<>("""
            local attempts = redis.call('INCR', KEYS[1])
            if attempts == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            if attempts > tonumber(ARGV[2]) then
                local remainingWindow = redis.call('PTTL', KEYS[1])
                if remainingWindow < 1 then 
                    redis.call('PEXPIRE', KEYS[1], ARGV[1])
                    return tonumber(ARGV[1])
                end
                return remainingWindow
            end
            return 0
            """, Long.class);

    private static final String INVALID_EMAIL_BUCKET = "invalid-email";

    private final StringRedisTemplate redisTemplate;
    private final Duration attemptWindow;
    private final int maxAttemptsPerClientAddress;
    private final int maxAttemptsPerEmail;
    private final String keyPrefix;

    public RedisLoginAttemptRateLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${app.security.login-rate-limit.window}") Duration attemptWindow,
            @Value("${app.security.login-rate-limit.max-attempts-per-client-address}")
            int maxAttemptsPerClientAddress,
            @Value("${app.security.login-rate-limit.max-attempts-per-email}") int maxAttemptsPerEmail,
            @Value("${app.security.login-rate-limit.key-prefix}") String keyPrefix) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.attemptWindow = requirePositive(attemptWindow, "attemptWindow");
        this.maxAttemptsPerClientAddress = requirePositive(
                maxAttemptsPerClientAddress,
                "maxAttemptsPerClientAddress");
        this.maxAttemptsPerEmail = requirePositive(maxAttemptsPerEmail, "maxAttemptsPerEmail");
        this.keyPrefix = requireText(keyPrefix, "keyPrefix");
    }

    LoginAttemptDecision recordLoginAttempt(String clientAddress, String submittedEmail) {
        LoginAttemptDecision clientAddressDecision = recordBucketAttempt(
                "client-address",
                normalizedClientAddress(clientAddress),
                maxAttemptsPerClientAddress);
        LoginAttemptDecision emailDecision = recordBucketAttempt(
                "email",
                normalizedEmailOrInvalidBucket(submittedEmail),
                maxAttemptsPerEmail);

        if (clientAddressDecision.allowed() && emailDecision.allowed()) {
            return LoginAttemptDecision.allow();
        }
        return LoginAttemptDecision.rejectFor(Math.max(
                clientAddressDecision.retryAfterSeconds(),
                emailDecision.retryAfterSeconds()));
    }

    private LoginAttemptDecision recordBucketAttempt(String dimension, String bucketValue, int maxAttempts) {
        String bucketKey = keyPrefix + ':' + dimension + ':' + sha256(bucketValue);
        Long remainingWindowMillis = redisTemplate.execute(
                RECORD_ATTEMPT_SCRIPT,
                List.of(bucketKey),
                Long.toString(attemptWindow.toMillis()),
                Integer.toString(maxAttempts));
        if (remainingWindowMillis == null) {
            throw new IllegalStateException("Redis did not return a login rate-limit decision");
        }
        if (remainingWindowMillis == 0) {
            return LoginAttemptDecision.allow();
        }
        return LoginAttemptDecision.rejectFor((remainingWindowMillis + 999) / 1_000);
    }

    private static String normalizedClientAddress(String clientAddress) {
        return clientAddress == null || clientAddress.isBlank() ? "unknown-client-address" : clientAddress;
    }

    private static String normalizedEmailOrInvalidBucket(String submittedEmail) {
        try {
            return LocalEmailNormalizer.normalize(submittedEmail);
        }
        catch (IllegalArgumentException | NullPointerException exception) {
            return INVALID_EMAIL_BUCKET;
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative() || value.toMillis() < 1) {
            throw new IllegalArgumentException(name + " must be at least 1 millisecond");
        }
        return value;
    }

    private static int requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be at least 1");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    record LoginAttemptDecision(boolean allowed, long retryAfterSeconds) {

        LoginAttemptDecision {
            if (retryAfterSeconds < 0) {
                throw new IllegalArgumentException("retryAfterSeconds must not be negative");
            }
        }

        static LoginAttemptDecision allow() {
            return new LoginAttemptDecision(true, 0);
        }

        static LoginAttemptDecision rejectFor(long retryAfterSeconds) {
            if (retryAfterSeconds < 1) {
                throw new IllegalArgumentException("retryAfterSeconds must be at least 1 when rejected");
            }
            return new LoginAttemptDecision(false, retryAfterSeconds);
        }
    }
}
