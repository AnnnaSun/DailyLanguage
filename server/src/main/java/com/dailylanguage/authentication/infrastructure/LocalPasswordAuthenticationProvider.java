package com.dailylanguage.authentication.infrastructure;

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

import com.dailylanguage.security.domain.UserContext;

/**
 * LOCAL_EMAIL password login 的认证边界。成功时只把 UserContext 放入 principal；
 * credential rejection 使用无差别响应；infrastructure failure 单独收敛为 unavailable，
 * 并确保 unknown account 仍执行一次受控 Argon2 verification。
 */
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
            // unknown account 使用预生成的合法 hash，缩小“账号不存在”和“密码错误”的 timing 差异。
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
            // Spring Security 7.1 的 ProviderManager 在异常时会保留 request，因此必须在此主动清除 credential。
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
            // 非法 email 属于预期的 credential rejection，不应伪装成 infrastructure failure。
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
        // Throwable message 与 chain 可能包含 identity / credential material，日志只保留 stage 和类型。
        LOGGER.error(
                "Local authentication failed stage={} exceptionType={}",
                failureStage,
                exception.getClass().getName());
        return new AuthenticationServiceException(AUTHENTICATION_UNAVAILABLE);
    }
}
