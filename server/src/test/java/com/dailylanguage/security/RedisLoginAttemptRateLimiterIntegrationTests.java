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
class RedisLoginAttemptRateLimiterIntegrationTests {

    private static final int MAX_ATTEMPTS_PER_EMAIL = 3;

    private final String keyPrefix = "daily-language:test:login-rate-limit:" + UUID.randomUUID();
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RedisLoginAttemptRateLimiter rateLimiter;

    @BeforeAll
    void connectToRedis() {
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(redisHost, redisPort));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        rateLimiter = new RedisLoginAttemptRateLimiter(
                redisTemplate,
                Duration.ofMinutes(5),
                20,
                MAX_ATTEMPTS_PER_EMAIL,
                keyPrefix);
    }

    @AfterEach
    void deleteTestBuckets() {
        Set<String> testKeys = redisTemplate.keys(keyPrefix + "*");
        if (!testKeys.isEmpty()) {
            redisTemplate.delete(testKeys);
        }
    }

    @AfterAll
    void disconnectFromRedis() {
        connectionFactory.destroy();
    }

    @Test
    void concurrentAttemptsAtomicallyEnforceTheEmailLimitAndExpireTheirBuckets() throws Exception {
        int concurrentAttempts = 12;
        CountDownLatch startTogether = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(concurrentAttempts);

        try {
            List<Future<RedisLoginAttemptRateLimiter.LoginAttemptDecision>> decisions =
                    java.util.stream.IntStream.range(0, concurrentAttempts)
                            .mapToObj(index -> executor.submit(() -> {
                                assertThat(startTogether.await(5, TimeUnit.SECONDS)).isTrue();
                                return rateLimiter.recordLoginAttempt(
                                        "203.0.113.42",
                                        "learner@example.com");
                            }))
                            .toList();
            startTogether.countDown();

            long allowedAttempts = 0;
            for (Future<RedisLoginAttemptRateLimiter.LoginAttemptDecision> decision : decisions) {
                if (decision.get(5, TimeUnit.SECONDS).allowed()) {
                    allowedAttempts++;
                }
            }

            assertThat(allowedAttempts).isEqualTo(MAX_ATTEMPTS_PER_EMAIL);
            Set<String> bucketKeys = redisTemplate.keys(keyPrefix + "*");
            assertThat(bucketKeys).hasSize(2)
                    .allSatisfy(key -> {
                        assertThat(key)
                                .doesNotContain("203.0.113.42")
                                .doesNotContain("learner@example.com");
                        assertThat(redisTemplate.getExpire(key)).isPositive();
                    });
        }
        finally {
            startTogether.countDown();
            executor.shutdownNow();
        }
    }
}
