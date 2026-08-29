package com.dailylanguage.security.infrastructure;

import java.io.IOException;
import java.util.Objects;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 位于 Spring Session Filter 之前，把认证端点上的 Redis Session 故障收敛为稳定的 503。
 * Session storage 不可用时必须 fail closed，不能把请求当成无状态认证继续处理。
 */
@Component
@Order(SessionRepositoryFilter.DEFAULT_ORDER - 1)
public final class SessionStorageFailureFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionStorageFailureFilter.class);

    private final AuthenticationHttpResponseWriter authenticationHttpResponseWriter;

    public SessionStorageFailureFilter(AuthenticationHttpResponseWriter authenticationHttpResponseWriter) {
        this.authenticationHttpResponseWriter = Objects.requireNonNull(
                authenticationHttpResponseWriter,
                "authenticationHttpResponseWriter must not be null");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        }
        catch (RedisConnectionFailureException exception) {
            if (response.isCommitted()) {
                // 已提交的响应不能安全改写；继续抛出以避免伪装成完整、成功的认证响应。
                throw exception;
            }

            LOGGER.error(
                    "Authentication session storage failed stage=SESSION_STORAGE exceptionType={}",
                    exception.getClass().getName());
            response.resetBuffer();
            authenticationHttpResponseWriter.writeAuthenticationUnavailable(response);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        return !switch (requestPath) {
            case "/api/auth/login", "/api/auth/logout", "/api/auth/me" -> true;
            default -> false;
        };
    }
}
