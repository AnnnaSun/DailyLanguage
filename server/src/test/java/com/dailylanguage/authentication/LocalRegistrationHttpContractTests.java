package com.dailylanguage.authentication;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dailylanguage.authentication.LocalRegistrationException.FailureReason;
import com.dailylanguage.security.AuthenticationHttpResponseWriter;
import com.dailylanguage.security.RedisAuthenticationAttemptRateLimiter;
import com.dailylanguage.security.SecurityConfiguration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LocalRegistrationController.class)
@Import({SecurityConfiguration.class, AuthenticationHttpResponseWriter.class})
class LocalRegistrationHttpContractTests {

    private static final String SUBMITTED_EMAIL = "learner@example.com";
    private static final String SUBMITTED_PASSWORD = "S4C3a-safe-" + "x".repeat(20);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LocalRegistrationService registrationService;

    @MockitoBean
    private LocalPasswordAuthenticationProvider authenticationProvider;

    @MockitoBean
    private RedisAuthenticationAttemptRateLimiter authenticationAttemptRateLimiter;

    @BeforeEach
    void allowRegistrationAttempt() {
        when(authenticationProvider.supports(UsernamePasswordAuthenticationToken.class)).thenReturn(true);
        when(authenticationAttemptRateLimiter.recordRegistrationAttempt(any(), nullable(String.class)))
                .thenReturn(new RedisAuthenticationAttemptRateLimiter.AttemptDecision(true, 0));
    }

    @Test
    void reportsHostedRegistrationAsPublic() throws Exception {
        mockMvc.perform(get("/api/auth/registration"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.state").value("PUBLIC"));
    }

    @Test
    void successfulRegistrationReturnsNoContentAndDoesNotCreateSession() throws Exception {
        mockMvc.perform(post("/api/auth/registration")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", SUBMITTED_EMAIL)
                        .param("password", SUBMITTED_PASSWORD))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(cookie().doesNotExist("SESSION"));

        InOrder order = inOrder(authenticationAttemptRateLimiter, registrationService);
        order.verify(authenticationAttemptRateLimiter)
                .recordRegistrationAttempt("127.0.0.1", SUBMITTED_EMAIL);
        order.verify(registrationService).register(SUBMITTED_EMAIL, SUBMITTED_PASSWORD);
    }

    @Test
    void missingCsrfStopsBeforeRateLimitAndRegistration() throws Exception {
        mockMvc.perform(post("/api/auth/registration")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", SUBMITTED_EMAIL)
                        .param("password", SUBMITTED_PASSWORD))
                .andExpect(status().isForbidden());

        verify(authenticationAttemptRateLimiter, never()).recordRegistrationAttempt(any(), any());
        verifyNoInteractions(registrationService);
    }

    @Test
    void rateLimitedRegistrationReturnsRetryAfterWithoutCallingRegistrationService() throws Exception {
        when(authenticationAttemptRateLimiter.recordRegistrationAttempt(any(), any()))
                .thenReturn(new RedisAuthenticationAttemptRateLimiter.AttemptDecision(false, 3_599));

        mockMvc.perform(post("/api/auth/registration")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", SUBMITTED_EMAIL)
                        .param("password", SUBMITTED_PASSWORD))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3599"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REGISTRATION_ATTEMPTS"));

        verifyNoInteractions(registrationService);
    }

    @Test
    void rateLimitStorageFailureFailsClosedWithoutCallingRegistrationService() throws Exception {
        when(authenticationAttemptRateLimiter.recordRegistrationAttempt(any(), any()))
                .thenThrow(new RedisConnectionFailureException("redis detail learner@example.com"));

        mockMvc.perform(post("/api/auth/registration")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", SUBMITTED_EMAIL)
                        .param("password", SUBMITTED_PASSWORD))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REGISTRATION_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("learner@example.com"))));

        verifyNoInteractions(registrationService);
    }

    @ParameterizedTest
    @EnumSource(
            value = FailureReason.class,
            names = {
                    "INVALID_EMAIL",
                    "INVALID_PASSWORD_LENGTH",
                    "INVALID_PASSWORD_CHARACTER",
                    "COMMON_OR_COMPROMISED_PASSWORD"
            })
    void policyFailuresReturnSpecificBadRequestCodes(FailureReason failureReason) throws Exception {
        when(registrationService.register(any(), any()))
                .thenThrow(new LocalRegistrationException(failureReason));

        mockMvc.perform(post("/api/auth/registration")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", SUBMITTED_EMAIL)
                        .param("password", SUBMITTED_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(failureReason.name()));
    }

    @Test
    void duplicateIdentityReturnsConflictWithoutExposingIdentityDetails() throws Exception {
        when(registrationService.register(any(), any()))
                .thenThrow(new LocalRegistrationException(FailureReason.IDENTITY_UNAVAILABLE));

        mockMvc.perform(post("/api/auth/registration")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", SUBMITTED_EMAIL)
                        .param("password", SUBMITTED_PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDENTITY_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(SUBMITTED_EMAIL))));
    }

    @Test
    void registrationInfrastructureFailureReturnsGenericUnavailableResponse() throws Exception {
        when(registrationService.register(any(), any()))
                .thenThrow(new LocalRegistrationException(FailureReason.REGISTRATION_FAILED));

        mockMvc.perform(post("/api/auth/registration")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", SUBMITTED_EMAIL)
                        .param("password", SUBMITTED_PASSWORD))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REGISTRATION_UNAVAILABLE"));
    }
}
