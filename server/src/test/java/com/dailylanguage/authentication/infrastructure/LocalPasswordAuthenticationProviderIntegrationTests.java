package com.dailylanguage.authentication.infrastructure;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.security.domain.UserContext;
import com.dailylanguage.user.infrastructure.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "app.registration-enabled=true")
@Transactional
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class LocalPasswordAuthenticationProviderIntegrationTests {

    private static final String SUBMITTED_PASSWORD = "correct horse battery staple";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LocalAuthenticationRepository authenticationRepository;

    @Autowired
    private LocalPasswordHasher passwordHasher;

    @Autowired
    private LocalPasswordAuthenticationProvider authenticationProvider;

    @Test
    void authenticatesARealPersistedLocalCredential() {
        UUID userId = userRepository.create();
        authenticationRepository.createLocalEmailIdentity(
                userId,
                " Owner@Example.COM ",
                passwordHasher.hash(SUBMITTED_PASSWORD));
        var request = UsernamePasswordAuthenticationToken.unauthenticated(
                "owner@example.com",
                SUBMITTED_PASSWORD);

        var result = new ProviderManager(authenticationProvider).authenticate(request);

        assertThat(result.getPrincipal()).isEqualTo(new UserContext(userId));
        assertThat(result.getCredentials()).isNull();
        assertThat(request.getCredentials()).isNull();
    }
}
