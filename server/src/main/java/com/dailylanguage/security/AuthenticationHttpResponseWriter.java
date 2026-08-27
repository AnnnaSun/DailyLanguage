package com.dailylanguage.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

@Component
public final class AuthenticationHttpResponseWriter {

    private static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    private static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    private static final String AUTHENTICATION_UNAVAILABLE = "AUTHENTICATION_UNAVAILABLE";
    private static final String TOO_MANY_LOGIN_ATTEMPTS = "TOO_MANY_LOGIN_ATTEMPTS";

    private final JsonMapper jsonMapper;

    public AuthenticationHttpResponseWriter(JsonMapper jsonMapper) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
    }

    void writeInvalidCredentials(HttpServletResponse response) throws IOException {
        writeError(response, HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS);
    }

    void writeUnauthenticated(HttpServletResponse response) throws IOException {
        writeError(response, HttpStatus.UNAUTHORIZED, UNAUTHENTICATED);
    }

    void writeAuthenticationUnavailable(HttpServletResponse response) throws IOException {
        writeError(response, HttpStatus.SERVICE_UNAVAILABLE, AUTHENTICATION_UNAVAILABLE);
    }

    void writeTooManyLoginAttempts(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        writeError(response, HttpStatus.TOO_MANY_REQUESTS, TOO_MANY_LOGIN_ATTEMPTS);
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String code) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        jsonMapper.writeValue(response.getOutputStream(), new AuthenticationErrorResponse(code));
    }

    private record AuthenticationErrorResponse(String code) {
    }
}
