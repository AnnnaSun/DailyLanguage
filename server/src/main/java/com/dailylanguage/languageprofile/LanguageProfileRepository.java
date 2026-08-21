package com.dailylanguage.languageprofile;

import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

@Repository
public class LanguageProfileRepository {

    private static final int MAX_LANGUAGE_CODE_LENGTH = 35;

    private final LanguageProfileMapper languageProfileMapper;

    public LanguageProfileRepository(LanguageProfileMapper languageProfileMapper) {
        this.languageProfileMapper = languageProfileMapper;
    }

    public LanguageProfileIdentity create(UUID userId, String languageCode) {
        Objects.requireNonNull(userId, "userId must not be null");
        String normalizedLanguageCode = normalize(languageCode);

        return languageProfileMapper.insertReturning(userId, normalizedLanguageCode);
    }

    public Optional<LanguageProfileIdentity> findByIdAndUserId(UUID languageProfileId, UUID userId) {
        Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        return languageProfileMapper.findByIdAndUserId(languageProfileId, userId);
    }

    private static String normalize(String languageCode) {
        Objects.requireNonNull(languageCode, "languageCode must not be null");
        String candidate = languageCode.strip();
        if (candidate.isEmpty() || candidate.length() > MAX_LANGUAGE_CODE_LENGTH) {
            throw new IllegalArgumentException("languageCode must contain between 1 and 35 characters");
        }

        try {
            return new Locale.Builder()
                    .setLanguageTag(candidate)
                    .build()
                    .toLanguageTag()
                    .toLowerCase(Locale.ROOT);
        } catch (IllformedLocaleException exception) {
            throw new IllegalArgumentException("languageCode must be a well-formed BCP 47 language tag", exception);
        }
    }
}
