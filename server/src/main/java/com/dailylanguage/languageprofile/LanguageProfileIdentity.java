package com.dailylanguage.languageprofile;

import java.util.UUID;

public record LanguageProfileIdentity(UUID id, UUID userId, String languageCode) {
}
