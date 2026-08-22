package com.dailylanguage.authentication;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LocalAuthenticationRepository {

    private static final String LOCAL_EMAIL = "LOCAL_EMAIL";
    private static final String VERIFIER_PREFIX = "{argon2id-v1}$";
    private static final int MAX_VERIFIER_LENGTH = 512;

    private final LocalAuthenticationMapper localAuthenticationMapper;

    public LocalAuthenticationRepository(LocalAuthenticationMapper localAuthenticationMapper) {
        this.localAuthenticationMapper = localAuthenticationMapper;
    }

    @Transactional
    public UUID create(UUID userId, String email, String encodedPasswordVerifier) {
        Objects.requireNonNull(userId, "userId must not be null");
        String normalizedEmail = LocalEmailNormalizer.normalize(email);
        validateEncodedVerifier(encodedPasswordVerifier);

        UUID identityId = localAuthenticationMapper.insertIdentityReturningId(
                userId,
                LOCAL_EMAIL,
                normalizedEmail);
        localAuthenticationMapper.insertCredential(identityId, encodedPasswordVerifier);
        return identityId;
    }

    public Optional<LocalAuthenticationCredential> findByEmail(String email) {
        return localAuthenticationMapper.findCredential(
                LOCAL_EMAIL,
                LocalEmailNormalizer.normalize(email));
    }

    private static void validateEncodedVerifier(String encodedPasswordVerifier) {
        Objects.requireNonNull(encodedPasswordVerifier, "encodedPasswordVerifier must not be null");
        if (!encodedPasswordVerifier.startsWith(VERIFIER_PREFIX)
                || encodedPasswordVerifier.length() > MAX_VERIFIER_LENGTH) {
            throw new IllegalArgumentException("encodedPasswordVerifier must use argon2id-v1");
        }
    }
}

record LocalAuthenticationCredential(
        UUID authIdentityId,
        UUID userId,
        String normalizedEmail,
        String passwordVerifier
) {

    @Override
    public String toString() {
        return "LocalAuthenticationCredential[authIdentityId=" + authIdentityId
                + ", userId=" + userId
                + ", normalizedEmail=" + normalizedEmail
                + ", passwordVerifier=<redacted>]";
    }
}
