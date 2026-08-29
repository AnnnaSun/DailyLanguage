package com.dailylanguage.authentication.application;

import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.dailylanguage.authentication.domain.LocalEmailNormalizer;
import com.dailylanguage.authentication.domain.LocalPasswordPolicy;
import com.dailylanguage.authentication.infrastructure.LocalPasswordHasher;
import com.dailylanguage.authentication.infrastructure.LocalRegistrationPersistence;
import com.dailylanguage.authentication.infrastructure.PasswordHashCapacityExceededException;

import static com.dailylanguage.authentication.application.LocalRegistrationException.FailureReason.COMMON_OR_COMPROMISED_PASSWORD;
import static com.dailylanguage.authentication.application.LocalRegistrationException.FailureReason.IDENTITY_UNAVAILABLE;
import static com.dailylanguage.authentication.application.LocalRegistrationException.FailureReason.INVALID_EMAIL;
import static com.dailylanguage.authentication.application.LocalRegistrationException.FailureReason.INVALID_PASSWORD_CHARACTER;
import static com.dailylanguage.authentication.application.LocalRegistrationException.FailureReason.INVALID_PASSWORD_LENGTH;
import static com.dailylanguage.authentication.application.LocalRegistrationException.FailureReason.REGISTRATION_FAILED;

@Service
public final class LocalRegistrationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalRegistrationService.class);

    private final LocalPasswordPolicy passwordPolicy;
    private final LocalPasswordHasher passwordHasher;
    private final LocalRegistrationPersistence registrationPersistence;

    public LocalRegistrationService(
            LocalPasswordPolicy passwordPolicy,
            LocalPasswordHasher passwordHasher,
            LocalRegistrationPersistence registrationPersistence) {
        this.passwordPolicy = Objects.requireNonNull(passwordPolicy, "passwordPolicy must not be null");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher must not be null");
        this.registrationPersistence = Objects.requireNonNull(
                registrationPersistence,
                "registrationPersistence must not be null");
    }

    /**
     * 注册由本应用管理 password credential 的 LOCAL_EMAIL identity。
     * Apple / OIDC credential 与 phone OTP secret 必须进入各自的 provider-specific flow。
     */
    public UUID register(String submittedEmail, String submittedPassword) {
        String normalizedEmail = normalizeEmail(submittedEmail);
        // 先做低成本 deterministic policy，再占用受限的 Argon2 capacity。
        validatePassword(submittedPassword, normalizedEmail);
        String encodedPasswordHash = hashPassword(submittedPassword);

        try {
            return registrationPersistence.createLocalAccount(normalizedEmail, encodedPasswordHash);
        }
        catch (DuplicateKeyException exception) {
            throw new LocalRegistrationException(IDENTITY_UNAVAILABLE);
        }
        catch (RuntimeException exception) {
            logUnexpectedFailure("PERSISTENCE", exception);
            throw new LocalRegistrationException(REGISTRATION_FAILED);
        }
    }

    private static String normalizeEmail(String submittedEmail) {
        try {
            return LocalEmailNormalizer.normalize(submittedEmail);
        }
        catch (IllegalArgumentException | NullPointerException exception) {
            throw new LocalRegistrationException(INVALID_EMAIL);
        }
    }

    private void validatePassword(String submittedPassword, String normalizedEmail) {
        LocalPasswordPolicy.ValidationResult passwordValidationResult;
        try {
            passwordValidationResult = passwordPolicy.validate(submittedPassword, normalizedEmail);
        }
        catch (RuntimeException exception) {
            logUnexpectedFailure("PASSWORD_POLICY", exception);
            throw new LocalRegistrationException(REGISTRATION_FAILED);
        }

        switch (passwordValidationResult) {
            case ACCEPTED -> {
                return;
            }
            case INVALID_LENGTH -> throw new LocalRegistrationException(INVALID_PASSWORD_LENGTH);
            case INVALID_CHARACTER -> throw new LocalRegistrationException(INVALID_PASSWORD_CHARACTER);
            case COMMON_OR_COMPROMISED -> throw new LocalRegistrationException(
                    COMMON_OR_COMPROMISED_PASSWORD);
        }
    }

    private String hashPassword(String submittedPassword) {
        try {
            return passwordHasher.hash(submittedPassword);
        }
        catch (PasswordHashCapacityExceededException exception) {
            throw new LocalRegistrationException(REGISTRATION_FAILED);
        }
        catch (RuntimeException exception) {
            logUnexpectedFailure("PASSWORD_HASH", exception);
            throw new LocalRegistrationException(REGISTRATION_FAILED);
        }
    }

    private static void logUnexpectedFailure(String failureStage, RuntimeException exception) {
        // Database exception message 与 throwable chain 可能包含提交的 email，日志只保留 stage 和类型。
        LOGGER.error(
                "Local registration failed stage={} exceptionType={}",
                failureStage,
                exception.getClass().getName());
    }
}
