package com.dailylanguage.security.infrastructure;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dailylanguage.authentication.infrastructure.LocalPasswordAuthenticationProvider;
import com.dailylanguage.security.domain.UserContext;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.registration-enabled=true",
        "spring.flyway.enabled=false",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1",
        "spring.data.redis.connect-timeout=100ms",
        "spring.data.redis.timeout=100ms",
        "spring.data.redis.lettuce.shutdown-timeout=2s",
        "server.servlet.session.cookie.secure=false"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "RUN_REDIS_UNAVAILABLE_TESTS", matches = "true")
class RedisSessionUnavailableHttpIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LocalPasswordAuthenticationProvider authenticationProvider;

    @BeforeEach
    void authenticateSubmittedLogin() {
        when(authenticationProvider.supports(UsernamePasswordAuthenticationToken.class)).thenReturn(true);
        when(authenticationProvider.authenticate(any())).thenReturn(
                UsernamePasswordAuthenticationToken.authenticated(
                        new UserContext(UUID.randomUUID()),
                        null,
                        List.of()));
    }

    @Test
    void loginSessionSaveFailureReturnsFixedUnavailableResponse() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", "learner@example.com")
                        .param("password", "correct horse battery staple"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("127.0.0.1"))));
    }

    @Test
    void currentUserSessionRestoreFailureReturnsFixedUnavailableResponse() throws Exception {
        String unavailableSessionId = Base64.getEncoder().encodeToString(
                UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(get("/api/auth/me")
                        .cookie(new Cookie("SESSION", unavailableSessionId)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_UNAVAILABLE"));
    }
}
