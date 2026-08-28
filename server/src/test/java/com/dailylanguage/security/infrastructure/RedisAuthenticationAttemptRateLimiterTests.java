package com.dailylanguage.security.infrastructure;

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

class RedisAuthenticationAttemptRateLimiterTests {

    private static final String LOGIN_KEY_PREFIX = "daily-language:test:login-rate-limit";
    private static final String REGISTRATION_KEY_PREFIX = "daily-language:test:registration-rate-limit";

    @Test
    void loginAndRegistrationUseIndependentHashedNamespaces() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);
        RedisAuthenticationAttemptRateLimiter rateLimiter = rateLimiter(redisTemplate);

        RedisAuthenticationAttemptRateLimiter.AttemptDecision loginDecision = rateLimiter.recordLoginAttempt(
                "203.0.113.42",
                " Learner@Example.COM ");
        RedisAuthenticationAttemptRateLimiter.AttemptDecision registrationDecision =
                rateLimiter.recordRegistrationAttempt("203.0.113.42", "learner@example.com");

        assertThat(loginDecision.allowed()).isTrue();
        assertThat(registrationDecision.allowed()).isTrue();
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate, times(4)).execute(any(RedisScript.class), keys.capture(), any(), any());
        assertThat(keys.getAllValues().subList(0, 2))
                .allSatisfy(keyList -> assertThat(keyList).singleElement().satisfies(key -> assertThat(key)
                        .startsWith(LOGIN_KEY_PREFIX)
                        .doesNotContain("203.0.113.42")
                        .doesNotContain("learner@example.com")));
        assertThat(keys.getAllValues().subList(2, 4))
                .allSatisfy(keyList -> assertThat(keyList).singleElement().satisfies(key -> assertThat(key)
                        .startsWith(REGISTRATION_KEY_PREFIX)
                        .doesNotContain("203.0.113.42")
                        .doesNotContain("learner@example.com")));
        assertThat(keys.getAllValues().get(1).getFirst())
                .endsWith(keys.getAllValues().get(3).getFirst().substring(
                        keys.getAllValues().get(3).getFirst().lastIndexOf(':') + 1));
    }

    @Test
    void returnsTheLongestRemainingWindowWhenEitherRegistrationBucketRejectsTheAttempt() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(1_200L, 4_001L);
        RedisAuthenticationAttemptRateLimiter rateLimiter = rateLimiter(redisTemplate);

        RedisAuthenticationAttemptRateLimiter.AttemptDecision decision =
                rateLimiter.recordRegistrationAttempt("203.0.113.42", "learner@example.com");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(5);
    }

    @Test
    void invalidRegistrationEmailsShareOneHashedBucket() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);
        RedisAuthenticationAttemptRateLimiter rateLimiter = rateLimiter(redisTemplate);

        rateLimiter.recordRegistrationAttempt("203.0.113.42", "not-an-email");
        rateLimiter.recordRegistrationAttempt("203.0.113.43", null);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate, times(4)).execute(any(RedisScript.class), keys.capture(), any(), any());
        String firstInvalidEmailKey = keys.getAllValues().get(1).getFirst();
        String secondInvalidEmailKey = keys.getAllValues().get(3).getFirst();
        assertThat(firstInvalidEmailKey)
                .isEqualTo(secondInvalidEmailKey)
                .startsWith(REGISTRATION_KEY_PREFIX)
                .doesNotContain("not-an-email")
                .doesNotContain("invalid-email");
    }

    @Test
    void rejectsInvalidPolicyConfiguration() {
        assertThatIllegalArgumentException().isThrownBy(() -> policy(Duration.ZERO, 20, 5, LOGIN_KEY_PREFIX));
        assertThatIllegalArgumentException().isThrownBy(() -> policy(
                Duration.ofMinutes(5),
                0,
                5,
                LOGIN_KEY_PREFIX));
        assertThatIllegalArgumentException().isThrownBy(() -> policy(
                Duration.ofMinutes(5),
                20,
                0,
                LOGIN_KEY_PREFIX));
        assertThatIllegalArgumentException().isThrownBy(() -> policy(Duration.ofMinutes(5), 20, 5, " "));
    }

    private static RedisAuthenticationAttemptRateLimiter rateLimiter(StringRedisTemplate redisTemplate) {
        return new RedisAuthenticationAttemptRateLimiter(
                redisTemplate,
                policy(Duration.ofMinutes(5), 20, 5, LOGIN_KEY_PREFIX),
                policy(Duration.ofHours(1), 5, 3, REGISTRATION_KEY_PREFIX));
    }

    private static RedisAuthenticationAttemptRateLimiter.AttemptPolicy policy(
            Duration window,
            int maxAttemptsPerClientAddress,
            int maxAttemptsPerEmail,
            String keyPrefix) {
        return new RedisAuthenticationAttemptRateLimiter.AttemptPolicy(
                window,
                maxAttemptsPerClientAddress,
                maxAttemptsPerEmail,
                keyPrefix);
    }
}
