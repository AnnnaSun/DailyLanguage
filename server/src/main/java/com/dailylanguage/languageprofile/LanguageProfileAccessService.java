package com.dailylanguage.languageprofile;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.dailylanguage.security.UserContext;

@Service
public class LanguageProfileAccessService {

    private final LanguageProfileRepository languageProfileRepository;

    public LanguageProfileAccessService(LanguageProfileRepository languageProfileRepository) {
        this.languageProfileRepository = languageProfileRepository;
    }

    public Optional<LanguageProfileIdentity> findOwnedBy(
            UUID languageProfileId,
            UserContext userContext
    ) {
        Objects.requireNonNull(userContext, "userContext must not be null");
        return languageProfileRepository.findByIdAndUserId(languageProfileId, userContext.userId());
    }
}
