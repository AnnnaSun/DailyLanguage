package com.dailylanguage.modelgateway.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.dailylanguage.modelgateway.application.ProviderConnectionVerificationService;
import com.dailylanguage.modelgateway.application.ProviderConnectionVerificationService.ProviderPreset;
import com.dailylanguage.modelgateway.result.ModelFailure;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.result.ModelResult;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.security.domain.UserContext;
import com.dailylanguage.security.infrastructure.AuthenticationHttpResponseWriter;
import com.dailylanguage.security.infrastructure.PersistentSingleUser;
import com.dailylanguage.security.infrastructure.RedisAuthenticationAttemptRateLimiter;
import com.dailylanguage.security.infrastructure.SecurityConfiguration;

@WebMvcTest(ModelProviderPresetController.class)
@Import({SecurityConfiguration.class, AuthenticationHttpResponseWriter.class})
class ModelProviderPresetControllerTests {

    private static final ProviderId PROVIDER_ID = new ProviderId("deepseek");
    private static final ModelId MODEL_ID = new ModelId("deepseek-chat");
    private static final ProviderPreset PRESET = new ProviderPreset(PROVIDER_ID, MODEL_ID);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProviderConnectionVerificationService verificationService;

    @MockitoBean
    private RedisAuthenticationAttemptRateLimiter authenticationAttemptRateLimiter;

    @MockitoBean
    private PersistentSingleUser persistentSingleUser;

    @BeforeEach
    void useRegisteredUserAuthenticationMode() {
        when(persistentSingleUser.userContext()).thenReturn(Optional.empty());
    }

    @Test
    void requiresAuthenticationBeforeListingPresets() throws Exception {
        mockMvc.perform(get("/api/model-provider-presets"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(verificationService);
    }

    @Test
    void listsConfiguredPresetWithoutEndpointOrProtocolDetails() throws Exception {
        when(verificationService.configuredPresets()).thenReturn(List.of(PRESET));

        mockMvc.perform(get("/api/model-provider-presets")
                        .with(authenticatedUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].providerId").value("deepseek"))
                .andExpect(jsonPath("$[0].modelId").value("deepseek-chat"))
                .andExpect(jsonPath("$[0].endpoint").doesNotExist())
                .andExpect(jsonPath("$[0].protocol").doesNotExist());
    }

    @Test
    void csrfStopsVerificationBeforeCredentialUse() throws Exception {
        mockMvc.perform(post("/api/model-provider-presets/deepseek/verify")
                        .with(authenticatedUser())
                        .header(ModelProviderPresetController.PROVIDER_CREDENTIAL_HEADER, "unused-secret"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(verificationService);
    }

    @Test
    void rejectsMissingCredentialWithoutCallingProvider() throws Exception {
        mockMvc.perform(post("/api/model-provider-presets/deepseek/verify")
                        .with(authenticatedUser())
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROVIDER_CREDENTIAL"));

        verifyNoInteractions(verificationService);
    }

    @Test
    void verifiesConnectionWithoutReturningCredentialOrGeneratedText() throws Exception {
        String credential = "browser-only-secret";
        when(verificationService.verifyConnection(PROVIDER_ID, credential))
                .thenReturn(ModelResult.success(PRESET));

        mockMvc.perform(post("/api/model-provider-presets/deepseek/verify")
                        .with(authenticatedUser())
                        .with(csrf())
                        .header(ModelProviderPresetController.PROVIDER_CREDENTIAL_HEADER, credential))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.providerId").value("deepseek"))
                .andExpect(jsonPath("$.modelId").value("deepseek-chat"))
                .andExpect(content().string(not(containsString(credential))))
                .andExpect(jsonPath("$.text").doesNotExist());

        verify(verificationService).verifyConnection(PROVIDER_ID, credential);
    }

    @Test
    void mapsProviderAuthenticationFailureWithoutReturningCredential() throws Exception {
        String credential = "rejected-secret";
        ModelFailure failure = ModelFailure.forRoute(
                ModelFailureKind.AUTHENTICATION_FAILED,
                PROVIDER_ID,
                MODEL_ID);
        when(verificationService.verifyConnection(PROVIDER_ID, credential))
                .thenReturn(ModelResult.failure(failure));

        mockMvc.perform(post("/api/model-provider-presets/deepseek/verify")
                        .with(authenticatedUser())
                        .with(csrf())
                        .header(ModelProviderPresetController.PROVIDER_CREDENTIAL_HEADER, credential))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
                .andExpect(content().string(not(containsString(credential))));
    }

    @Test
    void mapsRateLimitAndSafeRetryAfter() throws Exception {
        String credential = "rate-limited-secret";
        ModelFailure failure = ModelFailure.forRoute(
                ModelFailureKind.RATE_LIMITED,
                PROVIDER_ID,
                MODEL_ID,
                Duration.ofSeconds(12));
        when(verificationService.verifyConnection(PROVIDER_ID, credential))
                .thenReturn(ModelResult.failure(failure));

        mockMvc.perform(post("/api/model-provider-presets/deepseek/verify")
                        .with(authenticatedUser())
                        .with(csrf())
                        .header(ModelProviderPresetController.PROVIDER_CREDENTIAL_HEADER, credential))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "12"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(12))
                .andExpect(content().string(not(containsString(credential))));
    }

    private static RequestPostProcessor authenticatedUser() {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                new UserContext(UUID.randomUUID()),
                null,
                List.of()));
    }
}
