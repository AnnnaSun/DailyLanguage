package com.dailylanguage.languageprofile.infrastructure;

import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;

@Repository
public class LanguageProfileRepository {

    private static final int MAX_LANGUAGE_CODE_LENGTH = 35;

    private final LanguageProfileMapper languageProfileMapper;

    public LanguageProfileRepository(LanguageProfileMapper languageProfileMapper) {
        this.languageProfileMapper = languageProfileMapper;
    }

    public Optional<LanguageProfileIdentity> create(UUID userId, String languageCode) {
        Objects.requireNonNull(userId, "userId must not be null");
        String normalizedLanguageCode = normalizeLanguageCode(languageCode);

        return Optional.ofNullable(
                languageProfileMapper.insertLanguageProfileAndReturn(userId, normalizedLanguageCode));
    }

    public Optional<LanguageProfileIdentity> findByIdAndUserId(UUID languageProfileId, UUID userId) {
        Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        return languageProfileMapper.findByIdAndUserId(languageProfileId, userId);
    }

    public List<LanguageProfileIdentity> listByUserId(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        return languageProfileMapper.findAllByUserId(userId);
    }

    private static String normalizeLanguageCode(String languageCode) {
        // languageCode 参与 workspace 唯一性判断，入库前必须收敛为稳定的 BCP 47 lowercase 表示。
        if (languageCode == null) {
            throw new IllegalArgumentException("languageCode must not be null");
        }
        String trimmedLanguageCode = languageCode.strip();
        if (trimmedLanguageCode.isEmpty() || trimmedLanguageCode.length() > MAX_LANGUAGE_CODE_LENGTH) {
            throw new IllegalArgumentException("languageCode must contain between 1 and 35 characters");
        }

        try {
            return new Locale.Builder()
                    .setLanguageTag(trimmedLanguageCode)
                    .build()
                    .toLanguageTag()
                    .toLowerCase(Locale.ROOT);
        } catch (IllformedLocaleException exception) {
            throw new IllegalArgumentException("languageCode must be a well-formed BCP 47 language tag", exception);
        }
    }
}
