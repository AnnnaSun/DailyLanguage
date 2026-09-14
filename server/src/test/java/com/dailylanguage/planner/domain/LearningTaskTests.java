package com.dailylanguage.planner.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.content.domain.MaterialIdentity;

class LearningTaskTests {

    private static final OffsetDateTime CREATED_AT = OffsetDateTime.now();
    private static final String RECOMMENDATION_REASON =
            "今天在咖啡馆练习点单，并追问今日特供。";
    private static final String MAXIMUM_REASON = "x".repeat(239) + "\uD835\uDD4A";

    @Test
    void acceptsSnapshotForEachLifecycleStatus() {
        assertThatCode(() -> task(LearningTask.Status.PLANNED, Optional.empty(), Optional.empty()))
                .doesNotThrowAnyException();
        assertThatCode(() -> task(
                LearningTask.Status.STARTED,
                Optional.of(CREATED_AT.plusSeconds(1)),
                Optional.empty()))
                .doesNotThrowAnyException();
        assertThatCode(() -> task(
                LearningTask.Status.COMPLETED,
                Optional.of(CREATED_AT.plusSeconds(1)),
                Optional.of(CREATED_AT.plusSeconds(2))))
                .doesNotThrowAnyException();
    }

    @Test
    void deterministicSnapshotCarriesEmptyRecommendationReason() {
        LearningTask task = task(LearningTask.Status.PLANNED, Optional.empty(), Optional.empty());

        assertThat(task.planningReason())
                .isEqualTo(LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK);
        assertThat(task.recommendationReason()).isEmpty();
    }

    @Test
    void legacyConstructorDefaultsToEmptyRecommendationReason() {
        LearningTask legacySnapshot = new LearningTask(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new MaterialIdentity("builtin:text-practice/morning-routine", "2026.03.1"),
                "en", "zh", MaterialDifficulty.FOUNDATION, 7,
                "Ordering breakfast at a café",
                "Ask the staff a follow-up question about today's specials",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK,
                LearningTask.Status.PLANNED, CREATED_AT, Optional.empty(), Optional.empty());

        assertThat(legacySnapshot.planningReason())
                .isEqualTo(LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK);
        assertThat(legacySnapshot.recommendationReason()).isEmpty();
    }

    @Test
    void acceptsModelEnrichedSnapshotForEachLifecycleStatus() {
        assertThatCode(() -> enrichedTask(
                LearningTask.Status.PLANNED, Optional.empty(), Optional.empty()))
                .doesNotThrowAnyException();
        assertThatCode(() -> enrichedTask(
                LearningTask.Status.STARTED,
                Optional.of(CREATED_AT.plusSeconds(1)),
                Optional.empty()))
                .doesNotThrowAnyException();
        assertThatCode(() -> enrichedTask(
                LearningTask.Status.COMPLETED,
                Optional.of(CREATED_AT.plusSeconds(1)),
                Optional.of(CREATED_AT.plusSeconds(2))))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsMaximumBoundaryRecommendationReason() {
        // 239 个 ASCII + 1 个 astral 字符：240 个 code points、241 个 UTF-16 chars。
        assertThat(MAXIMUM_REASON.codePointCount(0, MAXIMUM_REASON.length()))
                .isEqualTo(LearningTaskPlan.MAXIMUM_RECOMMENDATION_REASON_CODE_POINTS);
        assertThatCode(() -> enrichedTaskWithReason(MAXIMUM_REASON)).doesNotThrowAnyException();
    }

    @Test
    void rejectsReasonPairingViolations() {
        assertThatThrownBy(() -> taskWithReasons(
                LearningTaskPlan.PlanningReason.MODEL_ENRICHED, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("recommendationReason must be present while reason is MODEL_ENRICHED");
        assertThatThrownBy(() -> taskWithReasons(
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK,
                Optional.of(RECOMMENDATION_REASON)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "recommendationReason must be empty while reason is DETERMINISTIC_BUILT_IN_FALLBACK");
    }

    @Test
    void rejectsInvalidRecommendationReasonText() {
        assertThatThrownBy(() -> enrichedTaskWithReason("cafe\u0301"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("recommendationReason must be NFC normalized");
        assertThatThrownBy(() -> enrichedTaskWithReason(" " + RECOMMENDATION_REASON))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "recommendationReason must not be blank or contain surrounding whitespace");
        assertThatThrownBy(() -> enrichedTaskWithReason(RECOMMENDATION_REASON + "\t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "recommendationReason must not be blank or contain surrounding whitespace");
        assertThatThrownBy(() -> enrichedTaskWithReason(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "recommendationReason must not be blank or contain surrounding whitespace");
        assertThatThrownBy(() -> enrichedTaskWithReason("today\nspecials"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "recommendationReason must not contain line-break or control characters");
        assertThatThrownBy(() -> enrichedTaskWithReason("today\u2028specials"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "recommendationReason must not contain line-break or control characters");
        assertThatThrownBy(() -> enrichedTaskWithReason("x".repeat(241)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "recommendationReason must not exceed 240 Unicode code points");
    }

    @Nested
    class LearningTaskPlanContract {

        @Test
        void deterministicPlanUsesLegacyConstructorWithEmptyReason() {
            LearningTaskPlan plan = planWithReasons(
                    LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK, Optional.empty());

            assertThat(plan.reason())
                    .isEqualTo(LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK);
            assertThat(plan.recommendationReason()).isEmpty();
        }

        @Test
        void acceptsModelEnrichedPlanWithBoundedReason() {
            LearningTaskPlan plan = planWithReasons(
                    LearningTaskPlan.PlanningReason.MODEL_ENRICHED,
                    Optional.of(MAXIMUM_REASON));

            assertThat(plan.reason()).isEqualTo(LearningTaskPlan.PlanningReason.MODEL_ENRICHED);
            assertThat(plan.recommendationReason()).contains(MAXIMUM_REASON);
        }

        @Test
        void rejectsReasonPairingViolations() {
            assertThatThrownBy(() -> planWithReasons(
                    LearningTaskPlan.PlanningReason.MODEL_ENRICHED, Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("recommendationReason must be present while reason is MODEL_ENRICHED");
            assertThatThrownBy(() -> planWithReasons(
                    LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK,
                    Optional.of(RECOMMENDATION_REASON)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(
                            "recommendationReason must be empty while reason is DETERMINISTIC_BUILT_IN_FALLBACK");
        }

        @Test
        void rejectsInvalidRecommendationReasonText() {
            assertThatThrownBy(() -> planWithReasons(
                    LearningTaskPlan.PlanningReason.MODEL_ENRICHED,
                    Optional.of("cafe\u0301")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("recommendationReason must be NFC normalized");
            assertThatThrownBy(() -> planWithReasons(
                    LearningTaskPlan.PlanningReason.MODEL_ENRICHED,
                    Optional.of("x".repeat(241))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(
                            "recommendationReason must not exceed 240 Unicode code points");
        }
    }

    @Test
    void rejectsDurationOutsideDeterministicPlannerRange() {
        assertThatThrownBy(() -> taskWithDuration(4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("estimatedDurationMinutes must be between 5 and 10");
        assertThatThrownBy(() -> taskWithDuration(11))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("estimatedDurationMinutes must be between 5 and 10");
    }

    @Test
    void rejectsBlankOrSurroundingWhitespaceText() {
        assertThatThrownBy(() -> taskWithMaterialId("builtin:morning-routine "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("materialIdentity.materialId must not be blank or contain surrounding whitespace");
        assertThatThrownBy(() -> taskWithPublishedVersion("\t2026.03.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("materialIdentity.publishedVersion must not be blank or contain surrounding whitespace");
        assertThatThrownBy(() -> taskWithScenario(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scenario must not be blank or contain surrounding whitespace");
        assertThatThrownBy(() -> taskWithPrimaryGoal(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("primaryGoal must not be blank or contain surrounding whitespace");
        assertThatThrownBy(() -> taskWithSupportLanguage(" zh "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("supportLanguage must not be blank or contain surrounding whitespace");
    }

    @Test
    void rejectsUppercaseSupportLanguage() {
        assertThatThrownBy(() -> taskWithSupportLanguage("ZH"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("supportLanguage must be lowercase");
    }

    @Test
    void rejectsLifecycleTimestampsThatDoNotMatchStatus() {
        assertThatThrownBy(() -> task(
                LearningTask.Status.PLANNED,
                Optional.of(CREATED_AT.plusSeconds(1)),
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("startedAt must be empty while status is PLANNED");
        assertThatThrownBy(() -> task(
                LearningTask.Status.PLANNED,
                Optional.empty(),
                Optional.of(CREATED_AT.plusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("completedAt must be empty while status is PLANNED");
        assertThatThrownBy(() -> task(
                LearningTask.Status.STARTED,
                Optional.empty(),
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("startedAt must be present while status is STARTED");
        assertThatThrownBy(() -> task(
                LearningTask.Status.STARTED,
                Optional.of(CREATED_AT.plusSeconds(1)),
                Optional.of(CREATED_AT.plusSeconds(2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("completedAt must be empty while status is STARTED");
        assertThatThrownBy(() -> task(
                LearningTask.Status.COMPLETED,
                Optional.of(CREATED_AT.plusSeconds(1)),
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("completedAt must be present while status is COMPLETED");
        assertThatThrownBy(() -> task(
                LearningTask.Status.COMPLETED,
                Optional.empty(),
                Optional.of(CREATED_AT.plusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("startedAt must be present while status is COMPLETED");
    }

    @Test
    void rejectsOutOfOrderLifecycleTimestamps() {
        assertThatThrownBy(() -> task(
                LearningTask.Status.STARTED,
                Optional.of(CREATED_AT.minusSeconds(1)),
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("startedAt must not be before createdAt");
        assertThatThrownBy(() -> task(
                LearningTask.Status.COMPLETED,
                Optional.of(CREATED_AT.plusSeconds(2)),
                Optional.of(CREATED_AT.plusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("completedAt must not be before startedAt");
    }

    @Test
    void rejectsMissingDurableIdentity() {
        assertThatThrownBy(() -> taskWithId(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("id must not be null");
        assertThatThrownBy(() -> taskWithUserId(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("userId must not be null");
        assertThatThrownBy(() -> taskWithLanguageProfileId(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("languageProfileId must not be null");
    }

    private static LearningTaskPlan planWithReasons(
            LearningTaskPlan.PlanningReason reason, Optional<String> recommendationReason) {
        return new LearningTaskPlan(
                UUID.randomUUID(),
                new MaterialIdentity("builtin:text-practice/morning-routine", "2026.03.1"),
                "en",
                "zh",
                MaterialDifficulty.FOUNDATION,
                7,
                "Ordering breakfast at a café",
                "Ask the staff a follow-up question about today's specials",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                reason,
                recommendationReason);
    }

    private static LearningTask task(
            LearningTask.Status status,
            Optional<OffsetDateTime> startedAt,
            Optional<OffsetDateTime> completedAt) {
        return task(status, startedAt, completedAt, 7, "en", "zh",
                "builtin:text-practice/morning-routine", "2026.03.1",
                "Ordering breakfast at a café",
                "Ask the staff a follow-up question about today's specials");
    }

    private static LearningTask enrichedTask(
            LearningTask.Status status,
            Optional<OffsetDateTime> startedAt,
            Optional<OffsetDateTime> completedAt) {
        return new LearningTask(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new MaterialIdentity("builtin:text-practice/morning-routine", "2026.03.1"),
                "en", "zh", MaterialDifficulty.FOUNDATION, 7,
                "Ordering breakfast at a café",
                "Ask the staff a follow-up question about today's specials",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.MODEL_ENRICHED,
                Optional.of(RECOMMENDATION_REASON),
                status, CREATED_AT, startedAt, completedAt);
    }

    private static LearningTask enrichedTaskWithReason(String recommendationReason) {
        return new LearningTask(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new MaterialIdentity("builtin:text-practice/morning-routine", "2026.03.1"),
                "en", "zh", MaterialDifficulty.FOUNDATION, 7,
                "Ordering breakfast at a café",
                "Ask the staff a follow-up question about today's specials",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.MODEL_ENRICHED,
                Optional.of(recommendationReason),
                LearningTask.Status.PLANNED, CREATED_AT, Optional.empty(), Optional.empty());
    }

    private static LearningTask taskWithReasons(
            LearningTaskPlan.PlanningReason planningReason, Optional<String> recommendationReason) {
        return new LearningTask(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new MaterialIdentity("builtin:text-practice/morning-routine", "2026.03.1"),
                "en", "zh", MaterialDifficulty.FOUNDATION, 7,
                "Ordering breakfast at a café",
                "Ask the staff a follow-up question about today's specials",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                planningReason,
                recommendationReason,
                LearningTask.Status.PLANNED, CREATED_AT, Optional.empty(), Optional.empty());
    }

    private static LearningTask taskWithDuration(int estimatedDurationMinutes) {
        return task(LearningTask.Status.PLANNED, Optional.empty(), Optional.empty(),
                estimatedDurationMinutes, "en", "zh",
                "builtin:text-practice/morning-routine", "2026.03.1",
                "Ordering breakfast at a café",
                "Ask the staff a follow-up question about today's specials");
    }

    private static LearningTask taskWithSupportLanguage(String supportLanguage) {
        return task(LearningTask.Status.PLANNED, Optional.empty(), Optional.empty(),
                7, "en", supportLanguage,
                "builtin:text-practice/morning-routine", "2026.03.1",
                "Ordering breakfast at a café",
                "Ask the staff a follow-up question about today's specials");
    }

    private static LearningTask taskWithMaterialId(String materialId) {
        return task(LearningTask.Status.PLANNED, Optional.empty(), Optional.empty(),
                7, "en", "zh", materialId, "2026.03.1",
                "Ordering breakfast at a café",
                "Ask the staff a follow-up question about today's specials");
    }

    private static LearningTask taskWithPublishedVersion(String publishedVersion) {
        return task(LearningTask.Status.PLANNED, Optional.empty(), Optional.empty(),
                7, "en", "zh",
                "builtin:text-practice/morning-routine", publishedVersion,
                "Ordering breakfast at a café",
                "Ask the staff a follow-up question about today's specials");
    }

    private static LearningTask taskWithScenario(String scenario) {
        return task(LearningTask.Status.PLANNED, Optional.empty(), Optional.empty(),
                7, "en", "zh",
                "builtin:text-practice/morning-routine", "2026.03.1",
                scenario,
                "Ask the staff a follow-up question about today's specials");
    }

    private static LearningTask taskWithPrimaryGoal(String primaryGoal) {
        return task(LearningTask.Status.PLANNED, Optional.empty(), Optional.empty(),
                7, "en", "zh",
                "builtin:text-practice/morning-routine", "2026.03.1",
                "Ordering breakfast at a café",
                primaryGoal);
    }

    private static LearningTask taskWithId(UUID id) {
        return new LearningTask(
                id, UUID.randomUUID(), UUID.randomUUID(),
                new MaterialIdentity("builtin:text-practice/morning-routine", "2026.03.1"),
                "en", "zh", MaterialDifficulty.FOUNDATION, 7,
                "Ordering breakfast at a café",
                "Ask the staff a follow-up question about today's specials",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK,
                LearningTask.Status.PLANNED, CREATED_AT, Optional.empty(), Optional.empty());
    }

    private static LearningTask taskWithUserId(UUID userId) {
        return new LearningTask(
                UUID.randomUUID(), userId, UUID.randomUUID(),
                new MaterialIdentity("builtin:text-practice/morning-routine", "2026.03.1"),
                "en", "zh", MaterialDifficulty.FOUNDATION, 7,
                "Ordering breakfast at a café",
                "Ask the staff a follow-up question about today's specials",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK,
                LearningTask.Status.PLANNED, CREATED_AT, Optional.empty(), Optional.empty());
    }

    private static LearningTask taskWithLanguageProfileId(UUID languageProfileId) {
        return new LearningTask(
                UUID.randomUUID(), UUID.randomUUID(), languageProfileId,
                new MaterialIdentity("builtin:text-practice/morning-routine", "2026.03.1"),
                "en", "zh", MaterialDifficulty.FOUNDATION, 7,
                "Ordering breakfast at a café",
                "Ask the staff a follow-up question about today's specials",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK,
                LearningTask.Status.PLANNED, CREATED_AT, Optional.empty(), Optional.empty());
    }

    private static LearningTask task(
            LearningTask.Status status,
            Optional<OffsetDateTime> startedAt,
            Optional<OffsetDateTime> completedAt,
            int estimatedDurationMinutes,
            String targetLanguage,
            String supportLanguage,
            String materialId,
            String publishedVersion,
            String scenario,
            String primaryGoal) {
        return new LearningTask(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new MaterialIdentity(materialId, publishedVersion),
                targetLanguage, supportLanguage, MaterialDifficulty.FOUNDATION,
                estimatedDurationMinutes, scenario, primaryGoal,
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK,
                status, CREATED_AT, startedAt, completedAt);
    }
}
