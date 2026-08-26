package com.dailylanguage.authentication;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class PasswordHashConcurrencyGate {

    private final Semaphore availablePasswordHashSlots;

    public PasswordHashConcurrencyGate(
            @Value("${app.security.password-hashing.max-concurrent}") int maxConcurrentHashes) {
        if (maxConcurrentHashes < 1) {
            throw new IllegalArgumentException("maxConcurrentHashes must be at least 1");
        }
        this.availablePasswordHashSlots = new Semaphore(maxConcurrentHashes);
    }

    <T> T runWithAvailableSlot(Supplier<T> passwordHashOperation) {
        Objects.requireNonNull(passwordHashOperation, "passwordHashOperation must not be null");
        if (!availablePasswordHashSlots.tryAcquire()) {
            throw new PasswordHashCapacityExceededException();
        }

        try {
            return passwordHashOperation.get();
        }
        finally {
            availablePasswordHashSlots.release();
        }
    }
}
