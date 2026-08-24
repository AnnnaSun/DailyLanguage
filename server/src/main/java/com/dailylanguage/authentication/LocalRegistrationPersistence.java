package com.dailylanguage.authentication;

import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.user.UserRepository;

@Component
public class LocalRegistrationPersistence {

    private final UserRepository userRepository;
    private final LocalAuthenticationRepository localAuthenticationRepository;

    public LocalRegistrationPersistence(
            UserRepository userRepository,
            LocalAuthenticationRepository localAuthenticationRepository) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.localAuthenticationRepository = Objects.requireNonNull(
                localAuthenticationRepository,
                "localAuthenticationRepository must not be null");
    }

    /**
     * Atomically persists the User, LOCAL_EMAIL identity, and application-managed password credential.
     * External provider identities do not enter this password-credential transaction.
     */
    @Transactional
    public UUID createLocalAccount(String normalizedEmail, String encodedPasswordHash) {
        Objects.requireNonNull(normalizedEmail, "normalizedEmail must not be null");
        Objects.requireNonNull(encodedPasswordHash, "encodedPasswordHash must not be null");

        UUID userId = userRepository.create();
        localAuthenticationRepository.createLocalEmailIdentity(
                userId,
                normalizedEmail,
                encodedPasswordHash);
        return userId;
    }
}
