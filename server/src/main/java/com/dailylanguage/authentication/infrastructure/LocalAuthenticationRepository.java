package com.dailylanguage.authentication.infrastructure;

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
     * 为已有 User 绑定由本应用管理的 LOCAL_EMAIL credential。
     * Apple {@code sub} 等 provider-issued subject 必须使用独立 persistence flow。
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
     * 只读取 password authentication 所需的应用管理 LOCAL_EMAIL credential。
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
        // credential object 可能进入诊断日志，任何情况下都不能展开 stored password hash。
        return "StoredLocalPasswordCredential[authenticationIdentityId=" + authenticationIdentityId
                + ", userId=" + userId
                + ", normalizedEmail=" + normalizedEmail
                + ", storedPasswordHash=<redacted>]";
    }
}
