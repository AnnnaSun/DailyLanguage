package com.dailylanguage.authentication;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import com.dailylanguage.security.UserContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class LocalPasswordAuthenticationProviderTests {

    private static final String EMAIL = "owner@example.com";
    private static final String SUBMITTED_PASSWORD = "correct horse battery staple";
    private static final String STORED_PASSWORD_HASH = "{argon2id-v1}$stored-password-hash";
    private static final String UNKNOWN_ACCOUNT_PASSWORD_HASH = "{argon2id-v1}$unknown-account-hash";

    @Mock
    private LocalAuthenticationRepository authenticationRepository;

    @Mock
    private LocalPasswordHasher passwordHasher;

    private LocalPasswordAuthenticationProvider authenticationProvider;
    private ProviderManager authenticationManager;

    @BeforeEach
    void setUp() {
        when(passwordHasher.createUnknownAccountPasswordHash()).thenReturn(UNKNOWN_ACCOUNT_PASSWORD_HASH);
        authenticationProvider = new LocalPasswordAuthenticationProvider(
                authenticationRepository,
                passwordHasher);
        authenticationManager = new ProviderManager(authenticationProvider);
    }

    @Test
    void authenticatesNormalizedCredentialAsUserContextAndClearsBothTokens() {
        UUID userId = UUID.randomUUID();
        var credential = credential(userId, STORED_PASSWORD_HASH);
        when(authenticationRepository.findByEmail(" Owner@Example.COM ")).thenReturn(Optional.of(credential));
        when(passwordHasher.matches(SUBMITTED_PASSWORD, STORED_PASSWORD_HASH)).thenReturn(true);
        var request = request(" Owner@Example.COM ", SUBMITTED_PASSWORD);

        Authentication result = authenticationManager.authenticate(request);

        assertThat(result).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getPrincipal()).isEqualTo(new UserContext(userId));
        assertThat(result.getCredentials()).isNull();
        assertThat(result.getAuthorities()).isEmpty();
        assertThat(request.getCredentials()).isNull();
        verify(authenticationRepository).findByEmail(" Owner@Example.COM ");
        verify(passwordHasher).matches(SUBMITTED_PASSWORD, STORED_PASSWORD_HASH);
    }

    @Test
    void unknownAccountUsesFallbackPasswordHashAndReturnsUniformCredentialFailure() {
        when(authenticationRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(passwordHasher.matches(SUBMITTED_PASSWORD, UNKNOWN_ACCOUNT_PASSWORD_HASH)).thenReturn(false);
        var request = request(EMAIL, SUBMITTED_PASSWORD);

        BadCredentialsException exception = catchThrowableOfType(
                () -> authenticationManager.authenticate(request),
                BadCredentialsException.class);

        assertCredentialFailureIsSafe(exception, request);
        verify(passwordHasher).matches(SUBMITTED_PASSWORD, UNKNOWN_ACCOUNT_PASSWORD_HASH);
    }

    @Test
    void repositoryNormalizationRejectionUsesUnknownAccountPasswordHash() {
        when(authenticationRepository.findByEmail("not-an-email"))
                .thenThrow(new IllegalArgumentException("email must be a valid ASCII address"));
        when(passwordHasher.matches(SUBMITTED_PASSWORD, UNKNOWN_ACCOUNT_PASSWORD_HASH)).thenReturn(false);
        var request = request("not-an-email", SUBMITTED_PASSWORD);

        BadCredentialsException exception = catchThrowableOfType(
                () -> authenticationManager.authenticate(request),
                BadCredentialsException.class);

        assertCredentialFailureIsSafe(exception, request);
        verify(authenticationRepository).findByEmail("not-an-email");
        verify(passwordHasher).matches(SUBMITTED_PASSWORD, UNKNOWN_ACCOUNT_PASSWORD_HASH);
    }

    @Test
    void wrongOrRejectedStoredPasswordHashReturnsTheSameCredentialFailure() {
        var credential = credential(UUID.randomUUID(), STORED_PASSWORD_HASH);
        when(authenticationRepository.findByEmail(EMAIL)).thenReturn(Optional.of(credential));
        when(passwordHasher.matches(SUBMITTED_PASSWORD, STORED_PASSWORD_HASH)).thenReturn(false);
        var request = request(EMAIL, SUBMITTED_PASSWORD);

        BadCredentialsException exception = catchThrowableOfType(
                () -> authenticationManager.authenticate(request),
                BadCredentialsException.class);

        assertCredentialFailureIsSafe(exception, request);
        verify(passwordHasher).matches(SUBMITTED_PASSWORD, STORED_PASSWORD_HASH);
    }

    @Test
    void missingPasswordStillUsesTheStoredPasswordHashPath() {
        var credential = credential(UUID.randomUUID(), STORED_PASSWORD_HASH);
        when(authenticationRepository.findByEmail(EMAIL)).thenReturn(Optional.of(credential));
        when(passwordHasher.matches("", STORED_PASSWORD_HASH)).thenReturn(false);
        var request = request(EMAIL, null);

        BadCredentialsException exception = catchThrowableOfType(
                () -> authenticationManager.authenticate(request),
                BadCredentialsException.class);

        assertCredentialFailureIsSafe(exception, request);
        verify(passwordHasher).matches("", STORED_PASSWORD_HASH);
    }

    @Test
    void mapsLookupFailureWithoutLeakingSubmittedOrPersistenceDetails(CapturedOutput output) {
        String failureDetail = "database rejected " + EMAIL + " " + SUBMITTED_PASSWORD;
        when(authenticationRepository.findByEmail(EMAIL))
                .thenThrow(new DataAccessResourceFailureException(failureDetail));
        var request = request(EMAIL, SUBMITTED_PASSWORD);

        AuthenticationServiceException exception = catchThrowableOfType(
                () -> authenticationManager.authenticate(request),
                AuthenticationServiceException.class);

        assertUnavailableFailureIsSafe(exception, request);
        assertThat(output)
                .contains("stage=ACCOUNT_LOOKUP")
                .contains("exceptionType=org.springframework.dao.DataAccessResourceFailureException")
                .doesNotContain(EMAIL)
                .doesNotContain(SUBMITTED_PASSWORD)
                .doesNotContain(failureDetail);
        verify(passwordHasher, never()).matches(any(), anyString());
    }

    @Test
    void mapsUnexpectedNullPointerFromRepositoryToUnavailableWithoutLeakingDetails(CapturedOutput output) {
        String failureDetail = "repository bug exposed " + EMAIL + " " + SUBMITTED_PASSWORD;
        when(authenticationRepository.findByEmail(EMAIL))
                .thenThrow(new NullPointerException(failureDetail));
        var request = request(EMAIL, SUBMITTED_PASSWORD);

        AuthenticationServiceException exception = catchThrowableOfType(
                () -> authenticationManager.authenticate(request),
                AuthenticationServiceException.class);

        assertUnavailableFailureIsSafe(exception, request);
        assertThat(output)
                .contains("stage=ACCOUNT_LOOKUP")
                .contains("exceptionType=java.lang.NullPointerException")
                .doesNotContain(EMAIL)
                .doesNotContain(SUBMITTED_PASSWORD)
                .doesNotContain(failureDetail);
        verify(passwordHasher, never()).matches(any(), anyString());
    }

    @Test
    void mapsHasherFailureWithoutLeakingCredentialMaterial(CapturedOutput output) {
        var credential = credential(UUID.randomUUID(), STORED_PASSWORD_HASH);
        String failureDetail = "hasher rejected " + SUBMITTED_PASSWORD + " " + STORED_PASSWORD_HASH;
        when(authenticationRepository.findByEmail(EMAIL)).thenReturn(Optional.of(credential));
        when(passwordHasher.matches(SUBMITTED_PASSWORD, STORED_PASSWORD_HASH))
                .thenThrow(new IllegalStateException(failureDetail));
        var request = request(EMAIL, SUBMITTED_PASSWORD);

        AuthenticationServiceException exception = catchThrowableOfType(
                () -> authenticationManager.authenticate(request),
                AuthenticationServiceException.class);

        assertUnavailableFailureIsSafe(exception, request);
        assertThat(output)
                .contains("stage=PASSWORD_VERIFY")
                .contains("exceptionType=java.lang.IllegalStateException")
                .doesNotContain(EMAIL)
                .doesNotContain(SUBMITTED_PASSWORD)
                .doesNotContain(STORED_PASSWORD_HASH)
                .doesNotContain(failureDetail);
    }

    @Test
    void supportsOnlyUsernamePasswordAuthenticationTokens() {
        assertThat(authenticationProvider.supports(UsernamePasswordAuthenticationToken.class)).isTrue();
        assertThat(authenticationProvider.supports(Authentication.class)).isFalse();
    }

    private static UsernamePasswordAuthenticationToken request(String email, String password) {
        return UsernamePasswordAuthenticationToken.unauthenticated(email, password);
    }

    private static StoredLocalPasswordCredential credential(UUID userId, String storedPasswordHash) {
        return new StoredLocalPasswordCredential(
                UUID.randomUUID(),
                userId,
                EMAIL,
                storedPasswordHash);
    }

    private static void assertCredentialFailureIsSafe(
            BadCredentialsException exception,
            UsernamePasswordAuthenticationToken request) {
        assertThat(exception).hasMessage("Invalid credentials").hasNoCause();
        assertThat(exception.getAuthenticationRequest()).isSameAs(request);
        assertThat(exception.getAuthenticationRequest().getCredentials()).isNull();
    }

    private static void assertUnavailableFailureIsSafe(
            AuthenticationServiceException exception,
            UsernamePasswordAuthenticationToken request) {
        assertThat(exception).hasMessage("Local authentication unavailable").hasNoCause();
        assertThat(exception.getAuthenticationRequest()).isSameAs(request);
        assertThat(exception.getAuthenticationRequest().getCredentials()).isNull();
    }
}
