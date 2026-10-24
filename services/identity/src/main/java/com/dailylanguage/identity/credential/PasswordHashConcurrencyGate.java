package com.dailylanguage.identity.credential;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 散列和校验共用的进程级容量；不等待、不排队，也不承担跨进程限流。 */
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

    /**
     * Human Core：只有取得名额的调用才能执行 passwordHashOperation。
     * 容量不足立即抛 PasswordHashCapacityExceededException，不执行操作、不归还他人占用的名额。
     * 正常结果（包括 false / null）原样返回；操作失败向上传播，已经取得的名额仍必须归还。
     * Supplier 表示取得名额之后才执行的操作；这里不记录密码、verifier 或异常正文。
     */
    <T> T runWithAvailableSlot(Supplier<T> passwordHashOperation) {
        Objects.requireNonNull(passwordHashOperation, "passwordHashOperation must not be null");
        boolean acquired = false;
        acquired = availablePasswordHashSlots.tryAcquire();
        if (!acquired) {
            throw new PasswordHashCapacityExceededException();
        }
        try {
            return passwordHashOperation.get();
        }finally {
            availablePasswordHashSlots.release();
        }
    }
}
