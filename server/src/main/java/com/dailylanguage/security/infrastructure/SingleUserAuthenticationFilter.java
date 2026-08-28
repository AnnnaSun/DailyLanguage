package com.dailylanguage.security.infrastructure;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.dailylanguage.security.domain.UserContext;

final class SingleUserAuthenticationFilter extends OncePerRequestFilter {

    private final PersistentSingleUser persistentSingleUser;

    SingleUserAuthenticationFilter(PersistentSingleUser persistentSingleUser) {
        this.persistentSingleUser = Objects.requireNonNull(
                persistentSingleUser,
                "persistentSingleUser must not be null");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Optional<UserContext> singleUserContext = persistentSingleUser.userContext();
        if (singleUserContext.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isLoginRequest(request)) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                singleUserContext.orElseThrow(),
                null,
                List.of()));
        SecurityContextHolder.setContext(securityContext);
        filterChain.doFilter(request, response);
    }

    private static boolean isLoginRequest(HttpServletRequest request) {
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        return HttpMethod.POST.matches(request.getMethod()) && "/api/auth/login".equals(requestPath);
    }
}
