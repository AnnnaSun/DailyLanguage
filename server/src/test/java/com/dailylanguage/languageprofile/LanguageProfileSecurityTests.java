package com.dailylanguage.languageprofile;

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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dailylanguage.security.SecurityConfiguration;
import com.dailylanguage.security.AuthenticationHttpResponseWriter;
import com.dailylanguage.security.UserContext;

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

    @Test
    void rejectsUnauthenticatedAccess() throws Exception {
        mockMvc.perform(get("/api/language-profiles/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(languageProfileRepository);
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

    private static org.springframework.test.web.servlet.request.RequestPostProcessor authenticatedAs(UUID userId) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                new UserContext(userId),
                null,
                List.of()
        ));
    }
}
