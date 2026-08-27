package com.dailylanguage.authentication;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.dailylanguage.security.domain.UserContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.session.cookie.secure=false",
                "app.security.login-rate-limit.key-prefix=daily-language:test:c3a-login-rate-limit",
                "app.security.registration-rate-limit.key-prefix=daily-language:test:c3a-registration-rate-limit"
        })
@AutoConfigureMockMvc
@EnabledIf("integrationDependenciesEnabled")
class LocalRegistrationLoginIntegrationTests {

    private static final String LOGIN_RATE_LIMIT_KEY_PREFIX = "daily-language:test:c3a-login-rate-limit";
    private static final String REGISTRATION_RATE_LIMIT_KEY_PREFIX =
            "daily-language:test:c3a-registration-rate-limit";
    private static final String SUBMITTED_PASSWORD = "S4C3a-safe-" + "x".repeat(20);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RedisSessionRepository sessionRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<String> createdSessionIds = new ArrayList<>();
    private UUID registeredUserId;

    @AfterEach
    void cleanUp() {
        createdSessionIds.forEach(sessionRepository::deleteById);
        if (registeredUserId != null) {
            jdbcTemplate.update("DELETE FROM auth_identity WHERE user_id = ?", registeredUserId);
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", registeredUserId);
        }
        deleteRateLimitKeys(LOGIN_RATE_LIMIT_KEY_PREFIX);
        deleteRateLimitKeys(REGISTRATION_RATE_LIMIT_KEY_PREFIX);
    }

    @Test
    void hostedRegistrationThenLoginCreatesRedisSessionAndRestoresCurrentUser() throws Exception {
        String registeredEmail = "s4c3a-" + UUID.randomUUID() + "@example.com";

        mockMvc.perform(get("/api/auth/registration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PUBLIC"));

        mockMvc.perform(post("/api/auth/registration")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", registeredEmail)
                        .param("password", SUBMITTED_PASSWORD))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(cookie().doesNotExist("SESSION"));

        registeredUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM auth_identity WHERE provider = 'LOCAL_EMAIL' AND provider_subject = ?",
                UUID.class,
                registeredEmail);
        assertThat(registeredUserId).isNotNull();

        Cookie sessionCookie = login(registeredEmail);
        String sessionId = decodedSessionId(sessionCookie);
        createdSessionIds.add(sessionId);

        org.springframework.session.Session storedSession = sessionRepository.findById(sessionId);
        assertThat(storedSession).isNotNull();
        SecurityContext storedSecurityContext = storedSession.getAttribute(SPRING_SECURITY_CONTEXT_KEY);
        assertThat(storedSecurityContext.getAuthentication().getPrincipal())
                .isEqualTo(new UserContext(registeredUserId));
        assertThat(storedSecurityContext.getAuthentication().getCredentials()).isNull();

        mockMvc.perform(get("/api/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.userId").value(registeredUserId.toString()));
    }

    private Cookie login(String registeredEmail) throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", registeredEmail)
                        .param("password", SUBMITTED_PASSWORD))
                .andExpect(status().isNoContent())
                .andReturn();

        String setCookie = loginResult.getResponse().getHeaders("Set-Cookie").stream()
                .filter(header -> header.startsWith("SESSION="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Login response did not contain a SESSION cookie"));
        assertThat(setCookie)
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .contains("Path=/")
                .doesNotContain("Max-Age");
        String encodedSessionId = setCookie.substring("SESSION=".length(), setCookie.indexOf(';'));
        return new Cookie("SESSION", encodedSessionId);
    }

    private void deleteRateLimitKeys(String keyPrefix) {
        Set<String> keys = redisTemplate.keys(keyPrefix + "*");
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private static String decodedSessionId(Cookie sessionCookie) {
        return new String(
                Base64.getDecoder().decode(sessionCookie.getValue()),
                StandardCharsets.UTF_8);
    }

    static boolean integrationDependenciesEnabled() {
        return "true".equalsIgnoreCase(System.getenv("RUN_DATABASE_TESTS"))
                && "true".equalsIgnoreCase(System.getenv("RUN_REDIS_TESTS"));
    }
}
