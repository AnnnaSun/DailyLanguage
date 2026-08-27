package com.dailylanguage.security;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.dailylanguage.authentication.infrastructure.LocalPasswordAuthenticationProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CurrentUserController.class)
@Import({SecurityConfiguration.class, AuthenticationHttpResponseWriter.class})
class AuthenticationHttpContractTests {

    private static final String SUBMITTED_EMAIL = "learner@example.com";
    private static final String SUBMITTED_PASSWORD = "correct horse battery staple";

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
    void loginRotatesSessionAndStoresOnlyAuthenticatedUserContext() throws Exception {
        UUID userId = UUID.randomUUID();
        when(authenticationProvider.authenticate(any())).thenReturn(authenticatedAs(userId));
        MockHttpSession anonymousSession = new MockHttpSession();
        String anonymousSessionId = anonymousSession.getId();

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .session(anonymousSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", SUBMITTED_EMAIL)
                        .param("password", SUBMITTED_PASSWORD))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().doesNotExist("Location"))
                .andReturn();

        MockHttpSession authenticatedSession = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(authenticatedSession).isNotNull();
        assertThat(authenticatedSession.getId()).isNotEqualTo(anonymousSessionId);

        SecurityContext storedSecurityContext =
                (SecurityContext) authenticatedSession.getAttribute(SPRING_SECURITY_CONTEXT_KEY);
        assertThat(storedSecurityContext.getAuthentication().getPrincipal()).isEqualTo(new UserContext(userId));
        assertThat(storedSecurityContext.getAuthentication().getCredentials()).isNull();

        mockMvc.perform(get("/api/auth/me").session(authenticatedSession))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.userId").value(userId.toString()));
    }

    @Test
    void invalidOrMissingCredentialsReturnTheSameSafeResponse() throws Exception {
        when(authenticationProvider.authenticate(any())).thenThrow(new BadCredentialsException("internal detail"));

        assertInvalidCredentials(post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("email", SUBMITTED_EMAIL)
                .param("password", "wrong password"));
        assertInvalidCredentials(post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED));
    }

    @Test
    void authenticationInfrastructureFailureReturnsGenericUnavailableResponse() throws Exception {
        when(authenticationProvider.authenticate(any()))
                .thenThrow(new AuthenticationServiceException("database detail"));

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", SUBMITTED_EMAIL)
                        .param("password", SUBMITTED_PASSWORD))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("database detail"))));
    }

    @Test
    void rateLimitedLoginReturnsRetryAfterWithoutAuthenticating() throws Exception {
        when(authenticationAttemptRateLimiter.recordLoginAttempt(any(), any()))
                .thenReturn(RedisAuthenticationAttemptRateLimiter.AttemptDecision.rejectFor(47));

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", SUBMITTED_EMAIL)
                        .param("password", SUBMITTED_PASSWORD))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "47"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("TOO_MANY_LOGIN_ATTEMPTS"));

        verify(authenticationProvider, never()).authenticate(any());
    }

    @Test
    void loginRateLimitStorageFailureStopsAuthenticationAndReturnsUnavailable() throws Exception {
        when(authenticationAttemptRateLimiter.recordLoginAttempt(any(), any()))
                .thenThrow(new RedisConnectionFailureException("redis connection detail"));

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", SUBMITTED_EMAIL)
                        .param("password", SUBMITTED_PASSWORD))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("redis connection detail"))));

        verify(authenticationProvider, never()).authenticate(any());
    }

    @Test
    void unauthenticatedCurrentUserReturnsFixedResponse() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void loginAndLogoutRequireValidCsrf() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", SUBMITTED_EMAIL)
                        .param("password", SUBMITTED_PASSWORD))
                .andExpect(status().isForbidden());
        verifyNoInteractions(authenticationProvider);
        verify(authenticationAttemptRateLimiter, never()).recordLoginAttempt(any(), any());

        MockHttpSession authenticatedSession = sessionFor(UUID.randomUUID());
        mockMvc.perform(post("/api/auth/logout")
                        .session(authenticatedSession)
                        .with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        assertThat(authenticatedSession.isInvalid()).isFalse();
    }

    @Test
    void logoutInvalidatesTheCurrentSession() throws Exception {
        MockHttpSession authenticatedSession = sessionFor(UUID.randomUUID());

        mockMvc.perform(post("/api/auth/logout")
                        .session(authenticatedSession)
                        .with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(authenticatedSession.isInvalid()).isTrue();
    }

    @Test
    void logoutIsIdempotentWithValidCsrf() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .session(new MockHttpSession())
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void doesNotExposeAGetLoginPage() throws Exception {
        mockMvc.perform(get("/api/auth/login"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("<form"))));
    }

    private void assertInvalidCredentials(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("internal detail"))));
    }

    private static UsernamePasswordAuthenticationToken authenticatedAs(UUID userId) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new UserContext(userId),
                null,
                List.of());
    }

    private static MockHttpSession sessionFor(UUID userId) {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authenticatedAs(userId));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, securityContext);
        return session;
    }
}
