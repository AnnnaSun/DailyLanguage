package com.dailylanguage.identity.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Security handler 与 Session 故障边界共用的固定错误响应，不接受原始异常作为输出。 */
@Component
public final class AuthenticationHttpResponseWriter {

    private final JsonMapper json;

    public AuthenticationHttpResponseWriter(JsonMapper json) {
        this.json = json;
    }

    public void writeInvalidCredentials(HttpServletResponse response) throws IOException {
        writeError(response, 401, "INVALID_CREDENTIALS");
    }

    public void writeUnauthenticated(HttpServletResponse response) throws IOException {
        writeError(response, 401, "UNAUTHENTICATED");
    }

    public void writeAuthenticationUnavailable(HttpServletResponse response) throws IOException {
        writeError(response, 503, "AUTHENTICATION_UNAVAILABLE");
    }

    private void writeError(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // Session 恢复可能在 Security header filter 前失败，此处仍提供基本安全响应头。
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        json.writeValue(response.getOutputStream(), new AuthenticationErrorResponse(code));
    }

    private record AuthenticationErrorResponse(String code) {
    }
}
