package com.dailylanguage.authentication.infrastructure;

import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.user.infrastructure.UserRepository;

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
     * 在同一 transaction 中原子写入 User、LOCAL_EMAIL identity 与应用管理的 password credential。
     * External provider identity 不进入这条 password-credential transaction。
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
