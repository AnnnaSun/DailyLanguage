package com.dailylanguage.planner.domain;

import java.util.Objects;
import java.util.UUID;

import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.content.domain.MaterialIdentity;

/**
 * Planner 已通过 Java hard constraints 产生、但尚未持久化的任务计划。taskId、status 与 lifecycle
 * 由 M1-S3 的 durable LearningTask 负责，避免 S2 提前拥有 persistence authority。
 */
public record LearningTaskPlan(
        UUID languageProfileId,
        MaterialIdentity materialIdentity,
        String targetLanguage,
        String supportLanguage,
        MaterialDifficulty difficulty,
        int estimatedDurationMinutes,
        String scenario,
        String primaryGoal,
        TaskType taskType,
        PlanningReason reason) {

    public LearningTaskPlan {
        Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
        Objects.requireNonNull(materialIdentity, "materialIdentity must not be null");
        requireText(materialIdentity.materialId(), "materialIdentity.materialId");
        requireText(materialIdentity.publishedVersion(), "materialIdentity.publishedVersion");
        requireText(targetLanguage, "targetLanguage");
        requireText(supportLanguage, "supportLanguage");
        Objects.requireNonNull(difficulty, "difficulty must not be null");
        if (estimatedDurationMinutes <= 0) {
            throw new IllegalArgumentException("estimatedDurationMinutes must be positive");
        }
        requireText(scenario, "scenario");
        requireText(primaryGoal, "primaryGoal");
        Objects.requireNonNull(taskType, "taskType must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    public enum TaskType {
        TEXT_PRACTICE
    }

    public enum PlanningReason {
        DETERMINISTIC_BUILT_IN_FALLBACK
    }
}
