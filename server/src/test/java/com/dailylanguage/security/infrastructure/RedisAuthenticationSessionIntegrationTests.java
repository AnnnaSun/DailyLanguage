package com.dailylanguage.security.infrastructure;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.dailylanguage.authentication.application.LocalRegistrationService;
import com.dailylanguage.security.domain.UserContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.servlet.session.cookie.secure=false")
@AutoConfigureMockMvc
@EnabledIf("integrationDependenciesEnabled")
class RedisAuthenticationSessionIntegrationTests {

    private static final String SUBMITTED_PASSWORD = "S4C1c-safe-" + "x".repeat(20);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LocalRegistrationService registrationService;

    @Autowired
    private RedisSessionRepository sessionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<String> createdSessionIds = new ArrayList<>();
    private UUID registeredUserId;
    private String registeredEmail;

    @BeforeEach
    void registerLocalAccount() {
        registeredEmail = "s4c1c-" + UUID.randomUUID() + "@example.com";
        registeredUserId = registrationService.register(registeredEmail, SUBMITTED_PASSWORD);
    }

    @AfterEach
    void cleanUp() {
        createdSessionIds.forEach(sessionRepository::deleteById);
        if (registeredUserId != null) {
            jdbcTemplate.update("DELETE FROM auth_identity WHERE user_id = ?", registeredUserId);
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", registeredUserId);
        }
    }

    @Test
    void loginPersistsRedisSessionAndRestoresCurrentUserAcrossRequests() throws Exception {
        Cookie sessionCookie = login();
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

        sessionRepository.deleteById(sessionId);
        createdSessionIds.remove(sessionId);

        mockMvc.perform(get("/api/auth/me").cookie(sessionCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void logoutDeletesOnlyTheCurrentRedisSessionAndExpiresItsCookie() throws Exception {
        Cookie sessionCookie = login();
        String sessionId = decodedSessionId(sessionCookie);
        createdSessionIds.add(sessionId);

        MvcResult logoutResult = mockMvc.perform(post("/api/auth/logout")
                        .cookie(sessionCookie)
                        .with(csrf()))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(sessionRepository.findById(sessionId)).isNull();
        createdSessionIds.remove(sessionId);
        assertThat(logoutResult.getResponse().getHeaders("Set-Cookie"))
                .filteredOn(setCookie -> setCookie.startsWith("SESSION="))
                .singleElement()
                .satisfies(setCookie -> assertThat(setCookie)
                        .contains("SESSION=")
                        .contains("Max-Age=0")
                        .contains("Path=/"));

        mockMvc.perform(get("/api/auth/me").cookie(sessionCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private Cookie login() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", registeredEmail)
                        .param("password", SUBMITTED_PASSWORD))
                .andExpect(status().isNoContent())
                .andReturn();

        String setCookie = loginResult.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie)
                .contains("SESSION=")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .contains("Path=/")
                .doesNotContain("Max-Age");
        String encodedSessionId = setCookie.substring("SESSION=".length(), setCookie.indexOf(';'));
        return new Cookie("SESSION", encodedSessionId);
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
