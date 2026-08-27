package com.dailylanguage.security;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.dailylanguage.authentication.infrastructure.LocalPasswordAuthenticationProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CurrentUserController.class)
@Import({SecurityConfiguration.class, AuthenticationHttpResponseWriter.class})
@TestPropertySource(properties = "server.servlet.session.cookie.secure=false")
class SpaCsrfHttpContractTests {

    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String CSRF_HEADER_NAME = "X-XSRF-TOKEN";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LocalPasswordAuthenticationProvider authenticationProvider;

    @MockitoBean
    private RedisAuthenticationAttemptRateLimiter authenticationAttemptRateLimiter;

    @BeforeEach
    void supportsUsernamePasswordLogin() {
        when(authenticationProvider.supports(UsernamePasswordAuthenticationToken.class)).thenReturn(true);
        when(authenticationAttemptRateLimiter.recordLoginAttempt(any(), any()))
                .thenReturn(RedisAuthenticationAttemptRateLimiter.AttemptDecision.allow());
    }

    @Test
    void spaCanSubmitTheCsrfCookieValueAsAHeader() throws Exception {
        Cookie csrfCookie = requestCsrfCookie();
        assertThat(csrfCookie.isHttpOnly()).isFalse();
        assertThat(csrfCookie.getSecure()).isFalse();
        assertThat(csrfCookie.getPath()).isEqualTo("/");
        assertThat(csrfCookie.getAttribute("SameSite")).isEqualTo("Lax");

        when(authenticationProvider.authenticate(any())).thenReturn(authenticatedAs(UUID.randomUUID()));

        mockMvc.perform(post("/api/auth/login")
                        .cookie(csrfCookie)
                        .header(CSRF_HEADER_NAME, csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", "learner@example.com")
                        .param("password", "correct horse battery staple"))
                .andExpect(status().isNoContent());
    }

    @Test
    void missingOrMismatchedSpaCsrfHeaderStopsBeforeAuthentication() throws Exception {
        Cookie csrfCookie = requestCsrfCookie();

        mockMvc.perform(post("/api/auth/login")
                        .cookie(csrfCookie)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/auth/login")
                        .cookie(csrfCookie)
                        .header(CSRF_HEADER_NAME, "mismatched-token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isForbidden());

        verify(authenticationProvider, never()).authenticate(any());
        verify(authenticationAttemptRateLimiter, never()).recordLoginAttempt(any(), any());
    }

    @Test
    void authenticationAndLogoutClearTheBrowserTokenBeforeAReplacementIsIssued() throws Exception {
        Cookie anonymousCsrfCookie = requestCsrfCookie();
        when(authenticationProvider.authenticate(any())).thenReturn(authenticatedAs(UUID.randomUUID()));

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .cookie(anonymousCsrfCookie)
                        .header(CSRF_HEADER_NAME, anonymousCsrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", "learner@example.com")
                        .param("password", "correct horse battery staple"))
                .andExpect(status().isNoContent())
                .andReturn();

        assertCsrfCookieWasCleared(loginResult);
        MockHttpSession authenticatedSession =
                (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(authenticatedSession).isNotNull();

        MvcResult currentUserResult = mockMvc.perform(get("/api/auth/me")
                        .session(authenticatedSession))
                .andExpect(status().isOk())
                .andReturn();
        Cookie authenticatedCsrfCookie = csrfCookieFrom(currentUserResult);
        assertThat(authenticatedCsrfCookie.getValue()).isNotEqualTo(anonymousCsrfCookie.getValue());

        MvcResult logoutResult = mockMvc.perform(post("/api/auth/logout")
                        .session(authenticatedSession)
                        .cookie(authenticatedCsrfCookie)
                        .header(CSRF_HEADER_NAME, authenticatedCsrfCookie.getValue()))
                .andExpect(status().isNoContent())
                .andReturn();

        assertCsrfCookieWasCleared(logoutResult);
        assertThat(authenticatedSession.isInvalid()).isTrue();
    }

    private Cookie requestCsrfCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        return csrfCookieFrom(result);
    }

    private static Cookie csrfCookieFrom(MvcResult result) {
        Cookie csrfCookie = result.getResponse().getCookie(CSRF_COOKIE_NAME);
        if (csrfCookie == null) {
            throw new AssertionError("Response did not contain an XSRF-TOKEN cookie");
        }
        return csrfCookie;
    }

    private static void assertCsrfCookieWasCleared(MvcResult result) {
        Cookie clearedCookie = csrfCookieFrom(result);
        assertThat(clearedCookie.getValue()).isEmpty();
        assertThat(clearedCookie.getMaxAge()).isZero();
    }

    private static UsernamePasswordAuthenticationToken authenticatedAs(UUID userId) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new UserContext(userId),
                null,
                List.of());
    }
}
