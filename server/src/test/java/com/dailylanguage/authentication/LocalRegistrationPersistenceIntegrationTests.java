package com.dailylanguage.authentication;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.dailylanguage.authentication.LocalRegistrationException.Reason.IDENTITY_UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class LocalRegistrationPersistenceIntegrationTests {

    private static final String RAW_PASSWORD = "safe-pass-12";

    @Autowired
    private LocalRegistrationService registrationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsUserIdentityAndCredentialAtomically() {
        String email = uniqueEmail();
        UUID userId = registrationService.register(email, RAW_PASSWORD);

        try {
            assertThat(count("SELECT COUNT(*) FROM app_user WHERE id = ?", userId)).isOne();
            assertThat(count(
                    "SELECT COUNT(*) FROM auth_identity WHERE user_id = ? AND provider_subject = ?",
                    userId,
                    email)).isOne();
            assertThat(count("""
                    SELECT COUNT(*)
                    FROM local_password_credential credential
                    JOIN auth_identity identity ON identity.id = credential.auth_identity_id
                    WHERE identity.user_id = ?
                    """, userId)).isOne();
        }
        finally {
            deleteRegistration(userId);
        }
    }

    @Test
    void concurrentDuplicateRegistrationCreatesExactlyOneCompleteAccount() throws Exception {
        String email = uniqueEmail();
        int userCountBefore = count("SELECT COUNT(*) FROM app_user");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<RegistrationAttempt> registration = () -> registerWhenReleased(email, ready, start);

        List<RegistrationAttempt> attempts;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<RegistrationAttempt> first = executor.submit(registration);
            Future<RegistrationAttempt> second = executor.submit(registration);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            attempts = List.of(first.get(), second.get());
        }

        List<UUID> successfulUserIds = attempts.stream()
                .filter(attempt -> attempt.userId() != null)
                .map(RegistrationAttempt::userId)
                .toList();
        try {
            assertThat(successfulUserIds).hasSize(1);
            assertThat(attempts.stream()
                    .filter(attempt -> attempt.reason() == IDENTITY_UNAVAILABLE))
                    .hasSize(1);
            assertThat(count("SELECT COUNT(*) FROM app_user")).isEqualTo(userCountBefore + 1);
            assertThat(count(
                    "SELECT COUNT(*) FROM auth_identity WHERE provider_subject = ?",
                    email)).isOne();
            assertThat(count("""
                    SELECT COUNT(*)
                    FROM local_password_credential credential
                    JOIN auth_identity identity ON identity.id = credential.auth_identity_id
                    WHERE identity.provider_subject = ?
                    """, email)).isOne();
        }
        finally {
            successfulUserIds.forEach(this::deleteRegistration);
        }
    }

    private RegistrationAttempt registerWhenReleased(
            String email,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return new RegistrationAttempt(registrationService.register(email, RAW_PASSWORD), null);
        }
        catch (LocalRegistrationException exception) {
            return new RegistrationAttempt(null, exception.reason());
        }
    }

    private int count(String sql, Object... arguments) {
        Integer result = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        return result == null ? 0 : result;
    }

    private void deleteRegistration(UUID userId) {
        jdbcTemplate.update("DELETE FROM auth_identity WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
    }

    private static String uniqueEmail() {
        return "s4b2c-" + UUID.randomUUID() + "@example.com";
    }

    private record RegistrationAttempt(
            UUID userId,
            LocalRegistrationException.Reason reason
    ) {
    }
}
