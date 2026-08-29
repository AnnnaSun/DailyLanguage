package com.dailylanguage.languageprofile.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.languageprofile.infrastructure.LanguageProfileRepository;
import com.dailylanguage.security.domain.UserContext;

import static com.dailylanguage.languageprofile.application.LanguageProfileCreationException.FailureReason.INVALID_LANGUAGE_CODE;
import static com.dailylanguage.languageprofile.application.LanguageProfileCreationException.FailureReason.LANGUAGE_PROFILE_ALREADY_EXISTS;

@Service
public class LanguageProfileAccessService {

    private final LanguageProfileRepository languageProfileRepository;

    public LanguageProfileAccessService(LanguageProfileRepository languageProfileRepository) {
        this.languageProfileRepository = languageProfileRepository;
    }

    public LanguageProfileIdentity createProfileForUser(String languageCode, UserContext userContext) {
        Objects.requireNonNull(userContext, "userContext must not be null");

        try {
            return languageProfileRepository.create(userContext.userId(), languageCode)
                    .orElseThrow(() -> new LanguageProfileCreationException(
                            LANGUAGE_PROFILE_ALREADY_EXISTS));
        }
        catch (IllegalArgumentException exception) {
            throw new LanguageProfileCreationException(INVALID_LANGUAGE_CODE);
        }
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
