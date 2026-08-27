package com.dailylanguage.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.dailylanguage.authentication.domain.LocalEmailNormalizer;

@Component
public final class RedisAuthenticationAttemptRateLimiter {

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
    private final AttemptPolicy loginPolicy;
    private final AttemptPolicy registrationPolicy;

    @Autowired
    public RedisAuthenticationAttemptRateLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${app.security.login-rate-limit.window}") Duration loginWindow,
            @Value("${app.security.login-rate-limit.max-attempts-per-client-address}")
            int maxLoginAttemptsPerClientAddress,
            @Value("${app.security.login-rate-limit.max-attempts-per-email}") int maxLoginAttemptsPerEmail,
            @Value("${app.security.login-rate-limit.key-prefix}") String loginKeyPrefix,
            @Value("${app.security.registration-rate-limit.window}") Duration registrationWindow,
            @Value("${app.security.registration-rate-limit.max-attempts-per-client-address}")
            int maxRegistrationAttemptsPerClientAddress,
            @Value("${app.security.registration-rate-limit.max-attempts-per-email}")
            int maxRegistrationAttemptsPerEmail,
            @Value("${app.security.registration-rate-limit.key-prefix}") String registrationKeyPrefix) {
        this(
                redisTemplate,
                new AttemptPolicy(
                        loginWindow,
                        maxLoginAttemptsPerClientAddress,
                        maxLoginAttemptsPerEmail,
                        loginKeyPrefix),
                new AttemptPolicy(
                        registrationWindow,
                        maxRegistrationAttemptsPerClientAddress,
                        maxRegistrationAttemptsPerEmail,
                        registrationKeyPrefix));
    }

    RedisAuthenticationAttemptRateLimiter(
            StringRedisTemplate redisTemplate,
            AttemptPolicy loginPolicy,
            AttemptPolicy registrationPolicy) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.loginPolicy = Objects.requireNonNull(loginPolicy, "loginPolicy must not be null");
        this.registrationPolicy = Objects.requireNonNull(
                registrationPolicy,
                "registrationPolicy must not be null");
    }

    AttemptDecision recordLoginAttempt(String clientAddress, String submittedEmail) {
        return recordAttempt(loginPolicy, clientAddress, submittedEmail);
    }

    public AttemptDecision recordRegistrationAttempt(String clientAddress, String submittedEmail) {
        return recordAttempt(registrationPolicy, clientAddress, submittedEmail);
    }

    private AttemptDecision recordAttempt(
            AttemptPolicy policy,
            String clientAddress,
            String submittedEmail) {
        AttemptDecision clientAddressDecision = recordBucketAttempt(
                policy,
                "client-address",
                normalizedClientAddress(clientAddress),
                policy.maxAttemptsPerClientAddress());
        AttemptDecision emailDecision = recordBucketAttempt(
                policy,
                "email",
                normalizedEmailOrInvalidBucket(submittedEmail),
                policy.maxAttemptsPerEmail());

        if (clientAddressDecision.allowed() && emailDecision.allowed()) {
            return AttemptDecision.allow();
        }
        return AttemptDecision.rejectFor(Math.max(
                clientAddressDecision.retryAfterSeconds(),
                emailDecision.retryAfterSeconds()));
    }

    private AttemptDecision recordBucketAttempt(
            AttemptPolicy policy,
            String dimension,
            String bucketValue,
            int maxAttempts) {
        String bucketKey = policy.keyPrefix() + ':' + dimension + ':' + sha256(bucketValue);
        Long remainingWindowMillis = redisTemplate.execute(
                RECORD_ATTEMPT_SCRIPT,
                List.of(bucketKey),
                Long.toString(policy.attemptWindow().toMillis()),
                Integer.toString(maxAttempts));
        if (remainingWindowMillis == null) {
            throw new IllegalStateException("Redis did not return an authentication rate-limit decision");
        }
        if (remainingWindowMillis == 0) {
            return AttemptDecision.allow();
        }
        return AttemptDecision.rejectFor((remainingWindowMillis + 999) / 1_000);
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

    record AttemptPolicy(
            Duration attemptWindow,
            int maxAttemptsPerClientAddress,
            int maxAttemptsPerEmail,
            String keyPrefix) {

        AttemptPolicy {
            attemptWindow = requirePositive(attemptWindow, "attemptWindow");
            maxAttemptsPerClientAddress = requirePositive(
                    maxAttemptsPerClientAddress,
                    "maxAttemptsPerClientAddress");
            maxAttemptsPerEmail = requirePositive(maxAttemptsPerEmail, "maxAttemptsPerEmail");
            keyPrefix = requireText(keyPrefix, "keyPrefix");
        }
    }

    public record AttemptDecision(boolean allowed, long retryAfterSeconds) {

        public AttemptDecision {
            if (retryAfterSeconds < 0) {
                throw new IllegalArgumentException("retryAfterSeconds must not be negative");
            }
            if (allowed && retryAfterSeconds != 0) {
                throw new IllegalArgumentException("allowed decisions must not include retryAfterSeconds");
            }
            if (!allowed && retryAfterSeconds < 1) {
                throw new IllegalArgumentException("rejected decisions require retryAfterSeconds");
            }
        }

        static AttemptDecision allow() {
            return new AttemptDecision(true, 0);
        }

        static AttemptDecision rejectFor(long retryAfterSeconds) {
            return new AttemptDecision(false, retryAfterSeconds);
        }
    }
}
