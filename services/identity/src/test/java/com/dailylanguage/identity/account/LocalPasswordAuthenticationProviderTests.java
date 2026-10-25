package com.dailylanguage.identity.account;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import com.dailylanguage.identity.credential.LocalPasswordHasher;
import com.dailylanguage.identity.credential.PasswordHashCapacityExceededException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.*;

/** 验证真实认证合同；Human Core 占位必须表现为 RED，不把未实现当成预期成功。 */
@ExtendWith(OutputCaptureExtension.class)
class LocalPasswordAuthenticationProviderTests {

    private static final String EMAIL = "owner@example.com";
    private static final String PASSWORD = "  Original password 42  ";
    private static final String VERIFIER = "synthetic stored verifier";
    private static final String DUMMY = "synthetic dummy verifier";
    private static final UUID USER_ID = UUID.randomUUID();

    private final LocalAccountMapper accounts = mock(LocalAccountMapper.class);
    private final LocalPasswordHasher passwords = mock(LocalPasswordHasher.class);

    @Test
    void authenticatesNormalizedEmailWithOriginalPasswordAndOnlyUserId() {
        var provider = provider();
        when(accounts.findLocalPasswordCredential(EMAIL)).thenReturn(credential());
        when(passwords.matches(PASSWORD, VERIFIER)).thenReturn(true);
        var request = request(" Owner@Example.COM ");

        var result = provider.authenticate(request);

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getPrincipal()).isEqualTo(new AuthenticatedAccount(USER_ID));
        assertThat(result.getCredentials()).isNull();
        assertThat(result.getAuthorities()).isEmpty();
        assertThat(request.getCredentials()).isNull();
        verify(passwords).matches(PASSWORD, VERIFIER);
        verify(accounts).findLocalPasswordCredential(EMAIL);
        verifyNoMoreInteractions(accounts);
    }

    @ParameterizedTest
    @MethodSource("credentialRejections")
    void uniformlyRejectsWrongPasswordOrMissingAccountEvenWhenDummyMatches(
            String email, boolean accountExists, boolean matches) {
        var provider = provider();
        when(accounts.findLocalPasswordCredential(EMAIL))
                .thenReturn(accountExists ? credential() : Optional.empty());
        String verifier = accountExists ? VERIFIER : DUMMY;
        when(passwords.matches(PASSWORD, verifier)).thenReturn(matches);
        var request = request(email);

        var failure = catchThrowableOfType(BadCredentialsException.class, () -> provider.authenticate(request));

        assertThat(failure).isNotNull().hasMessage("Invalid credentials").hasNoCause();
        assertThat(request.getCredentials()).isNull();
        verify(passwords).matches(PASSWORD, verifier);
        if (email.equals("not-an-email")) {
            verifyNoInteractions(accounts);
        }
    }

    static Stream<Arguments> credentialRejections() {
        return Stream.of(
                Arguments.of(EMAIL, true, false),
                Arguments.of(EMAIL, false, true),
                Arguments.of("not-an-email", false, true));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void treatsMapperFailureIncludingIllegalArgumentAsUnavailable(boolean illegalArgument, CapturedOutput output) {
        var provider = provider();
        RuntimeException databaseFailure = illegalArgument
                ? new IllegalArgumentException(sensitiveDetail())
                : new DataAccessResourceFailureException(sensitiveDetail());
        when(accounts.findLocalPasswordCredential(EMAIL)).thenThrow(databaseFailure);
        var request = request(EMAIL);

        assertUnavailable(() -> provider.authenticate(request));

        assertThat(request.getCredentials()).isNull();
        verify(passwords, never()).matches(any(), any());
        assertSafeLog(output, "ACCOUNT_LOOKUP", databaseFailure.getClass());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void treatsHashFailureAndCapacityExhaustionAsUnavailable(boolean capacity, CapturedOutput output) {
        var provider = provider();
        when(accounts.findLocalPasswordCredential(EMAIL)).thenReturn(credential());
        if (capacity) {
            when(passwords.matches(PASSWORD, VERIFIER)).thenThrow(PasswordHashCapacityExceededException.class);
        }
        else {
            when(passwords.matches(PASSWORD, VERIFIER)).thenThrow(new IllegalStateException(sensitiveDetail()));
        }
        var request = request(EMAIL);

        assertUnavailable(() -> provider.authenticate(request));

        assertThat(request.getCredentials()).isNull();
        if (capacity) {
            assertThat(output).doesNotContain("Local authentication failed");
        }
        else {
            assertSafeLog(output, "PASSWORD_VERIFY", IllegalStateException.class);
        }
    }

    @Test
    void refusesConstructionWhenDummyHashCannotBeCreated(CapturedOutput output) {
        when(passwords.hash(any())).thenThrow(new IllegalStateException(sensitiveDetail()));

        assertUnavailable(() -> new LocalPasswordAuthenticationProvider(accounts, passwords));

        assertSafeLog(output, "DUMMY_INITIALIZATION", IllegalStateException.class);
    }

    @Test
    void redactsStoredVerifierFromCredentialDescription() {
        assertThat(credential().orElseThrow().toString()).contains("[REDACTED]").doesNotContain(VERIFIER);
    }

    @Test
    void leavesUnsupportedAuthenticationToOtherProviders() {
        var provider = provider();
        assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isTrue();
        assertThat(provider.supports(TestingAuthenticationToken.class)).isFalse();
        assertThat(provider.authenticate(new TestingAuthenticationToken("principal", "credential"))).isNull();
        verifyNoInteractions(accounts);
    }

    private LocalPasswordAuthenticationProvider provider() {
        when(passwords.hash(any())).thenReturn(DUMMY);
        return new LocalPasswordAuthenticationProvider(accounts, passwords);
    }

    private static Optional<LocalPasswordCredential> credential() {
        return Optional.of(new LocalPasswordCredential(USER_ID, VERIFIER));
    }

    private static UsernamePasswordAuthenticationToken request(String email) {
        return UsernamePasswordAuthenticationToken.unauthenticated(email, PASSWORD);
    }

    private static void assertUnavailable(Runnable action) {
        var failure = catchThrowableOfType(AuthenticationServiceException.class, action::run);
        assertThat(failure).isNotNull().hasMessage("Local authentication unavailable").hasNoCause();
    }

    private static String sensitiveDetail() {
        return "synthetic failure " + EMAIL + " " + PASSWORD + " " + VERIFIER;
    }

    private static void assertSafeLog(CapturedOutput output, String stage, Class<?> failureType) {
        assertThat(output).contains("stage=" + stage, "exceptionType=" + failureType.getName())
                .doesNotContain(EMAIL, PASSWORD, VERIFIER, DUMMY, "synthetic failure");
    }
}
