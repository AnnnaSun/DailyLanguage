package com.dailylanguage.identity.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 包围 Spring Session，仅在认证端点转换已知的会话存储故障，不接管 Session 生命周期。 */
@Component
@Order(SessionRepositoryFilter.DEFAULT_ORDER - 1)
public final class SessionStorageFailureFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionStorageFailureFilter.class);
    private final AuthenticationHttpResponseWriter responses;

    public SessionStorageFailureFilter(AuthenticationHttpResponseWriter responses) {
        this.responses = responses;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        }
        catch (RedisConnectionFailureException | QueryTimeoutException | SerializationException exception) {
            if (response.isCommitted()) {
                // 已提交响应不能伪装成可安全改写的完整错误响应。
                throw exception;
            }
            LOGGER.error("Authentication session storage failed stage=SESSION_STORAGE exceptionType={}",
                    exception.getClass().getName());
            response.resetBuffer();
            responses.writeAuthenticationUnavailable(response);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !switch (path) {
            case "/api/auth/login", "/api/auth/logout", "/api/auth/me" -> true;
            default -> false;
        };
    }
}
