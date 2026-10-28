package com.dailylanguage.identity.account;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;

/** 通过真实 HTTP / PostgreSQL / Redis 验证框架生命周期，不新增注册或测试端点。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "IDENTITY_SESSION_COOKIE_SECURE=false")
@EnabledIfEnvironmentVariable(named = "IDENTITY_HTTP_AUTH_TESTS", matches = "true")
class IdentityAuthenticationHttpIntegrationTests {

    @LocalServerPort
    private int port;
    @Autowired
    private LocalRegistrationService registrations;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RedisSessionRepository sessions;

    @BeforeEach
    void requireIsolatedDatabase() {
        IdentityDatabaseTestData.requireVerificationDatabase(jdbc);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rotatesRestoresAndLogsOutOnlyTheCurrentSessionThroughRealHttp() throws Exception {
        String email = IdentityDatabaseTestData.uniqueEmail();
        String password = "  R1g original password 42  ";
        var userId = registrations.register(email, password);
        Session anonymous = sessions.createSession();
        ((SessionRepository) sessions).save(anonymous);
        String currentId = null;
        String otherId = null;
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var anonymousCookie = new HttpCookie("SESSION", Base64.getEncoder()
                .encodeToString(anonymous.getId().getBytes(StandardCharsets.UTF_8)));
        anonymousCookie.setPath("/");
        cookies.getCookieStore().add(uri("/"), anonymousCookie);
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(2)).build()) {
            var login = post(client, cookies, "/api/auth/login", "email=" + encode(email) + "&password=" + encode(password));
            assertThat(login.statusCode()).isEqualTo(204);
            assertThat(login.headers().firstValue("Location")).isEmpty();
            assertThat(login.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("SESSION=")))
                    .singleElement().asString().contains("HttpOnly", "SameSite=Lax").doesNotContain("Secure");
            String sessionCookie = cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("SESSION")).findFirst().orElseThrow().getValue();
            currentId = new String(Base64.getDecoder().decode(sessionCookie), StandardCharsets.UTF_8);
            assertThat(currentId).isNotEqualTo(anonymous.getId());
            assertThat((Session) sessions.findById(anonymous.getId())).isNull();
            Session restored = sessions.findById(currentId);
            assertThat(restored).isNotNull();
            SecurityContext context = restored.getAttribute(SPRING_SECURITY_CONTEXT_KEY);
            assertThat(context.getAuthentication().getPrincipal()).isEqualTo(new AuthenticatedAccount(userId));
            assertThat(context.getAuthentication().getCredentials()).isNull();
            var me = client.send(HttpRequest.newBuilder(uri("/api/auth/me"))
                    .header("X-User-Id", "00000000-0000-0000-0000-000000000001").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(me.statusCode()).isEqualTo(200);
            assertThat(JsonMapper.builder().build().readValue(me.body(), Map.class))
                    .isEqualTo(Map.of("userId", userId.toString()));

            Session other = sessions.createSession();
            otherId = other.getId();
            other.setAttribute(SPRING_SECURITY_CONTEXT_KEY, context);
            ((SessionRepository) sessions).save(other);
            var logout = post(client, cookies, "/api/auth/logout", "");
            assertThat(logout.statusCode()).isEqualTo(204);
            assertThat(logout.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("SESSION=")))
                    .singleElement().asString().contains("Max-Age=0");
            assertThat((Session) sessions.findById(currentId)).isNull();
            assertThat((Session) sessions.findById(otherId)).isNotNull();
            var replay = client.send(HttpRequest.newBuilder(uri("/api/auth/me"))
                    .header("Cookie", "SESSION=" + sessionCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(replay.statusCode()).isEqualTo(401);
            assertThat(post(client, cookies, "/api/auth/logout", "").statusCode()).isEqualTo(204);
        }
        finally {
            sessions.deleteById(anonymous.getId());
            if (currentId != null) { sessions.deleteById(currentId); }
            if (otherId != null) { sessions.deleteById(otherId); }
        }
    }

    private HttpResponse<String> post(HttpClient client, CookieManager cookies, String path, String body) throws Exception {
        client.send(HttpRequest.newBuilder(uri("/actuator/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
        String csrf = cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                .findFirst().orElseThrow().getValue();
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded").header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
