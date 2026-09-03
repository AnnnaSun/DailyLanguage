package com.dailylanguage.languageprofile.api;

import static org.mockito.Mockito.never;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import com.dailylanguage.security.config.SecurityConfiguration;

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
    void rejectsUnauthenticatedCreateAccess() throws Exception {
        mockMvc.perform(post("/api/language-profiles")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"languageCode":"en"}
                                """))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(languageProfileRepository);
    }

    @Test
    void createsProfileForAuthenticatedUserAndReturnsItsResourceLocation() throws Exception {
        UUID requestedUserId = UUID.randomUUID();
        UUID authenticatedUserId = UUID.randomUUID();
        LanguageProfileIdentity createdProfile =
                new LanguageProfileIdentity(UUID.randomUUID(), authenticatedUserId, "en-us");
        when(languageProfileRepository.create(authenticatedUserId, " en-US "))
                .thenReturn(Optional.of(createdProfile));

        mockMvc.perform(post("/api/language-profiles")
                        .with(authenticatedAs(authenticatedUserId))
                        .with(csrf())
                        .header("X-User-Id", requestedUserId)
                        .param("userId", requestedUserId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","languageCode":" en-US "}
                                """.formatted(requestedUserId)))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location",
                        "/api/language-profiles/" + createdProfile.id()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(createdProfile.id().toString()))
                .andExpect(jsonPath("$.userId").value(authenticatedUserId.toString()))
                .andExpect(jsonPath("$.languageCode").value("en-us"));

        verify(languageProfileRepository).create(authenticatedUserId, " en-US ");
        verify(languageProfileRepository, never()).create(requestedUserId, " en-US ");
    }

    @Test
    void missingCsrfStopsBeforeCreatingProfile() throws Exception {
        UUID authenticatedUserId = UUID.randomUUID();

        mockMvc.perform(post("/api/language-profiles")
                        .with(authenticatedAs(authenticatedUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"languageCode":"en"}
                                """))
                .andExpect(status().isForbidden());

        verifyNoInteractions(languageProfileRepository);
    }

    @Test
    void rejectsMalformedLanguageCodeWithStableErrorCode() throws Exception {
        UUID authenticatedUserId = UUID.randomUUID();
        when(languageProfileRepository.create(authenticatedUserId, "not a tag"))
                .thenThrow(new IllegalArgumentException("persistence validation detail"));

        mockMvc.perform(post("/api/language-profiles")
                        .with(authenticatedAs(authenticatedUserId))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"languageCode":"not a tag"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LANGUAGE_CODE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("persistence validation detail"))));
    }

    @Test
    void rejectsMissingLanguageCodeWithStableErrorCode() throws Exception {
        UUID authenticatedUserId = UUID.randomUUID();
        when(languageProfileRepository.create(authenticatedUserId, null))
                .thenThrow(new IllegalArgumentException("languageCode must not be null"));

        mockMvc.perform(post("/api/language-profiles")
                        .with(authenticatedAs(authenticatedUserId))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LANGUAGE_CODE"));
    }

    @Test
    void duplicateLanguageForAuthenticatedUserReturnsConflict() throws Exception {
        UUID authenticatedUserId = UUID.randomUUID();
        when(languageProfileRepository.create(authenticatedUserId, "EN"))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/language-profiles")
                        .with(authenticatedAs(authenticatedUserId))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"languageCode":"EN"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LANGUAGE_PROFILE_ALREADY_EXISTS"));

        verify(languageProfileRepository).create(authenticatedUserId, "EN");
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

    @Test
    void singleUserModeCreatesProfileThroughTheSameDomainAuthorizationPath() throws Exception {
        UUID requestedUserId = UUID.randomUUID();
        UUID singleUserId = UUID.randomUUID();
        LanguageProfileIdentity createdProfile =
                new LanguageProfileIdentity(UUID.randomUUID(), singleUserId, "ja");
        when(persistentSingleUser.userContext()).thenReturn(Optional.of(new UserContext(singleUserId)));
        when(languageProfileRepository.create(singleUserId, "ja"))
                .thenReturn(Optional.of(createdProfile));

        mockMvc.perform(post("/api/language-profiles")
                        .with(csrf())
                        .param("userId", requestedUserId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"languageCode":"ja"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(singleUserId.toString()));

        verify(languageProfileRepository).create(singleUserId, "ja");
        verify(languageProfileRepository, never()).create(requestedUserId, "ja");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor authenticatedAs(UUID userId) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                new UserContext(userId),
                null,
                List.of()
        ));
    }
}
