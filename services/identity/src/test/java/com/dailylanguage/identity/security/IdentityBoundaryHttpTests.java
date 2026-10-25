package com.dailylanguage.identity.security;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

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
import org.springframework.security.core.userdetails.UserDetailsService;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用真实 HTTP 检查安全骨架；不把无数据库的健康检查当作完整服务启动证据。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = IdentityBoundaryHttpTests.TestApplication.class,
        properties = {"spring.data.redis.host=127.0.0.1", "management.health.redis.enabled=false"})
class IdentityBoundaryHttpTests {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @Import({IdentitySecurityConfiguration.class, SessionConfiguration.class})
    static class TestApplication {
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
