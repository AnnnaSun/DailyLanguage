package com.dailylanguage.authentication;

import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import static com.dailylanguage.authentication.LocalRegistrationException.Reason.COMMON_OR_COMPROMISED_PASSWORD;
import static com.dailylanguage.authentication.LocalRegistrationException.Reason.IDENTITY_UNAVAILABLE;
import static com.dailylanguage.authentication.LocalRegistrationException.Reason.INVALID_EMAIL;
import static com.dailylanguage.authentication.LocalRegistrationException.Reason.INVALID_PASSWORD_CHARACTER;
import static com.dailylanguage.authentication.LocalRegistrationException.Reason.INVALID_PASSWORD_LENGTH;
import static com.dailylanguage.authentication.LocalRegistrationException.Reason.REGISTRATION_FAILED;

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
     * Registers a LOCAL_EMAIL identity whose password credential is managed by this application.
     * Apple/OIDC credentials and phone OTP secrets must use provider-specific flows instead.
     */
    public UUID register(String email, String rawPassword) {
        String normalizedEmail = normalizeEmail(email);
        validatePassword(rawPassword, normalizedEmail);
        String encodedVerifier = hashPassword(rawPassword);

        try {
            return registrationPersistence.create(normalizedEmail, encodedVerifier);
        }
        catch (DuplicateKeyException exception) {
            throw new LocalRegistrationException(IDENTITY_UNAVAILABLE);
        }
        catch (RuntimeException exception) {
            logUnexpectedFailure("PERSISTENCE", exception);
            throw new LocalRegistrationException(REGISTRATION_FAILED);
        }
    }

    private static String normalizeEmail(String email) {
        try {
            return LocalEmailNormalizer.normalize(email);
        }
        catch (IllegalArgumentException | NullPointerException exception) {
            throw new LocalRegistrationException(INVALID_EMAIL);
        }
    }

    private void validatePassword(String rawPassword, String normalizedEmail) {
        LocalPasswordPolicy.ValidationResult validationResult;
        try {
            validationResult = passwordPolicy.validate(rawPassword, normalizedEmail);
        }
        catch (RuntimeException exception) {
            logUnexpectedFailure("PASSWORD_POLICY", exception);
            throw new LocalRegistrationException(REGISTRATION_FAILED);
        }

        switch (validationResult) {
            case ACCEPTED -> {
                return;
            }
            case INVALID_LENGTH -> throw new LocalRegistrationException(INVALID_PASSWORD_LENGTH);
            case INVALID_CHARACTER -> throw new LocalRegistrationException(INVALID_PASSWORD_CHARACTER);
            case COMMON_OR_COMPROMISED -> throw new LocalRegistrationException(
                    COMMON_OR_COMPROMISED_PASSWORD);
        }
    }

    private String hashPassword(String rawPassword) {
        try {
            return passwordHasher.hash(rawPassword);
        }
        catch (RuntimeException exception) {
            logUnexpectedFailure("PASSWORD_HASH", exception);
            throw new LocalRegistrationException(REGISTRATION_FAILED);
        }
    }

    private static void logUnexpectedFailure(String stage, RuntimeException exception) {
        // Database exception messages and throwable chains can contain the submitted email.
        LOGGER.error(
                "Local registration failed stage={} exceptionType={}",
                stage,
                exception.getClass().getName());
    }
}
