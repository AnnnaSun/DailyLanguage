package com.dailylanguage.identity.account.application;

import java.util.UUID;
import java.util.stream.Stream;

import com.dailylanguage.identity.account.infrastructure.LocalAccountPersistence;
import com.dailylanguage.identity.credential.infrastructure.LocalPasswordHasher;
import com.dailylanguage.identity.credential.domain.LocalPasswordPolicy;
import com.dailylanguage.identity.credential.infrastructure.PasswordHashCapacityExceededException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;

import static com.dailylanguage.identity.account.application.LocalRegistrationException.FailureReason.*;
import static com.dailylanguage.identity.credential.domain.LocalPasswordPolicy.ValidationResult.ACCEPTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.*;

/** 断言真实注册合同，不把 Human Core 的未实现占位当成预期成功。 */
@ExtendWith(OutputCaptureExtension.class)
class LocalRegistrationServiceTests {

    private static final String PASSWORD = "  Safe registration 42  ";
    private static final String EMAIL = "owner@example.com";
    private static final String VERIFIER = "{argon2id-v1}$synthetic-verifier";

    private final LocalPasswordPolicy policy = mock(LocalPasswordPolicy.class);
    private final LocalPasswordHasher hasher = mock(LocalPasswordHasher.class);
    private final LocalAccountPersistence accounts = mock(LocalAccountPersistence.class);
    private final LocalRegistrationService registrations = new LocalRegistrationService(policy, hasher, accounts);

    @Test
    void normalizesValidatesHashesAndPersistsInOrderWithoutChangingPassword() {
        UUID userId = UUID.randomUUID();
        prepareAcceptedPassword();
        when(accounts.createAccountWithLanguageAccess(EMAIL, VERIFIER)).thenReturn(userId);

        assertThat(registrations.register(" Owner@Example.COM ", PASSWORD)).isEqualTo(userId);

        var order = inOrder(policy, hasher, accounts);
        order.verify(policy).validate(PASSWORD, EMAIL);
        order.verify(hasher).hash(PASSWORD);
        order.verify(accounts).createAccountWithLanguageAccess(EMAIL, VERIFIER);
        order.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "not-an-email")
    void rejectsInvalidEmailBeforeAnyPasswordOrDatabaseWork(String submittedEmail) {
        assertFailure(() -> registrations.register(submittedEmail, PASSWORD), INVALID_EMAIL);
        verifyNoInteractions(policy, hasher, accounts);
    }

    @ParameterizedTest
    @MethodSource("passwordRejections")
    void mapsPolicyRejectionWithoutHashingOrPersistence(
            LocalPasswordPolicy.ValidationResult result, LocalRegistrationException.FailureReason reason) {
        when(policy.validate(PASSWORD, EMAIL)).thenReturn(result);
        assertFailure(() -> registrations.register(EMAIL, PASSWORD), reason);
        verifyNoInteractions(hasher, accounts);
    }

    static Stream<Arguments> passwordRejections() {
        return Stream.of(
                Arguments.of(LocalPasswordPolicy.ValidationResult.INVALID_LENGTH, INVALID_PASSWORD_LENGTH),
                Arguments.of(LocalPasswordPolicy.ValidationResult.INVALID_CHARACTER, INVALID_PASSWORD_CHARACTER),
                Arguments.of(LocalPasswordPolicy.ValidationResult.COMMON_OR_COMPROMISED,
                        COMMON_OR_COMPROMISED_PASSWORD));
    }

    @Test
    void treatsUnexpectedPolicyFailureAsInternalFailureInsteadOfInvalidInput(CapturedOutput output) {
        when(policy.validate(PASSWORD, EMAIL)).thenThrow(new IllegalStateException(sensitiveDetail()));
        assertFailure(() -> registrations.register(EMAIL, PASSWORD), REGISTRATION_FAILED);
        verifyNoInteractions(hasher, accounts);
        assertSafeLog(output, "PASSWORD_POLICY", IllegalStateException.class);
    }

    @Test
    void rejectsCapacityExhaustionWithoutPersistenceOrErrorLog(CapturedOutput output) {
        when(policy.validate(PASSWORD, EMAIL)).thenReturn(ACCEPTED);
        when(hasher.hash(PASSWORD)).thenThrow(PasswordHashCapacityExceededException.class);
        assertFailure(() -> registrations.register(EMAIL, PASSWORD), REGISTRATION_FAILED);
        verifyNoInteractions(accounts);
        assertThat(output).doesNotContain("Local registration failed", PASSWORD, EMAIL, VERIFIER);
    }

    @Test
    void mapsUnexpectedHashFailureWithoutPersistenceOrSensitiveCause(CapturedOutput output) {
        when(policy.validate(PASSWORD, EMAIL)).thenReturn(ACCEPTED);
        when(hasher.hash(PASSWORD)).thenThrow(new IllegalStateException(sensitiveDetail()));
        assertFailure(() -> registrations.register(EMAIL, PASSWORD), REGISTRATION_FAILED);
        verifyNoInteractions(accounts);
        assertSafeLog(output, "PASSWORD_HASH", IllegalStateException.class);
    }

    @Test
    void mapsDuplicateIdentityWithoutLoggingCauseOrRetrying(CapturedOutput output) {
        prepareAcceptedPassword();
        when(accounts.createAccountWithLanguageAccess(EMAIL, VERIFIER))
                .thenThrow(new DuplicateKeyException(sensitiveDetail()));
        assertFailure(() -> registrations.register(EMAIL, PASSWORD), IDENTITY_UNAVAILABLE);
        verify(accounts).createAccountWithLanguageAccess(EMAIL, VERIFIER);
        verifyNoMoreInteractions(accounts);
        assertThat(output).doesNotContain("Local registration failed", PASSWORD, EMAIL, VERIFIER);
    }

    @Test
    void mapsUnexpectedPersistenceFailureWithoutLeakingCauseOrRetrying(CapturedOutput output) {
        prepareAcceptedPassword();
        when(accounts.createAccountWithLanguageAccess(EMAIL, VERIFIER))
                .thenThrow(new DataAccessResourceFailureException(sensitiveDetail(),
                        new IllegalStateException(sensitiveDetail())));
        assertFailure(() -> registrations.register(EMAIL, PASSWORD), REGISTRATION_FAILED);
        verify(accounts).createAccountWithLanguageAccess(EMAIL, VERIFIER);
        verifyNoMoreInteractions(accounts);
        assertSafeLog(output, "PERSISTENCE", DataAccessResourceFailureException.class);
    }

    private void prepareAcceptedPassword() {
        when(policy.validate(PASSWORD, EMAIL)).thenReturn(ACCEPTED);
        when(hasher.hash(PASSWORD)).thenReturn(VERIFIER);
    }

    private static void assertFailure(Runnable action, LocalRegistrationException.FailureReason reason) {
        LocalRegistrationException failure = catchThrowableOfType(LocalRegistrationException.class, action::run);
        assertThat(failure).isNotNull().hasNoCause();
        assertThat(failure.failureReason()).isEqualTo(reason);
        assertThat(failure.getMessage()).doesNotContain(PASSWORD, EMAIL, VERIFIER);
    }

    private static String sensitiveDetail() {
        return "synthetic failure " + EMAIL + " " + PASSWORD + " " + VERIFIER;
    }

    private static void assertSafeLog(CapturedOutput output, String stage, Class<?> failureType) {
        assertThat(output).contains("stage=" + stage, "exceptionType=" + failureType.getName())
                .doesNotContain(PASSWORD, EMAIL, VERIFIER, "synthetic failure");
    }
}
