package com.dailylanguage.languageprofile.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.languageprofile.infrastructure.LanguageProfileRepository;
import com.dailylanguage.security.domain.UserContext;

@Service
public class LanguageProfileAccessService {

    private final LanguageProfileRepository languageProfileRepository;

    public LanguageProfileAccessService(LanguageProfileRepository languageProfileRepository) {
        this.languageProfileRepository = languageProfileRepository;
    }

    public List<LanguageProfileIdentity> listProfilesOwnedByUser(UserContext userContext) {
        Objects.requireNonNull(userContext, "userContext must not be null");
        return languageProfileRepository.listByUserId(userContext.userId());
    }

    public Optional<LanguageProfileIdentity> findProfileOwnedByUser(
            UUID languageProfileId,
            UserContext userContext
    ) {
        Objects.requireNonNull(userContext, "userContext must not be null");
        return languageProfileRepository.findByIdAndUserId(languageProfileId, userContext.userId());
    }
}
