package com.dailylanguage.security.infrastructure;

import java.io.IOException;
import java.util.Objects;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 只拦截 login POST，并在进入 AuthenticationProvider / Argon2 前执行 Redis Rate Limit。
 * Redis 不可用时 fail closed，避免绕过资源保护继续做 password verification。
 */
final class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoginRateLimitFilter.class);

    private final RedisAuthenticationAttemptRateLimiter authenticationAttemptRateLimiter;
    private final AuthenticationHttpResponseWriter authenticationHttpResponseWriter;

    LoginRateLimitFilter(
            RedisAuthenticationAttemptRateLimiter authenticationAttemptRateLimiter,
            AuthenticationHttpResponseWriter authenticationHttpResponseWriter) {
        this.authenticationAttemptRateLimiter = Objects.requireNonNull(
                authenticationAttemptRateLimiter,
                "authenticationAttemptRateLimiter must not be null");
        this.authenticationHttpResponseWriter = Objects.requireNonNull(
                authenticationHttpResponseWriter,
                "authenticationHttpResponseWriter must not be null");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        RedisAuthenticationAttemptRateLimiter.AttemptDecision decision;
        try {
            decision = authenticationAttemptRateLimiter.recordLoginAttempt(
                    request.getRemoteAddr(),
                    request.getParameter("email"));
        }
        catch (DataAccessException exception) {
            LOGGER.error(
                    "Login rate-limit storage failed stage=LOGIN_RATE_LIMIT exceptionType={}",
                    exception.getClass().getName());
            authenticationHttpResponseWriter.writeAuthenticationUnavailable(response);
            return;
        }
        if (!decision.allowed()) {
            // Filter 在此终止调用链，确保被限流请求不会触发后续 Argon2 工作。
            authenticationHttpResponseWriter.writeTooManyLoginAttempts(
                    response,
                    decision.retryAfterSeconds());
            return;
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        return !HttpMethod.POST.matches(request.getMethod()) || !"/api/auth/login".equals(requestPath);
    }
}
