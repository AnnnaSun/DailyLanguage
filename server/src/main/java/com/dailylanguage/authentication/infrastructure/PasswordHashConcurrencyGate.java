package com.dailylanguage.authentication.infrastructure;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 进程级 Argon2 concurrency hard limit。容量耗尽时立即 fail fast，
 * 避免请求排队继续占用线程和内存；所有 slot 都必须在 finally 中归还。
 */
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
        // tryAcquire 不等待：拒绝语义比无界排队更能保护 Hosted / Self-hosted 进程容量。
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
