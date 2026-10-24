package com.dailylanguage.identity.credential;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** 直接测试真实 Human Core；并发协调用 latch，不依赖固定睡眠或 mock 容量。 */
class PasswordHashConcurrencyGateTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withBean(PasswordHashConcurrencyGate.class);

    @Test
    void returnsResultsAndReleasesCapacityAfterSuccessFalseAndNull() {
        var capacity = new PasswordHashConcurrencyGate(1);
        assertThat(capacity.runWithAvailableSlot(() -> "completed")).isEqualTo("completed");
        assertThat(capacity.runWithAvailableSlot(() -> false)).isFalse();
        assertThat(capacity.<String>runWithAvailableSlot(() -> null)).isNull();
        assertThat(capacity.runWithAvailableSlot(() -> true)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("operationFailures")
    void propagatesOperationFailureAndReleasesItsCapacity(Throwable failure) {
        var capacity = new PasswordHashConcurrencyGate(1);
        assertThatThrownBy(() -> capacity.runWithAvailableSlot(() -> {
            if (failure instanceof Error error) {
                throw error;
            }
            throw (RuntimeException) failure;
        })).isSameAs(failure);
        assertThat(capacity.runWithAvailableSlot(() -> "recovered")).isEqualTo("recovered");
    }

    static Stream<Throwable> operationFailures() {
        return Stream.of(new IllegalStateException("synthetic operation failure"),
                new AssertionError("synthetic operation error"));
    }

    @Test
    void rejectsNullOperation() {
        var capacity = new PasswordHashConcurrencyGate(1);
        assertThatThrownBy(() -> capacity.runWithAvailableSlot(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("passwordHashOperation must not be null");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveCapacity(int configuredCapacity) {
        assertThatThrownBy(() -> new PasswordHashConcurrencyGate(configuredCapacity))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWithoutRunningOrReleasingAnotherOperationSlotAndSharesCapacityWithHasher() throws Exception {
        var capacity = new PasswordHashConcurrencyGate(1);
        var passwords = new LocalPasswordHasher(capacity);
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var rejectedOperationRan = new AtomicBoolean();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> capacity.runWithAvailableSlot(() -> {
                started.countDown();
                await(finish);
                return "completed";
            }));
            try {
                await(started);
                // 给拒绝路径设置截止时间，误用阻塞获取时也应失败，而不是挂住测试。
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                    // 重复拒绝不能增加可用名额，否则后续调用会突破配置上限。
                    for (int attempt = 0; attempt < 2; attempt++) {
                        assertThatThrownBy(() -> capacity.runWithAvailableSlot(() -> {
                            rejectedOperationRan.set(true);
                            return "must-not-run";
                        })).isInstanceOf(PasswordHashCapacityExceededException.class);
                    }
                    String verifier = "{argon2id-v1}$argon2id$v=19$m=19456,t=2,p=1$"
                            + "MDEyMzQ1Njc4OWFiY2RlZg$MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY";
                    assertThatThrownBy(() -> passwords.hash("synthetic password"))
                            .isInstanceOf(PasswordHashCapacityExceededException.class);
                    assertThatThrownBy(() -> passwords.matches("synthetic password", verifier))
                            .isInstanceOf(PasswordHashCapacityExceededException.class);
                });
                assertThat(rejectedOperationRan).isFalse();
            }
            finally {
                finish.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("completed");
        }
        String verifier = passwords.hash("synthetic password");
        assertThat(passwords.matches("synthetic password", verifier)).isTrue();
    }

    @Test
    void supportsConfiguredParallelCapacity() throws Exception {
        var capacity = new PasswordHashConcurrencyGate(2);
        var started = new CountDownLatch(2);
        var finish = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> capacity.runWithAvailableSlot(() -> {
                started.countDown();
                await(finish);
                return "first";
            }));
            var second = executor.submit(() -> capacity.runWithAvailableSlot(() -> {
                started.countDown();
                await(finish);
                return "second";
            }));
            try {
                await(started);
                assertTimeoutPreemptively(Duration.ofSeconds(2),
                        () -> assertThatThrownBy(() -> capacity.runWithAvailableSlot(() -> "must-not-run"))
                                .isInstanceOf(PasswordHashCapacityExceededException.class));
            }
            finally {
                finish.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("first");
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("second");
        }
    }

    @Test
    void bindsExplicitCapacityFromIdentityConfiguration() {
        contextRunner.withPropertyValues("IDENTITY_PASSWORD_HASH_MAX_CONCURRENT=2").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PasswordHashConcurrencyGate.class);
            assertThat(context.getEnvironment().getProperty(
                    "app.security.password-hashing.max-concurrent", Integer.class)).isEqualTo(2);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "not-a-number"})
    void rejectsInvalidConfigurationAtStartup(String configuredCapacity) {
        contextRunner.withPropertyValues("IDENTITY_PASSWORD_HASH_MAX_CONCURRENT=" + configuredCapacity)
                .run(context -> assertThat(context).hasFailed());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating password hash test", exception);
        }
    }
}
