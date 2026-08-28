package com.dailylanguage.languageprofile.domain;

import java.util.UUID;

public record LanguageProfileIdentity(UUID id, UUID userId, String languageCode) {
}
