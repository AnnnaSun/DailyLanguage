package com.dailylanguage.authentication;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordHashConcurrencyGateTests {

    @Test
    void rejectsImmediatelyWhenAllPasswordHashSlotsAreInUseAndRecoversAfterRelease() throws Exception {
        PasswordHashConcurrencyGate concurrencyGate = new PasswordHashConcurrencyGate(1);
        CountDownLatch firstOperationStarted = new CountDownLatch(1);
        CountDownLatch allowFirstOperationToFinish = new CountDownLatch(1);
        AtomicBoolean rejectedOperationRan = new AtomicBoolean();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<String> firstOperation = executor.submit(() -> concurrencyGate.runWithAvailableSlot(() -> {
                firstOperationStarted.countDown();
                await(allowFirstOperationToFinish);
                return "first-completed";
            }));
            await(firstOperationStarted);

            assertThatThrownBy(() -> concurrencyGate.runWithAvailableSlot(() -> {
                rejectedOperationRan.set(true);
                return "must-not-run";
            })).isInstanceOf(PasswordHashCapacityExceededException.class)
                    .hasMessage("Password hash capacity is currently exhausted");
            assertThat(rejectedOperationRan).isFalse();

            allowFirstOperationToFinish.countDown();
            assertThat(firstOperation.get(5, TimeUnit.SECONDS)).isEqualTo("first-completed");
            assertThat(concurrencyGate.runWithAvailableSlot(() -> "recovered")).isEqualTo("recovered");
        }
        finally {
            allowFirstOperationToFinish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void releasesThePasswordHashSlotWhenTheOperationReturnsFalseOrThrows() {
        PasswordHashConcurrencyGate concurrencyGate = new PasswordHashConcurrencyGate(1);

        assertThat(concurrencyGate.runWithAvailableSlot(() -> false)).isFalse();
        assertThatThrownBy(() -> concurrencyGate.runWithAvailableSlot(() -> {
            throw new IllegalStateException("hash operation failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(concurrencyGate.runWithAvailableSlot(() -> true)).isTrue();
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PasswordHashConcurrencyGate(0))
                .withMessage("maxConcurrentHashes must be at least 1");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PasswordHashConcurrencyGate(-1))
                .withMessage("maxConcurrentHashes must be at least 1");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating password hash concurrency test", exception);
        }
    }
}
