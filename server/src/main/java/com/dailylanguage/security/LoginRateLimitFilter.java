package com.dailylanguage.security;

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

final class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoginRateLimitFilter.class);

    private final RedisLoginAttemptRateLimiter loginAttemptRateLimiter;
    private final AuthenticationHttpResponseWriter authenticationHttpResponseWriter;

    LoginRateLimitFilter(
            RedisLoginAttemptRateLimiter loginAttemptRateLimiter,
            AuthenticationHttpResponseWriter authenticationHttpResponseWriter) {
        this.loginAttemptRateLimiter = Objects.requireNonNull(
                loginAttemptRateLimiter,
                "loginAttemptRateLimiter must not be null");
        this.authenticationHttpResponseWriter = Objects.requireNonNull(
                authenticationHttpResponseWriter,
                "authenticationHttpResponseWriter must not be null");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        RedisLoginAttemptRateLimiter.LoginAttemptDecision decision;
        try {
            decision = loginAttemptRateLimiter.recordLoginAttempt(
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
//限流报错，直接 return 不 filter
        if (!decision.allowed()) {
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
