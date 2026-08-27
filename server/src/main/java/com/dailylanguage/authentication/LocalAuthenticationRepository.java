package com.dailylanguage.authentication;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.authentication.domain.LocalEmailNormalizer;

@Repository
public class LocalAuthenticationRepository {

    private static final String LOCAL_EMAIL_PROVIDER = "LOCAL_EMAIL";
    private static final String SUPPORTED_PASSWORD_HASH_PREFIX = "{argon2id-v1}$";
    private static final int MAX_PASSWORD_HASH_LENGTH = 512;

    private final LocalAuthenticationMapper localAuthenticationMapper;

    public LocalAuthenticationRepository(LocalAuthenticationMapper localAuthenticationMapper) {
        this.localAuthenticationMapper = localAuthenticationMapper;
    }

    /**
     * Attaches an application-managed LOCAL_EMAIL credential to an existing User.
     * Provider-issued subjects such as Apple {@code sub} values require their own persistence flow.
     */
    @Transactional
    public UUID createLocalEmailIdentity(
            UUID userId,
            String normalizedEmail,
            String encodedPasswordHash) {
        Objects.requireNonNull(userId, "userId must not be null");
        String validatedNormalizedEmail = LocalEmailNormalizer.normalize(normalizedEmail);
        validateEncodedPasswordHash(encodedPasswordHash);

        UUID authenticationIdentityId = localAuthenticationMapper.insertAuthenticationIdentityAndReturnId(
                userId,
                LOCAL_EMAIL_PROVIDER,
                validatedNormalizedEmail);
        localAuthenticationMapper.insertLocalPasswordCredential(
                authenticationIdentityId,
                encodedPasswordHash);
        return authenticationIdentityId;
    }

    /**
     * Loads only the application-managed LOCAL_EMAIL credential used for password authentication.
     */
    public Optional<StoredLocalPasswordCredential> findByEmail(String submittedEmail) {
        return localAuthenticationMapper.findLocalPasswordCredential(
                LOCAL_EMAIL_PROVIDER,
                LocalEmailNormalizer.normalize(submittedEmail));
    }

    private static void validateEncodedPasswordHash(String encodedPasswordHash) {
        Objects.requireNonNull(encodedPasswordHash, "encodedPasswordHash must not be null");
        if (!encodedPasswordHash.startsWith(SUPPORTED_PASSWORD_HASH_PREFIX)
                || encodedPasswordHash.length() > MAX_PASSWORD_HASH_LENGTH) {
            throw new IllegalArgumentException("encodedPasswordHash must use argon2id-v1");
        }
    }
}

record StoredLocalPasswordCredential(
        UUID authenticationIdentityId,
        UUID userId,
        String normalizedEmail,
        String storedPasswordHash
) {

    @Override
    public String toString() {
        return "StoredLocalPasswordCredential[authenticationIdentityId=" + authenticationIdentityId
                + ", userId=" + userId
                + ", normalizedEmail=" + normalizedEmail
                + ", storedPasswordHash=<redacted>]";
    }
}
