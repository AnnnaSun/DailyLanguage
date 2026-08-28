package com.dailylanguage.security.infrastructure;

import java.util.Optional;

import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;

import com.dailylanguage.authentication.application.RegistrationCapability;
import com.dailylanguage.authentication.application.RegistrationCapability.State;
import com.dailylanguage.security.domain.UserContext;
import com.dailylanguage.user.infrastructure.UserRepository;

/**
 * 注册关闭时，在 database initialization 完成后建立并持有可信的默认用户身份。
 */
@Component
@DependsOnDatabaseInitialization
public final class PersistentSingleUser {

    private final UserContext userContext;

    public PersistentSingleUser(
            RegistrationCapability registrationCapability,
            UserRepository userRepository) {
        this.userContext = registrationCapability.state() == State.DISABLED
                ? new UserContext(userRepository.getOrCreateSingleUser())
                : null;
    }

    public Optional<UserContext> userContext() {
        return Optional.ofNullable(userContext);
    }
}
