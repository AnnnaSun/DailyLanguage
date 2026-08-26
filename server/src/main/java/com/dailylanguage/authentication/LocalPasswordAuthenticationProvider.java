package com.dailylanguage.authentication;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

import com.dailylanguage.security.UserContext;

@Component
public final class LocalPasswordAuthenticationProvider implements AuthenticationProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalPasswordAuthenticationProvider.class);
    private static final String INVALID_CREDENTIALS = "Invalid credentials";
    private static final String AUTHENTICATION_UNAVAILABLE = "Local authentication unavailable";

    private final LocalAuthenticationRepository localAuthenticationRepository;
    private final LocalPasswordHasher passwordHasher;
    private final String unknownAccountPasswordHash;

    public LocalPasswordAuthenticationProvider(
            LocalAuthenticationRepository localAuthenticationRepository,
            LocalPasswordHasher passwordHasher) {
        this.localAuthenticationRepository = Objects.requireNonNull(
                localAuthenticationRepository,
                "localAuthenticationRepository must not be null");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher must not be null");
        this.unknownAccountPasswordHash = Objects.requireNonNull(
                passwordHasher.createUnknownAccountPasswordHash(),
                "unknownAccountPasswordHash must not be null");
    }

    @Override
    public Authentication authenticate(Authentication authenticationRequest) throws AuthenticationException {
        if (!(authenticationRequest instanceof UsernamePasswordAuthenticationToken loginRequest)) {
            return null;
        }

        CharSequence submittedPassword = submittedPassword(loginRequest);
        try {
            Optional<StoredLocalPasswordCredential> storedPasswordCredential =
                    lookupStoredCredential(loginRequest);
            String passwordHashToCheck = storedPasswordCredential
                    .map(StoredLocalPasswordCredential::storedPasswordHash)
                    .orElse(this.unknownAccountPasswordHash);

            boolean submittedPasswordMatches = verifySubmittedPassword(
                    submittedPassword,
                    passwordHashToCheck);
            if (storedPasswordCredential.isEmpty() || !submittedPasswordMatches) {
                throw new BadCredentialsException(INVALID_CREDENTIALS);
            }

            StoredLocalPasswordCredential authenticatedCredential = storedPasswordCredential.orElseThrow();
            return UsernamePasswordAuthenticationToken.authenticated(
                    new UserContext(authenticatedCredential.userId()),
                    null,
                    List.of());
        }
        finally {
            // ProviderManager retains failed authentication requests on exceptions in Spring Security 7.1.
            loginRequest.eraseCredentials();
        }
    }

    @Override
    public boolean supports(Class<?> authenticationType) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authenticationType);
    }

    private Optional<StoredLocalPasswordCredential> lookupStoredCredential(
            UsernamePasswordAuthenticationToken loginRequest) {
        Object principal = loginRequest.getPrincipal();
        if (!(principal instanceof String submittedEmail)) {
            return Optional.empty();
        }

        try {
            return localAuthenticationRepository.findByEmail(submittedEmail);
        }
        catch (IllegalArgumentException exception) {
            // A malformed email is an expected credential rejection, not an infrastructure failure.
            return Optional.empty();
        }
        catch (RuntimeException exception) {
            throw authenticationUnavailable("ACCOUNT_LOOKUP", exception);
        }
    }

    private boolean verifySubmittedPassword(CharSequence submittedPassword, String passwordHashToCheck) {
        try {
            return passwordHasher.matches(submittedPassword, passwordHashToCheck);
        }
        catch (PasswordHashCapacityExceededException exception) {
            throw new AuthenticationServiceException(AUTHENTICATION_UNAVAILABLE);
        }
        catch (RuntimeException exception) {
            throw authenticationUnavailable("PASSWORD_VERIFY", exception);
        }
    }

    private static CharSequence submittedPassword(UsernamePasswordAuthenticationToken loginRequest) {
        Object credentials = loginRequest.getCredentials();
        return credentials instanceof CharSequence submittedPassword ? submittedPassword : "";
    }

    private static AuthenticationServiceException authenticationUnavailable(
            String failureStage,
            RuntimeException exception) {
        // Throwable messages and chains may contain submitted identity or credential material.
        LOGGER.error(
                "Local authentication failed stage={} exceptionType={}",
                failureStage,
                exception.getClass().getName());
        return new AuthenticationServiceException(AUTHENTICATION_UNAVAILABLE);
    }
}
