package com.dailylanguage.languageprofile.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dailylanguage.languageprofile.application.LanguageProfileAccessService;
import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.security.domain.UserContext;

@RestController
@RequestMapping("/api/language-profiles")
public class LanguageProfileController {

    private final LanguageProfileAccessService languageProfileAccessService;

    public LanguageProfileController(LanguageProfileAccessService languageProfileAccessService) {
        this.languageProfileAccessService = languageProfileAccessService;
    }

    @GetMapping
    List<LanguageProfileIdentity> listLanguageProfiles(
            @AuthenticationPrincipal(errorOnInvalidType = true) UserContext userContext
    ) {
        return languageProfileAccessService.listProfilesOwnedByUser(userContext);
    }

    @GetMapping("/{languageProfileId}")
    ResponseEntity<LanguageProfileIdentity> getLanguageProfile(
            @PathVariable UUID languageProfileId,
            @AuthenticationPrincipal(errorOnInvalidType = true) UserContext userContext
    ) {
        return ResponseEntity.of(
                languageProfileAccessService.findProfileOwnedByUser(languageProfileId, userContext));
    }
}
