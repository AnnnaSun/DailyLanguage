package com.dailylanguage.identity.security.infrastructure;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import com.dailylanguage.identity.account.domain.AuthenticatedAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = IdentityBoundaryHttpTests.TestApplication.class, properties = {
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1",
        "spring.data.redis.connect-timeout=100ms", "spring.data.redis.timeout=100ms",
        "management.health.redis.enabled=false", "IDENTITY_SESSION_COOKIE_SECURE=false"
})
@EnabledIfEnvironmentVariable(named = "IDENTITY_REDIS_UNAVAILABLE_TESTS", matches = "true")
class RedisSessionUnavailableIntegrationTests {

    @Autowired
    private RedisSessionRepository sessions;
    @Autowired
    private ApplicationContext application;

    @LocalServerPort
    private int port;
    @MockitoBean
    private AuthenticationProvider authentication;

    @BeforeEach
    void provideAnAuthenticatedAccountWithoutAnAccountDatabase() {
        when(authentication.supports(UsernamePasswordAuthenticationToken.class)).thenReturn(true);
        when(authentication.authenticate(any())).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedAccount(UUID.randomUUID()), null, List.of()));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void refusesPersistenceInsteadOfFallingBackToAnInMemoryRepository() {
        assertThat(application.getBeansOfType(SessionRepository.class)).hasSize(1).containsValue(sessions);
        Session session = sessions.createSession();

        assertThatThrownBy(() -> ((SessionRepository) sessions).save(session))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void returnsUnavailableForLoginSaveAndCurrentUserRestoreInsteadOfSuccess() throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        String base = "http://127.0.0.1:" + port;
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build()) {
            client.send(HttpRequest.newBuilder(URI.create(base + "/actuator/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            String csrf = cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                    .findFirst().orElseThrow().getValue();
            var login = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded").header("X-XSRF-TOKEN", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString("email=synthetic%40example.com&password=synthetic-password"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertUnavailable(login);
            // 合成未知 Session ID 只触发读取失败；不使用客户端自报 userId 建立身份。
            String session = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            cookies.getCookieStore().removeAll();
            var me = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/me"))
                    .header("Cookie", "SESSION=" + session).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertUnavailable(me);
        }
    }

    private static void assertUnavailable(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).isEqualTo("{\"code\":\"AUTHENTICATION_UNAVAILABLE\"}");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(response.body()).doesNotContain("127.0.0.1", "synthetic-password", "Redis");
    }
}
