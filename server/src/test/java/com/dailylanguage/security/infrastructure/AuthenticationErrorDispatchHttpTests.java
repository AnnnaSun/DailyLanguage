package com.dailylanguage.security.infrastructure;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.session.autoconfigure.SessionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.dailylanguage.authentication.api.LocalRegistrationController;
import com.dailylanguage.authentication.application.LocalRegistrationService;
import com.dailylanguage.authentication.application.RegistrationCapability;
import com.dailylanguage.authentication.infrastructure.LocalPasswordAuthenticationProvider;
import com.dailylanguage.security.api.CurrentUserController;
import com.dailylanguage.security.config.SecurityConfiguration;
import com.dailylanguage.security.domain.UserContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 真实 Tomcat 才会在 sendError 后执行 ERROR 分发；只用 MockMvc 的 REQUEST 断言会漏掉状态覆盖。
 * 沿用 production Security / Controller，替换外部依赖并使用容器内存 Session，不验证 PostgreSQL / Redis。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = AuthenticationErrorDispatchHttpTests.TestApplication.class,
        properties = {
                "server.address=127.0.0.1",
                "server.servlet.session.cookie.secure=false"
        })
class AuthenticationErrorDispatchHttpTests {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000123");

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class, DataRedisAutoConfiguration.class, SessionAutoConfiguration.class
    })
    @Import({SecurityConfiguration.class, AuthenticationHttpResponseWriter.class,
            CurrentUserController.class, LocalRegistrationController.class})
    static class TestApplication {
    }

    @LocalServerPort
    private int port;

    @MockitoBean
    private LocalPasswordAuthenticationProvider authenticationProvider;

    @MockitoBean
    private RedisAuthenticationAttemptRateLimiter authenticationAttemptRateLimiter;

    @MockitoBean
    private PersistentSingleUser persistentSingleUser;

    @MockitoBean
    private RegistrationCapability registrationCapability;

    @MockitoBean
    private LocalRegistrationService registrationService;

    @BeforeEach
    void useRegisteredUserMode() {
        when(persistentSingleUser.userContext()).thenReturn(Optional.empty());
        when(registrationCapability.state()).thenReturn(RegistrationCapability.State.PUBLIC);
        when(authenticationProvider.supports(UsernamePasswordAuthenticationToken.class)).thenReturn(true);
        when(authenticationAttemptRateLimiter.recordLoginAttempt(any(), any()))
                .thenReturn(RedisAuthenticationAttemptRateLimiter.AttemptDecision.allow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/login", "/api/auth/logout"})
    void anonymousMissingCsrfIsForbidden(String path) throws Exception {
        try (HttpClient client = newClient()) {
            assertForbidden(send(client, "POST", path, null));
        }
        verifyLoginWasNotAttempted();
    }

    @ParameterizedTest
    @CsvSource({
            "false,/api/auth/login,false", "false,/api/auth/login,true",
            "false,/api/auth/logout,false", "false,/api/auth/logout,true",
            "true,/api/auth/login,false", "true,/api/auth/login,true",
            "true,/api/auth/logout,false", "true,/api/auth/logout,true"
    })
    void csrfRejectionKeepsForbiddenAcrossErrorDispatch(
            boolean singleUser, String path, boolean wrongHeader) throws Exception {
        if (singleUser) {
            useSingleUserMode();
        }
        try (HttpClient client = newClient()) {
            assertThat(send(client, "GET", "/api/auth/registration", null).statusCode()).isEqualTo(200);
            assertThat(csrfToken(client)).isNotBlank();
            assertForbidden(send(client, "POST", path, wrongHeader ? "invalid-token" : null));
        }
        verifyLoginWasNotAttempted();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/me", "/error"})
    void ordinaryRequestsStillRequireAuthentication(String path) throws Exception {
        try (HttpClient client = newClient()) {
            HttpResponse<String> response = send(client, "GET", path, null);
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).contains("UNAUTHENTICATED");
        }
    }

    @Test
    void validLoginAndLogoutStillWorkWithCookieAndHeader() throws Exception {
        when(authenticationProvider.authenticate(any())).thenReturn(
                UsernamePasswordAuthenticationToken.authenticated(new UserContext(USER_ID), null, List.of()));

        try (HttpClient client = newClient()) {
            assertThat(send(client, "GET", "/api/auth/registration", null).statusCode()).isEqualTo(200);
            assertThat(send(client, "POST", "/api/auth/login", csrfToken(client)).statusCode()).isEqualTo(204);
            HttpResponse<String> currentUser = send(client, "GET", "/api/auth/me", null);
            assertThat(currentUser.statusCode()).isEqualTo(200);
            assertThat(currentUser.body()).contains(USER_ID.toString());

            // 登录后的 token 已轮换，重新通过 GET 获取，不使用 csrf() 测试注入。
            assertThat(send(client, "GET", "/api/auth/registration", null).statusCode()).isEqualTo(200);
            assertForbidden(send(client, "POST", "/api/auth/logout", null));
            assertForbidden(send(client, "POST", "/api/auth/logout", "invalid-token"));
            assertThat(send(client, "GET", "/api/auth/me", null).statusCode()).isEqualTo(200);
            assertThat(send(client, "POST", "/api/auth/logout", csrfToken(client)).statusCode()).isEqualTo(204);
            assertThat(send(client, "GET", "/api/auth/me", null).statusCode()).isEqualTo(401);
        }
        verify(authenticationProvider).authenticate(any());
    }

    @Test
    void singleUserStillHidesLoginAndProvidesIdentityAfterLogout() throws Exception {
        useSingleUserMode();
        try (HttpClient client = newClient()) {
            assertThat(send(client, "GET", "/api/auth/registration", null).statusCode()).isEqualTo(200);
            assertThat(send(client, "POST", "/api/auth/login", csrfToken(client)).statusCode()).isEqualTo(404);
            assertThat(send(client, "POST", "/api/auth/logout", csrfToken(client)).statusCode()).isEqualTo(204);
            HttpResponse<String> currentUser = send(client, "GET", "/api/auth/me", null);
            assertThat(currentUser.statusCode()).isEqualTo(200);
            assertThat(currentUser.body()).contains(USER_ID.toString());
        }
        verifyLoginWasNotAttempted();
    }

    private void useSingleUserMode() {
        when(persistentSingleUser.userContext()).thenReturn(Optional.of(new UserContext(USER_ID)));
        when(registrationCapability.state()).thenReturn(RegistrationCapability.State.DISABLED);
    }

    private void verifyLoginWasNotAttempted() {
        verify(authenticationProvider, never()).authenticate(any());
        verify(authenticationAttemptRateLimiter, never()).recordLoginAttempt(any(), any());
    }

    private static void assertForbidden(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).doesNotContain("UNAUTHENTICATED");
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static String csrfToken(HttpClient client) {
        CookieManager cookies = (CookieManager) client.cookieHandler().orElseThrow();
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> "XSRF-TOKEN".equals(cookie.getName()))
                .map(HttpCookie::getValue)
                .findFirst().orElseThrow();
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json");
        if (token != null) {
            request.header("X-XSRF-TOKEN", token);
        }
        if ("POST".equals(method)) {
            request.header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "email=error-dispatch%40example.test&password=synthetic-test-password"));
        }
        else {
            request.GET();
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
