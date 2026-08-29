package com.dailylanguage.languageprofile.api;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dailylanguage.languageprofile.application.LanguageProfileAccessService;
import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.languageprofile.infrastructure.LanguageProfileRepository;
import com.dailylanguage.security.domain.UserContext;
import com.dailylanguage.security.infrastructure.AuthenticationHttpResponseWriter;
import com.dailylanguage.security.infrastructure.PersistentSingleUser;
import com.dailylanguage.security.infrastructure.RedisAuthenticationAttemptRateLimiter;
import com.dailylanguage.security.infrastructure.SecurityConfiguration;

@WebMvcTest(LanguageProfileController.class)
@Import({
        SecurityConfiguration.class,
        AuthenticationHttpResponseWriter.class,
        LanguageProfileAccessService.class
})
class LanguageProfileSecurityTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LanguageProfileRepository languageProfileRepository;

    @MockitoBean
    private RedisAuthenticationAttemptRateLimiter authenticationAttemptRateLimiter;

    @MockitoBean
    private PersistentSingleUser persistentSingleUser;

    @BeforeEach
    void useRegisteredUserAuthenticationMode() {
        when(persistentSingleUser.userContext()).thenReturn(Optional.empty());
    }

    @Test
    void rejectsUnauthenticatedAccess() throws Exception {
        mockMvc.perform(get("/api/language-profiles/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(languageProfileRepository);
    }

    @Test
    void rejectsUnauthenticatedListAccess() throws Exception {
        mockMvc.perform(get("/api/language-profiles"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(languageProfileRepository);
    }

    @Test
    void listsProfilesOwnedByAuthenticatedUser() throws Exception {
        UUID ownerId = UUID.randomUUID();
        LanguageProfileIdentity englishProfile =
                new LanguageProfileIdentity(UUID.randomUUID(), ownerId, "en");
        LanguageProfileIdentity japaneseProfile =
                new LanguageProfileIdentity(UUID.randomUUID(), ownerId, "ja");
        when(languageProfileRepository.listByUserId(ownerId))
                .thenReturn(List.of(englishProfile, japaneseProfile));

        mockMvc.perform(get("/api/language-profiles")
                        .with(authenticatedAs(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(englishProfile.id().toString()))
                .andExpect(jsonPath("$[0].userId").value(ownerId.toString()))
                .andExpect(jsonPath("$[0].languageCode").value("en"))
                .andExpect(jsonPath("$[1].id").value(japaneseProfile.id().toString()))
                .andExpect(jsonPath("$[1].userId").value(ownerId.toString()))
                .andExpect(jsonPath("$[1].languageCode").value("ja"));

        verify(languageProfileRepository).listByUserId(ownerId);
    }

    @Test
    void listIgnoresUserIdSuppliedByTheRequest() throws Exception {
        UUID requestedUserId = UUID.randomUUID();
        UUID authenticatedUserId = UUID.randomUUID();
        when(languageProfileRepository.listByUserId(authenticatedUserId)).thenReturn(List.of());

        mockMvc.perform(get("/api/language-profiles")
                        .param("userId", requestedUserId.toString())
                        .header("X-User-Id", requestedUserId)
                        .with(authenticatedAs(authenticatedUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        verify(languageProfileRepository).listByUserId(authenticatedUserId);
        verify(languageProfileRepository, never()).listByUserId(requestedUserId);
    }

    @Test
    void returnsProfileOwnedByAuthenticatedUser() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(languageProfileRepository.findByIdAndUserId(profileId, ownerId))
                .thenReturn(Optional.of(new LanguageProfileIdentity(profileId, ownerId, "en")));

        mockMvc.perform(get("/api/language-profiles/{id}", profileId)
                        .with(authenticatedAs(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(profileId.toString()))
                .andExpect(jsonPath("$.userId").value(ownerId.toString()))
                .andExpect(jsonPath("$.languageCode").value("en"));

        verify(languageProfileRepository).findByIdAndUserId(profileId, ownerId);
    }

    @Test
    void hidesAnotherUsersProfile() throws Exception {
        UUID otherUserId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(languageProfileRepository.findByIdAndUserId(profileId, otherUserId))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/language-profiles/{id}", profileId)
                        .with(authenticatedAs(otherUserId)))
                .andExpect(status().isNotFound());

        verify(languageProfileRepository).findByIdAndUserId(profileId, otherUserId);
    }

    @Test
    void ignoresUserIdSuppliedByTheRequest() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID authenticatedUserId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(languageProfileRepository.findByIdAndUserId(profileId, authenticatedUserId))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/language-profiles/{id}", profileId)
                        .param("userId", ownerId.toString())
                        .with(authenticatedAs(authenticatedUserId)))
                .andExpect(status().isNotFound());

        verify(languageProfileRepository).findByIdAndUserId(profileId, authenticatedUserId);
        verify(languageProfileRepository, never()).findByIdAndUserId(profileId, ownerId);
    }

    @Test
    void singleUserModeUsesThePersistentUserContextForDomainAuthorization() throws Exception {
        UUID singleUserId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(persistentSingleUser.userContext())
                .thenReturn(Optional.of(new UserContext(singleUserId)));
        when(languageProfileRepository.findByIdAndUserId(profileId, singleUserId))
                .thenReturn(Optional.of(new LanguageProfileIdentity(profileId, singleUserId, "en")));

        mockMvc.perform(get("/api/language-profiles/{id}", profileId)
                        .param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(singleUserId.toString()));

        verify(languageProfileRepository).findByIdAndUserId(profileId, singleUserId);
    }

    @Test
    void singleUserModeListsProfilesThroughTheSameDomainAuthorizationPath() throws Exception {
        UUID singleUserId = UUID.randomUUID();
        LanguageProfileIdentity profile =
                new LanguageProfileIdentity(UUID.randomUUID(), singleUserId, "en");
        when(persistentSingleUser.userContext())
                .thenReturn(Optional.of(new UserContext(singleUserId)));
        when(languageProfileRepository.listByUserId(singleUserId)).thenReturn(List.of(profile));

        mockMvc.perform(get("/api/language-profiles")
                        .param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(profile.id().toString()))
                .andExpect(jsonPath("$[0].userId").value(singleUserId.toString()))
                .andExpect(jsonPath("$[0].languageCode").value("en"));

        verify(languageProfileRepository).listByUserId(singleUserId);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor authenticatedAs(UUID userId) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                new UserContext(userId),
                null,
                List.of()
        ));
    }
}
