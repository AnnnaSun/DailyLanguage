package com.dailylanguage.security.infrastructure;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dailylanguage.authentication.application.RegistrationCapability;
import com.dailylanguage.security.domain.UserContext;
import com.dailylanguage.user.infrastructure.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PersistentSingleUserTests {

    @Test
    void registrationEnabledDoesNotBootstrapSingleUser() {
        UserRepository userRepository = mock(UserRepository.class);

        PersistentSingleUser persistentSingleUser = new PersistentSingleUser(
                new RegistrationCapability(true),
                userRepository);

        assertThat(persistentSingleUser.userContext()).isEmpty();
        verifyNoInteractions(userRepository);
    }

    @Test
    void registrationDisabledBootstrapsTrustedSingleUserContext() {
        UUID userId = UUID.randomUUID();
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.getOrCreateSingleUser()).thenReturn(userId);

        PersistentSingleUser persistentSingleUser = new PersistentSingleUser(
                new RegistrationCapability(false),
                userRepository);

        assertThat(persistentSingleUser.userContext()).contains(new UserContext(userId));
        verify(userRepository).getOrCreateSingleUser();
    }
}
