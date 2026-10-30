package com.dailylanguage.identity.security.infrastructure;

import java.net.URI;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import com.dailylanguage.identity.security.api.CurrentAccountController;
import com.dailylanguage.identity.security.config.IdentitySecurityConfiguration;
import com.dailylanguage.identity.security.config.SessionConfiguration;
import com.dailylanguage.identity.security.infrastructure.AuthenticationHttpResponseWriter;
import com.dailylanguage.identity.security.infrastructure.SessionStorageFailureFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用真实 HTTP 检查安全骨架；不把无数据库的健康检查当作完整服务启动证据。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = IdentityBoundaryHttpTests.TestApplication.class,
        properties = {"spring.data.redis.host=127.0.0.1", "management.health.redis.enabled=false",
                "IDENTITY_SESSION_COOKIE_SECURE=false"})
class IdentityBoundaryHttpTests {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @Import({IdentitySecurityConfiguration.class, SessionConfiguration.class, CurrentAccountController.class,
            AuthenticationHttpResponseWriter.class, SessionStorageFailureFilter.class})
    static class TestApplication {

        // 无数据库的 HTTP / Session 配置只提供失败样本，不模拟任何认证成功身份。
        @Bean
        AuthenticationProvider testAuthenticationProvider() {
            return new AuthenticationProvider() {
                public Authentication authenticate(Authentication request) {
                    if (request.getName().equals("unavailable@example.com")) {
                        throw new AuthenticationServiceException("synthetic authentication detail");
                    }
                    throw new BadCredentialsException("synthetic authentication detail");
                }
                public boolean supports(Class<?> type) {
                    return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type);
                }
            };
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void exposesOnlyHealthWithoutDetailsOrSession() throws Exception {
        HttpResponse<String> response = send("GET", "/actuator/health");
        assertThat(response.statusCode()).isEqualTo(200);
        Map<?, ?> health = JsonMapper.builder().build().readValue(response.body(), Map.class);
        assertThat(health.get("status")).isEqualTo("UP");
        assertThat(health.containsKey("components")).isFalse();
        assertThat(health.containsKey("details")).isFalse();
        assertNoSession(response);
        assertThat(applicationContext.getBeansOfType(UserDetailsService.class)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/me", "/api/auth/registration", "/login", "/actuator/env", "/error"})
    void forgedUserIdDoesNotCreateIdentityOrExposeOtherEndpoints(String path) throws Exception {
        HttpResponse<String> response = send("GET", path);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertNoSession(response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/login", "/api/auth/registration", "/api/auth/logout"})
    void writesRemainForbiddenWithoutCreatingSession(String path) throws Exception {
        HttpResponse<String> response = send("POST", path);
        // 真实 Tomcat 的 ERROR 分发不得把 CSRF 403 覆盖成其他结果。
        assertThat(response.statusCode()).isEqualTo(403);
        assertNoSession(response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong@example.com", "unavailable@example.com"})
    void mapsAuthenticationFailureAfterValidCsrfWithoutExposingInternalDetail(String email) throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build()) {
            client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            String csrf = cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("XSRF-TOKEN")).findFirst().orElseThrow().getValue();
            var login = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("email=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                            + "&password=synthetic-password"));
            if (email.startsWith("wrong")) {
                assertThat(client.send(login.setHeader("X-XSRF-TOKEN", "mismatched").build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
            }
            var response = client.send(login.setHeader("X-XSRF-TOKEN", csrf).build(), HttpResponse.BodyHandlers.ofString());
            boolean unavailable = email.startsWith("unavailable");
            assertThat(response.statusCode()).isEqualTo(unavailable ? 503 : 401);
            assertThat(JsonMapper.builder().build().readValue(response.body(), Map.class))
                    .isEqualTo(Map.of("code", unavailable ? "AUTHENTICATION_UNAVAILABLE" : "INVALID_CREDENTIALS"));
            assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
            assertNoSession(response);
        }
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("X-User-Id", "00000000-0000-0000-0000-000000000001")
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static void assertNoSession(HttpResponse<?> response) {
        assertThat(response.headers().allValues("Set-Cookie"))
                .noneMatch(cookie -> cookie.startsWith("SESSION=") || cookie.startsWith("JSESSIONID="));
    }
}
