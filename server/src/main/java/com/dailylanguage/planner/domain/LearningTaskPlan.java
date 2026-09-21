package com.dailylanguage.planner.domain;

import java.text.Normalizer;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.content.domain.MaterialIdentity;

/**
 * Planner 已通过 Java hard constraints 产生、但尚未持久化的任务计划。taskId、status 与 lifecycle
 * 由 M1-S3 的 durable LearningTask 负责，避免 S2 提前拥有 persistence authority。
 * MODEL_ENRICHED plan 必须携带一条已归一化（NFC + strip）的 bounded recommendationReason；
 * pairing 与文本边界在构造期 fail closed，供 LearningTask 快照复用同一裁决。
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
        PlanningReason reason,
        Optional<String> recommendationReason) {

    /** present recommendationReason 的 Unicode code point 上限；与 S9B enrichment validator 的 240 对齐。 */
    public static final int MAXIMUM_RECOMMENDATION_REASON_CODE_POINTS = 240;

    /**
     * deterministic planning 的 legacy 构造入口：recommendationReason 固定为 empty。保留旧签名使
     * 既有 deterministic planner 调用方无需随本 slice 改动；MODEL_ENRICHED 必须走 canonical 构造器。
     */
    public LearningTaskPlan(
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
        this(languageProfileId, materialIdentity, targetLanguage, supportLanguage, difficulty,
                estimatedDurationMinutes, scenario, primaryGoal, taskType, reason, Optional.empty());
    }

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
        Objects.requireNonNull(recommendationReason, "recommendationReason must not be null");
        requireReasonPairing(reason, recommendationReason);
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    /** planningReason 与 recommendationReason 的封闭配对；非法组合在构造期 fail closed。 */
    static void requireReasonPairing(PlanningReason reason, Optional<String> recommendationReason) {
        switch (reason) {
            case DETERMINISTIC_BUILT_IN_FALLBACK -> {
                if (recommendationReason.isPresent()) {
                    throw new IllegalArgumentException(
                            "recommendationReason must be empty while reason is DETERMINISTIC_BUILT_IN_FALLBACK");
                }
            }
            case MODEL_ENRICHED -> {
                if (recommendationReason.isEmpty()) {
                    throw new IllegalArgumentException(
                            "recommendationReason must be present while reason is MODEL_ENRICHED");
                }
                requireValidRecommendationReason(recommendationReason.orElseThrow());
            }
        }
    }

    /**
     * present reason 只接受上游（S9B validator）已归一化为 NFC + strip 的文本；本方法不再次归一化，
     * 任何未归一化或越界输入都按 programming error 拒绝，而不是静默修复。
     */
    static void requireValidRecommendationReason(String recommendationReason) {
        if (!Normalizer.isNormalized(recommendationReason, Normalizer.Form.NFC)) {
            throw new IllegalArgumentException("recommendationReason must be NFC normalized");
        }
        if (recommendationReason.isBlank() || !recommendationReason.equals(recommendationReason.strip())) {
            throw new IllegalArgumentException(
                    "recommendationReason must not be blank or contain surrounding whitespace");
        }
        if (containsLineBreakOrControl(recommendationReason)) {
            throw new IllegalArgumentException(
                    "recommendationReason must not contain line-break or control characters");
        }
        if (recommendationReason.codePointCount(0, recommendationReason.length())
                > MAXIMUM_RECOMMENDATION_REASON_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "recommendationReason must not exceed "
                            + MAXIMUM_RECOMMENDATION_REASON_CODE_POINTS + " Unicode code points");
        }
    }

    /** 覆盖 ISO control（含 \n、\r、\t）与 Unicode line separator / paragraph separator。 */
    private static boolean containsLineBreakOrControl(String text) {
        return text.codePoints().anyMatch(codePoint ->
                Character.isISOControl(codePoint) || codePoint == '\u2028' || codePoint == '\u2029');
    }

    public enum TaskType {
        TEXT_PRACTICE
    }

    public enum PlanningReason {
        DETERMINISTIC_BUILT_IN_FALLBACK, MODEL_ENRICHED
    }
}
