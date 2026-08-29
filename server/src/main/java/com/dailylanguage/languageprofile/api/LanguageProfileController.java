package com.dailylanguage.languageprofile.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dailylanguage.languageprofile.application.LanguageProfileCreationException;
import com.dailylanguage.languageprofile.application.LanguageProfileAccessService;
import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.security.domain.UserContext;

/**
 * Language Profile 的 HTTP 入口。资源归属只接受 Spring Security 建立的 UserContext，
 * request body、query parameter 或 header 中的 userId 都不参与授权判断。
 */
@RestController
@RequestMapping("/api/language-profiles")
public class LanguageProfileController {

    private final LanguageProfileAccessService languageProfileAccessService;

    public LanguageProfileController(LanguageProfileAccessService languageProfileAccessService) {
        this.languageProfileAccessService = languageProfileAccessService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> createLanguageProfile(
            @RequestBody CreateLanguageProfileRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true) UserContext userContext
    ) {
        try {
            LanguageProfileIdentity createdProfile =
                    languageProfileAccessService.createProfileForUser(request.languageCode(), userContext);
            return ResponseEntity.created(URI.create("/api/language-profiles/" + createdProfile.id()))
                    .body(createdProfile);
        }
        catch (LanguageProfileCreationException exception) {
            // 对外只暴露稳定的业务错误码，不返回 validation 或 persistence exception detail。
            HttpStatus status = switch (exception.failureReason()) {
                case INVALID_LANGUAGE_CODE -> HttpStatus.BAD_REQUEST;
                case LANGUAGE_PROFILE_ALREADY_EXISTS -> HttpStatus.CONFLICT;
            };
            return ResponseEntity.status(status)
                    .body(new LanguageProfileErrorResponse(exception.failureReason().name()));
        }
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

    record CreateLanguageProfileRequest(String languageCode) {
    }

    record LanguageProfileErrorResponse(String code) {
    }
}
