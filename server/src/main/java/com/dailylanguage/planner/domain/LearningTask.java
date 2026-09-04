package com.dailylanguage.planner.domain;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.content.domain.MaterialIdentity;

/**
 * 已持久化 LearningTask 的 durable 快照。PostgreSQL 是 id、status 与 lifecycle timestamp 的
 * authority；本类型只还原数据库已裁决的行，不提供任何 transition 操作。target language 不在
 * learning_task 中重复存储，读取时通过 language_profile.language_code 还原。
 */
public record LearningTask(
        UUID id,
        UUID userId,
        UUID languageProfileId,
        MaterialIdentity materialIdentity,
        String targetLanguage,
        String supportLanguage,
        MaterialDifficulty difficulty,
        int estimatedDurationMinutes,
        String scenario,
        String primaryGoal,
        LearningTaskPlan.TaskType taskType,
        LearningTaskPlan.PlanningReason planningReason,
        Status status,
        OffsetDateTime createdAt,
        Optional<OffsetDateTime> startedAt,
        Optional<OffsetDateTime> completedAt) {

    static final int MINIMUM_ESTIMATED_DURATION_MINUTES = 5;
    static final int MAXIMUM_ESTIMATED_DURATION_MINUTES = 10;

    public LearningTask {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
        Objects.requireNonNull(materialIdentity, "materialIdentity must not be null");
        requireTrimmedText(materialIdentity.materialId(), "materialIdentity.materialId");
        requireTrimmedText(materialIdentity.publishedVersion(), "materialIdentity.publishedVersion");
        requireTrimmedText(targetLanguage, "targetLanguage");
        requireTrimmedText(supportLanguage, "supportLanguage");
        if (!supportLanguage.equals(supportLanguage.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("supportLanguage must be lowercase");
        }
        Objects.requireNonNull(difficulty, "difficulty must not be null");
        if (estimatedDurationMinutes < MINIMUM_ESTIMATED_DURATION_MINUTES
                || estimatedDurationMinutes > MAXIMUM_ESTIMATED_DURATION_MINUTES) {
            throw new IllegalArgumentException(
                    "estimatedDurationMinutes must be between "
                            + MINIMUM_ESTIMATED_DURATION_MINUTES + " and "
                            + MAXIMUM_ESTIMATED_DURATION_MINUTES);
        }
        requireTrimmedText(scenario, "scenario");
        requireTrimmedText(primaryGoal, "primaryGoal");
        Objects.requireNonNull(taskType, "taskType must not be null");
        Objects.requireNonNull(planningReason, "planningReason must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        validateLifecycle(status, createdAt, startedAt, completedAt);
    }

    private static void requireTrimmedText(String value, String fieldName) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(
                    fieldName + " must not be blank or contain surrounding whitespace");
        }
    }

    private static void validateLifecycle(
            Status status,
            OffsetDateTime createdAt,
            Optional<OffsetDateTime> startedAt,
            Optional<OffsetDateTime> completedAt) {
        switch (status) {
            case PLANNED -> {
                requireAbsent(startedAt, "startedAt", status);
                requireAbsent(completedAt, "completedAt", status);
            }
            case STARTED -> {
                requirePresent(startedAt, "startedAt", status);
                requireAbsent(completedAt, "completedAt", status);
                requireNotBefore(startedAt.orElseThrow(), createdAt, "startedAt", "createdAt");
            }
            case COMPLETED -> {
                requirePresent(startedAt, "startedAt", status);
                requirePresent(completedAt, "completedAt", status);
                requireNotBefore(startedAt.orElseThrow(), createdAt, "startedAt", "createdAt");
                requireNotBefore(completedAt.orElseThrow(), startedAt.orElseThrow(), "completedAt", "startedAt");
            }
        }
    }

    private static void requirePresent(Optional<OffsetDateTime> value, String fieldName, Status status) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must be present while status is " + status);
        }
    }

    private static void requireAbsent(Optional<OffsetDateTime> value, String fieldName, Status status) {
        if (value.isPresent()) {
            throw new IllegalArgumentException(fieldName + " must be empty while status is " + status);
        }
    }

    private static void requireNotBefore(
            OffsetDateTime value, OffsetDateTime floor, String fieldName, String floorName) {
        if (value.isBefore(floor)) {
            throw new IllegalArgumentException(fieldName + " must not be before " + floorName);
        }
    }

    public enum Status {
        PLANNED, STARTED, COMPLETED
    }
}
