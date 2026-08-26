package com.dailylanguage.authentication;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalPasswordHasherConcurrencyTests {

    @Test
    void registrationHashAndKnownOrUnknownLoginVerificationShareTheSameCapacity() throws Exception {
        PasswordHashConcurrencyGate concurrencyGate = new PasswordHashConcurrencyGate(1);
        LocalPasswordHasher passwordHasher = new LocalPasswordHasher(concurrencyGate);
        String storedPasswordHash = passwordHasher.hash("correct horse battery staple");
        CountDownLatch capacityOccupied = new CountDownLatch(1);
        CountDownLatch releaseCapacity = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> capacityHolder = executor.submit(() -> concurrencyGate.runWithAvailableSlot(() -> {
                capacityOccupied.countDown();
                await(releaseCapacity);
                return null;
            }));
            await(capacityOccupied);

            assertThatThrownBy(() -> passwordHasher.hash("a different valid password"))
                    .isInstanceOf(PasswordHashCapacityExceededException.class);
            assertThatThrownBy(() -> passwordHasher.matches(
                    "correct horse battery staple",
                    storedPasswordHash))
                    .isInstanceOf(PasswordHashCapacityExceededException.class);
            assertThatThrownBy(passwordHasher::createUnknownAccountPasswordHash)
                    .isInstanceOf(PasswordHashCapacityExceededException.class);

            assertThat(passwordHasher.matches("password", "malformed-hash")).isFalse();

            releaseCapacity.countDown();
            capacityHolder.get(5, TimeUnit.SECONDS);
        }
        finally {
            releaseCapacity.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating password hasher test", exception);
        }
    }
}
