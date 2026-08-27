package com.dailylanguage.security;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisLoginAttemptRateLimiterTests {

    private static final String KEY_PREFIX = "daily-language:test:login-rate-limit";

    @Test
    void recordsClientAddressAndNormalizedEmailWithoutPuttingRawValuesInRedisKeys() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);
        RedisLoginAttemptRateLimiter rateLimiter = rateLimiter(redisTemplate);

        RedisLoginAttemptRateLimiter.LoginAttemptDecision firstDecision = rateLimiter.recordLoginAttempt(
                "203.0.113.42",
                " Learner@Example.COM ");
        RedisLoginAttemptRateLimiter.LoginAttemptDecision secondDecision = rateLimiter.recordLoginAttempt(
                "203.0.113.42",
                "learner@example.com");

        assertThat(firstDecision.allowed()).isTrue();
        assertThat(secondDecision.allowed()).isTrue();
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate, times(4)).execute(any(RedisScript.class), keys.capture(), any(), any());
        assertThat(keys.getAllValues())
                .allSatisfy(keyList -> assertThat(keyList).singleElement().satisfies(key -> assertThat(key)
                        .startsWith(KEY_PREFIX)
                        .doesNotContain("203.0.113.42")
                        .doesNotContain("learner@example.com")));
        assertThat(keys.getAllValues().get(1)).isEqualTo(keys.getAllValues().get(3));
    }

    @Test
    void returnsTheLongestRemainingWindowWhenEitherBucketRejectsTheAttempt() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(1_200L, 4_001L);
        RedisLoginAttemptRateLimiter rateLimiter = rateLimiter(redisTemplate);

        RedisLoginAttemptRateLimiter.LoginAttemptDecision decision = rateLimiter.recordLoginAttempt(
                "203.0.113.42",
                "learner@example.com");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(5);
    }

    @Test
    void rejectsInvalidConfiguration() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

        assertThatIllegalArgumentException().isThrownBy(() -> new RedisLoginAttemptRateLimiter(
                redisTemplate,
                Duration.ZERO,
                20,
                5,
                KEY_PREFIX));
        assertThatIllegalArgumentException().isThrownBy(() -> new RedisLoginAttemptRateLimiter(
                redisTemplate,
                Duration.ofMinutes(5),
                0,
                5,
                KEY_PREFIX));
        assertThatIllegalArgumentException().isThrownBy(() -> new RedisLoginAttemptRateLimiter(
                redisTemplate,
                Duration.ofMinutes(5),
                20,
                0,
                KEY_PREFIX));
        assertThatIllegalArgumentException().isThrownBy(() -> new RedisLoginAttemptRateLimiter(
                redisTemplate,
                Duration.ofMinutes(5),
                20,
                5,
                " "));
    }

    private static RedisLoginAttemptRateLimiter rateLimiter(StringRedisTemplate redisTemplate) {
        return new RedisLoginAttemptRateLimiter(
                redisTemplate,
                Duration.ofMinutes(5),
                20,
                5,
                KEY_PREFIX);
    }
}
