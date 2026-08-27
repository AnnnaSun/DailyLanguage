package com.dailylanguage.security;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_REDIS_TESTS", matches = "true")
class RedisAuthenticationAttemptRateLimiterIntegrationTests {

    private static final int MAX_REGISTRATION_ATTEMPTS_PER_EMAIL = 3;

    private final String loginKeyPrefix = "daily-language:test:login-rate-limit:" + UUID.randomUUID();
    private final String registrationKeyPrefix =
            "daily-language:test:registration-rate-limit:" + UUID.randomUUID();
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RedisAuthenticationAttemptRateLimiter rateLimiter;

    @BeforeAll
    void connectToRedis() {
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(redisHost, redisPort));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        rateLimiter = new RedisAuthenticationAttemptRateLimiter(
                redisTemplate,
                new RedisAuthenticationAttemptRateLimiter.AttemptPolicy(
                        Duration.ofMinutes(5),
                        20,
                        5,
                        loginKeyPrefix),
                new RedisAuthenticationAttemptRateLimiter.AttemptPolicy(
                        Duration.ofHours(1),
                        5,
                        MAX_REGISTRATION_ATTEMPTS_PER_EMAIL,
                        registrationKeyPrefix));
    }

    @AfterEach
    void deleteTestBuckets() {
        deleteKeys(loginKeyPrefix);
        deleteKeys(registrationKeyPrefix);
    }

    @AfterAll
    void disconnectFromRedis() {
        connectionFactory.destroy();
    }

    @Test
    void concurrentAttemptsAtomicallyEnforceTheRegistrationEmailLimitAndExpireTheirBuckets()
            throws Exception {
        int concurrentAttempts = 12;
        CountDownLatch startTogether = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(concurrentAttempts);

        try {
            List<Future<RedisAuthenticationAttemptRateLimiter.AttemptDecision>> decisions =
                    java.util.stream.IntStream.range(0, concurrentAttempts)
                            .mapToObj(index -> executor.submit(() -> {
                                assertThat(startTogether.await(5, TimeUnit.SECONDS)).isTrue();
                                return rateLimiter.recordRegistrationAttempt(
                                        "203.0.113." + index,
                                        "learner@example.com");
                            }))
                            .toList();
            startTogether.countDown();

            long allowedAttempts = 0;
            for (Future<RedisAuthenticationAttemptRateLimiter.AttemptDecision> decision : decisions) {
                if (decision.get(5, TimeUnit.SECONDS).allowed()) {
                    allowedAttempts++;
                }
            }

            assertThat(allowedAttempts).isEqualTo(MAX_REGISTRATION_ATTEMPTS_PER_EMAIL);
            Set<String> bucketKeys = redisTemplate.keys(registrationKeyPrefix + "*");
            assertThat(bucketKeys).hasSize(concurrentAttempts + 1)
                    .allSatisfy(key -> {
                        assertThat(key)
                                .doesNotContain("203.0.113.")
                                .doesNotContain("learner@example.com");
                        assertThat(redisTemplate.getExpire(key)).isPositive();
                    });
        }
        finally {
            startTogether.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void loginAttemptsDoNotConsumeRegistrationQuota() {
        for (int attempt = 0; attempt < MAX_REGISTRATION_ATTEMPTS_PER_EMAIL; attempt++) {
            assertThat(rateLimiter.recordLoginAttempt("203.0.113.43", "separate@example.com").allowed())
                    .isTrue();
        }

        for (int attempt = 0; attempt < MAX_REGISTRATION_ATTEMPTS_PER_EMAIL; attempt++) {
            assertThat(rateLimiter.recordRegistrationAttempt("203.0.113.43", "separate@example.com").allowed())
                    .isTrue();
        }
        assertThat(rateLimiter.recordRegistrationAttempt("203.0.113.43", "separate@example.com").allowed())
                .isFalse();
    }

    @Test
    void registrationClientAddressLimitAllowsFiveAttemptsPerHour() {
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(rateLimiter.recordRegistrationAttempt(
                    "203.0.113.44",
                    "learner-" + attempt + "@example.com").allowed()).isTrue();
        }

        assertThat(rateLimiter.recordRegistrationAttempt(
                "203.0.113.44",
                "learner-5@example.com").allowed()).isFalse();
    }

    private void deleteKeys(String keyPrefix) {
        Set<String> testKeys = redisTemplate.keys(keyPrefix + "*");
        if (!testKeys.isEmpty()) {
            redisTemplate.delete(testKeys);
        }
    }
}
