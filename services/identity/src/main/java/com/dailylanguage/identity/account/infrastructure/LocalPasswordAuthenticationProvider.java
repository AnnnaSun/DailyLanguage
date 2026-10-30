package com.dailylanguage.identity.account.infrastructure;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.dailylanguage.identity.account.domain.AuthenticatedAccount;
import com.dailylanguage.identity.account.domain.LocalEmailNormalizer;
import com.dailylanguage.identity.credential.infrastructure.LocalPasswordHasher;
import com.dailylanguage.identity.credential.infrastructure.PasswordHashCapacityExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** 内部 LOCAL_EMAIL 凭据认证；不创建 Session，不查询或修改应用准入。 */
@Component
public final class LocalPasswordAuthenticationProvider implements AuthenticationProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalPasswordAuthenticationProvider.class);
    private static final String INVALID_CREDENTIALS = "Invalid credentials";
    private static final String AUTHENTICATION_UNAVAILABLE = "Local authentication unavailable";

    private final LocalAccountMapper accounts;
    private final LocalPasswordHasher passwordHasher;
    private final String unknownAccountPasswordVerifier;

    LocalPasswordAuthenticationProvider(LocalAccountMapper accounts, LocalPasswordHasher passwordHasher) {
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher must not be null");
        this.unknownAccountPasswordVerifier = createUnknownAccountPasswordVerifier();
    }

    @Override
    public Authentication authenticate(Authentication authenticationRequest) {
        if (!(authenticationRequest instanceof UsernamePasswordAuthenticationToken loginRequest)) {
            return null;
        }

        CharSequence submittedPassword = submittedPassword(loginRequest);
        try {
            Optional<LocalPasswordCredential> storedCredential = lookupCredential(loginRequest);

            String passwordVerifier = unknownAccountPasswordVerifier;
            if (storedCredential.isPresent()) {
                passwordVerifier = storedCredential.get().passwordVerifier();
            }
            boolean verifiedPassword = verifySubmittedPassword(submittedPassword, passwordVerifier);
            if (verifiedPassword && storedCredential.isPresent()) {
                AuthenticatedAccount authentication = new AuthenticatedAccount(storedCredential.get().userId());
                return UsernamePasswordAuthenticationToken.authenticated(authentication, null, List.of());
            }
            else {
                throw new BadCredentialsException(INVALID_CREDENTIALS);
            }
        }
        finally {
            // Provider 失败时请求仍可能被调用方保留，主动解除 token 对原始密码的引用。
            loginRequest.eraseCredentials();
        }
    }

    @Override
    public boolean supports(Class<?> authenticationType) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authenticationType);
    }

    private Optional<LocalPasswordCredential> lookupCredential(UsernamePasswordAuthenticationToken loginRequest) {
        if (!(loginRequest.getPrincipal() instanceof String submittedEmail)) {
            return Optional.empty();
        }

        String normalizedEmail;
        try {
            normalizedEmail = LocalEmailNormalizer.normalize(submittedEmail);
        }
        catch (IllegalArgumentException | NullPointerException exception) {
            return Optional.empty();
        }

        try {
            return accounts.findLocalPasswordCredential(normalizedEmail);
        }
        catch (RuntimeException exception) {
            // 查询故障不能与上面的邮箱输入拒绝共用 catch 范围。
            throw authenticationUnavailable("ACCOUNT_LOOKUP", exception);
        }
    }

    private boolean verifySubmittedPassword(CharSequence submittedPassword, String passwordVerifier) {
        try {
            return passwordHasher.matches(submittedPassword, passwordVerifier);
        }
        catch (PasswordHashCapacityExceededException exception) {
            throw new AuthenticationServiceException(AUTHENTICATION_UNAVAILABLE);
        }
        catch (RuntimeException exception) {
            throw authenticationUnavailable("PASSWORD_VERIFY", exception);
        }
    }

    private String createUnknownAccountPasswordVerifier() {
        try {
            // dummy 仅用于执行同一种受限计算；即使匹配也不能作为账户存在的证据。
            return Objects.requireNonNull(passwordHasher.hash("Identity unknown account dummy password"),
                    "dummy password verifier must not be null");
        }
        catch (RuntimeException exception) {
            // 不能在初始化失败时退回空值、无效 verifier 或跳过校验。
            throw authenticationUnavailable("DUMMY_INITIALIZATION", exception);
        }
    }

    private static CharSequence submittedPassword(UsernamePasswordAuthenticationToken loginRequest) {
        return loginRequest.getCredentials() instanceof CharSequence password ? password : "";
    }

    private static AuthenticationServiceException authenticationUnavailable(String stage, RuntimeException exception) {
        // 原始异常可能包含邮箱、密码、verifier 或 SQL 参数，日志与对外异常均不保留其正文或 cause。
        LOGGER.error("Local authentication failed stage={} exceptionType={}", stage, exception.getClass().getName());
        return new AuthenticationServiceException(AUTHENTICATION_UNAVAILABLE);
    }
}
