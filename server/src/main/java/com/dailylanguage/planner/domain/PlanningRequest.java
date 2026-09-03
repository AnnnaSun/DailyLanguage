package com.dailylanguage.planner.domain;

import java.util.Objects;
import java.util.Set;

import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;

/**
 * Deterministic Planner 的输入。LanguageProfile 必须由未来 owner-scoped Application flow 先完成权限校验；
 * 本类型只携带规划所需的稳定 identity 与 hard constraints，不代表 authorization proof。
 */
public record PlanningRequest(
        LanguageProfileIdentity languageProfile,
        String supportLanguage,
        MaterialDifficulty requestedDifficulty,
        int availableMinutes,
        Set<MaterialIdentity> excludedMaterials) {

    public PlanningRequest {
        Objects.requireNonNull(languageProfile, "languageProfile must not be null");
        Objects.requireNonNull(languageProfile.id(), "languageProfile.id must not be null");
        Objects.requireNonNull(languageProfile.userId(), "languageProfile.userId must not be null");
        requireText(languageProfile.languageCode(), "languageProfile.languageCode");
        requireText(supportLanguage, "supportLanguage");
        Objects.requireNonNull(requestedDifficulty, "requestedDifficulty must not be null");
        if (availableMinutes <= 0) {
            throw new IllegalArgumentException("availableMinutes must be positive");
        }
        excludedMaterials = Set.copyOf(Objects.requireNonNull(
                excludedMaterials, "excludedMaterials must not be null"));
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
