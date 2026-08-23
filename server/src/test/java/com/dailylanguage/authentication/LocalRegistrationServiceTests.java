package com.dailylanguage.authentication;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;

import static com.dailylanguage.authentication.LocalPasswordPolicy.ValidationResult.ACCEPTED;
import static com.dailylanguage.authentication.LocalPasswordPolicy.ValidationResult.COMMON_OR_COMPROMISED;
import static com.dailylanguage.authentication.LocalPasswordPolicy.ValidationResult.INVALID_CHARACTER;
import static com.dailylanguage.authentication.LocalPasswordPolicy.ValidationResult.INVALID_LENGTH;
import static com.dailylanguage.authentication.LocalRegistrationException.Reason.COMMON_OR_COMPROMISED_PASSWORD;
import static com.dailylanguage.authentication.LocalRegistrationException.Reason.IDENTITY_UNAVAILABLE;
import static com.dailylanguage.authentication.LocalRegistrationException.Reason.INVALID_EMAIL;
import static com.dailylanguage.authentication.LocalRegistrationException.Reason.INVALID_PASSWORD_CHARACTER;
import static com.dailylanguage.authentication.LocalRegistrationException.Reason.INVALID_PASSWORD_LENGTH;
import static com.dailylanguage.authentication.LocalRegistrationException.Reason.REGISTRATION_FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class LocalRegistrationServiceTests {

    private static final String RAW_PASSWORD = "safe-pass-12";
    private static final String NORMALIZED_EMAIL = "owner@example.com";
    private static final String ENCODED_VERIFIER = "{argon2id-v1}$encoded-verifier";

    @Mock
    private LocalPasswordPolicy passwordPolicy;

    @Mock
    private LocalPasswordHasher passwordHasher;

    @Mock
    private LocalRegistrationPersistence registrationPersistence;

    private LocalRegistrationService registrationService;

    @BeforeEach
    void setUp() {
        registrationService = new LocalRegistrationService(
                passwordPolicy,
                passwordHasher,
                registrationPersistence);
    }

    @Test
    void normalizesValidatesHashesAndPersistsInOrder() {
        UUID userId = UUID.randomUUID();
        when(passwordPolicy.validate(RAW_PASSWORD, NORMALIZED_EMAIL)).thenReturn(ACCEPTED);
        when(passwordHasher.hash(RAW_PASSWORD)).thenReturn(ENCODED_VERIFIER);
        when(registrationPersistence.create(NORMALIZED_EMAIL, ENCODED_VERIFIER)).thenReturn(userId);

        assertThat(registrationService.register(" Owner@Example.COM ", RAW_PASSWORD)).isEqualTo(userId);

        InOrder order = inOrder(passwordPolicy, passwordHasher, registrationPersistence);
        order.verify(passwordPolicy).validate(RAW_PASSWORD, NORMALIZED_EMAIL);
        order.verify(passwordHasher).hash(RAW_PASSWORD);
        order.verify(registrationPersistence).create(NORMALIZED_EMAIL, ENCODED_VERIFIER);
    }

    @Test
    void rejectsInvalidEmailBeforePasswordWork() {
        LocalRegistrationException exception = catchRegistrationFailure(
                () -> registrationService.register("not-an-email", RAW_PASSWORD));

        assertThat(exception.reason()).isEqualTo(INVALID_EMAIL);
        assertThat(exception).hasNoCause();
        verifyNoInteractions(passwordPolicy, passwordHasher, registrationPersistence);
    }

    @Test
    void mapsPasswordPolicyRejectionsWithoutHashingOrPersistence() {
        assertPolicyRejection(INVALID_LENGTH, INVALID_PASSWORD_LENGTH);
        assertPolicyRejection(INVALID_CHARACTER, INVALID_PASSWORD_CHARACTER);
        assertPolicyRejection(COMMON_OR_COMPROMISED, COMMON_OR_COMPROMISED_PASSWORD);
    }

    @Test
    void mapsDuplicateIdentityWithoutLoggingDatabaseDetails(CapturedOutput output) {
        prepareAcceptedPassword();
        when(registrationPersistence.create(NORMALIZED_EMAIL, ENCODED_VERIFIER))
                .thenThrow(new DuplicateKeyException("duplicate owner@example.com"));

        LocalRegistrationException exception = catchRegistrationFailure(
                () -> registrationService.register(NORMALIZED_EMAIL, RAW_PASSWORD));

        assertThat(exception.reason()).isEqualTo(IDENTITY_UNAVAILABLE);
        assertThat(exception).hasNoCause();
        assertThat(output).doesNotContain(NORMALIZED_EMAIL);
    }

    @Test
    void logsOnlySafeMetadataForUnexpectedPersistenceFailure(CapturedOutput output) {
        prepareAcceptedPassword();
        when(registrationPersistence.create(NORMALIZED_EMAIL, ENCODED_VERIFIER))
                .thenThrow(new DataAccessResourceFailureException(
                        "database rejected " + NORMALIZED_EMAIL + " " + RAW_PASSWORD + " " + ENCODED_VERIFIER));

        LocalRegistrationException exception = catchRegistrationFailure(
                () -> registrationService.register(NORMALIZED_EMAIL, RAW_PASSWORD));

        assertThat(exception.reason()).isEqualTo(REGISTRATION_FAILED);
        assertThat(exception).hasNoCause();
        assertThat(output)
                .contains("stage=PERSISTENCE")
                .contains("exceptionType=org.springframework.dao.DataAccessResourceFailureException")
                .doesNotContain(NORMALIZED_EMAIL)
                .doesNotContain(RAW_PASSWORD)
                .doesNotContain(ENCODED_VERIFIER);
    }

    @Test
    void stopsBeforePersistenceWhenPasswordHashingFails(CapturedOutput output) {
        when(passwordPolicy.validate(RAW_PASSWORD, NORMALIZED_EMAIL)).thenReturn(ACCEPTED);
        when(passwordHasher.hash(RAW_PASSWORD))
                .thenThrow(new IllegalStateException("failed to hash " + RAW_PASSWORD));

        LocalRegistrationException exception = catchRegistrationFailure(
                () -> registrationService.register(NORMALIZED_EMAIL, RAW_PASSWORD));

        assertThat(exception.reason()).isEqualTo(REGISTRATION_FAILED);
        assertThat(exception).hasNoCause();
        verifyNoInteractions(registrationPersistence);
        assertThat(output)
                .contains("stage=PASSWORD_HASH")
                .contains("exceptionType=java.lang.IllegalStateException")
                .doesNotContain(RAW_PASSWORD);
    }

    private void assertPolicyRejection(
            LocalPasswordPolicy.ValidationResult validationResult,
            LocalRegistrationException.Reason expectedReason) {
        when(passwordPolicy.validate(RAW_PASSWORD, NORMALIZED_EMAIL)).thenReturn(validationResult);

        LocalRegistrationException exception = catchRegistrationFailure(
                () -> registrationService.register(NORMALIZED_EMAIL, RAW_PASSWORD));

        assertThat(exception.reason()).isEqualTo(expectedReason);
        assertThat(exception).hasNoCause();
        verifyNoInteractions(passwordHasher, registrationPersistence);
    }

    private void prepareAcceptedPassword() {
        when(passwordPolicy.validate(RAW_PASSWORD, NORMALIZED_EMAIL)).thenReturn(ACCEPTED);
        when(passwordHasher.hash(RAW_PASSWORD)).thenReturn(ENCODED_VERIFIER);
    }

    private static LocalRegistrationException catchRegistrationFailure(Runnable registration) {
        return catchThrowableOfType(LocalRegistrationException.class, registration::run);
    }
}
