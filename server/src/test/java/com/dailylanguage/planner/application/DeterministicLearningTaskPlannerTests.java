package com.dailylanguage.planner.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.dailylanguage.content.domain.AvailableMaterialSummary;
import com.dailylanguage.content.domain.LearningMaterialCatalog;
import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.content.domain.MaterialQueryResult;
import com.dailylanguage.content.domain.MaterialSourceLineage;
import com.dailylanguage.content.domain.MaterialUnavailableReason;
import com.dailylanguage.content.domain.PublishedLearningMaterial;
import com.dailylanguage.content.domain.SupportScaffold;
import com.dailylanguage.content.domain.TargetPracticeCore;
import com.dailylanguage.content.infrastructure.BuiltInLearningMaterialCatalog;
import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.planner.domain.PlanningRequest;
import com.dailylanguage.planner.domain.PlanningResult;

import static com.dailylanguage.content.domain.MaterialDifficulty.FOUNDATION;
import static com.dailylanguage.planner.domain.PlanningResult.UnavailableReason.AVAILABLE_TIME_TOO_SHORT;
import static com.dailylanguage.planner.domain.PlanningResult.UnavailableReason.NO_ELIGIBLE_MATERIAL;
import static com.dailylanguage.planner.domain.PlanningResult.UnavailableReason.SELECTED_MATERIAL_UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeterministicLearningTaskPlannerTests {

    private static final UUID USER_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000001");
    private static final UUID PROFILE_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000002");
    private static final LanguageProfileIdentity ENGLISH_PROFILE =
            new LanguageProfileIdentity(PROFILE_ID, USER_ID, "en");
    private static final MaterialIdentity CAFE = new MaterialIdentity("en-builtin-cafe-request", "v1");
    private static final MaterialIdentity GREETING = new MaterialIdentity("en-builtin-greeting-intro", "v1");

    @Test
    void selectsFirstEligibleMaterialByStableIdentityOrderAndBuildsPlan() {
        FakeMaterialCatalog catalog = new FakeMaterialCatalog(List.of(
                summary(GREETING, "GREETING_INTRODUCTION"),
                summary(CAFE, "CAFE_SIMPLE_REQUEST")));
        LearningTaskPlanner planner = new DeterministicLearningTaskPlanner(catalog);

        PlanningResult result = planner.plan(request(10, Set.of()));

        assertThat(result).isInstanceOfSatisfying(PlanningResult.Planned.class, planned -> {
            LearningTaskPlan task = planned.task();
            assertThat(task.languageProfileId()).isEqualTo(PROFILE_ID);
            assertThat(task.materialIdentity()).isEqualTo(CAFE);
            assertThat(task.targetLanguage()).isEqualTo("en");
            assertThat(task.supportLanguage()).isEqualTo("zh-cn");
            assertThat(task.difficulty()).isEqualTo(FOUNDATION);
            assertThat(task.estimatedDurationMinutes()).isEqualTo(10);
            assertThat(task.scenario()).isEqualTo("CAFE_SIMPLE_REQUEST");
            assertThat(task.primaryGoal()).isEqualTo("Goal for CAFE_SIMPLE_REQUEST");
            assertThat(task.taskType()).isEqualTo(LearningTaskPlan.TaskType.TEXT_PRACTICE);
            assertThat(task.reason()).isEqualTo(
                    LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK);
        });
    }

    @Test
    void selectsNextStableCandidateWhenFirstMaterialIsExcluded() {
        FakeMaterialCatalog catalog = new FakeMaterialCatalog(List.of(
                summary(CAFE, "CAFE_SIMPLE_REQUEST"),
                summary(GREETING, "GREETING_INTRODUCTION")));
        LearningTaskPlanner planner = new DeterministicLearningTaskPlanner(catalog);

        PlanningResult result = planner.plan(request(10, Set.of(CAFE)));

        assertThat(result).isInstanceOfSatisfying(PlanningResult.Planned.class,
                planned -> assertThat(planned.task().materialIdentity()).isEqualTo(GREETING));
    }

    @ParameterizedTest
    @CsvSource({
            "5, 5",
            "7, 7",
            "10, 10",
            "30, 10"
    })
    void keepsPlannedDurationWithinApprovedFiveToTenMinuteWindow(
            int availableMinutes,
            int expectedDurationMinutes
    ) {
        LearningTaskPlanner planner = new DeterministicLearningTaskPlanner(
                new FakeMaterialCatalog(List.of(summary(CAFE, "CAFE_SIMPLE_REQUEST"))));

        PlanningResult result = planner.plan(request(availableMinutes, Set.of()));

        assertThat(result).isInstanceOfSatisfying(PlanningResult.Planned.class,
                planned -> assertThat(planned.task().estimatedDurationMinutes())
                        .isEqualTo(expectedDurationMinutes));
    }

    @Test
    void returnsUnavailableWithoutReadingCatalogWhenAvailableTimeIsTooShort() {
        FakeMaterialCatalog catalog = new FakeMaterialCatalog(List.of(summary(CAFE, "CAFE_SIMPLE_REQUEST")));
        LearningTaskPlanner planner = new DeterministicLearningTaskPlanner(catalog);

        PlanningResult result = planner.plan(request(4, Set.of()));

        assertUnavailable(result, AVAILABLE_TIME_TOO_SHORT);
        assertThat(catalog.listCalls).isZero();
    }

    @Test
    void returnsUnavailableWhenCatalogHasNoEligibleCandidate() {
        AvailableMaterialSummary wrongLanguage = new AvailableMaterialSummary(
                new MaterialIdentity("ja-builtin-clarify-repeat", "v1"),
                "ja",
                FOUNDATION,
                "CLARIFICATION",
                List.of("zh-cn"));
        LearningTaskPlanner planner = new DeterministicLearningTaskPlanner(
                new FakeMaterialCatalog(List.of(wrongLanguage)));

        PlanningResult result = planner.plan(request(10, Set.of()));

        assertUnavailable(result, NO_ELIGIBLE_MATERIAL);
    }

    @Test
    void rejectsMalformedCandidateIdentityBeforeStableOrdering() {
        AvailableMaterialSummary malformed = new AvailableMaterialSummary(
                new MaterialIdentity(null, "v1"),
                "en",
                FOUNDATION,
                "BROKEN",
                List.of("zh-cn"));
        LearningTaskPlanner planner = new DeterministicLearningTaskPlanner(
                new FakeMaterialCatalog(List.of(malformed)));

        PlanningResult result = planner.plan(request(10, Set.of()));

        assertUnavailable(result, NO_ELIGIBLE_MATERIAL);
    }

    @Test
    void doesNotFallbackAcrossLanguagePairsWithRealBuiltInCatalog() {
        LearningTaskPlanner planner = new DeterministicLearningTaskPlanner(
                new BuiltInLearningMaterialCatalog());
        PlanningRequest japaneseRequest = new PlanningRequest(
                new LanguageProfileIdentity(PROFILE_ID, USER_ID, "ja"),
                "zh-cn",
                FOUNDATION,
                10,
                Set.of());

        PlanningResult result = planner.plan(japaneseRequest);

        assertUnavailable(result, NO_ELIGIBLE_MATERIAL);
    }

    @Test
    void failsClosedWhenSelectedCandidateCannotBeResolved() {
        FakeMaterialCatalog catalog = new FakeMaterialCatalog(List.of(summary(CAFE, "CAFE_SIMPLE_REQUEST")));
        catalog.results.put(CAFE, new MaterialQueryResult.Unavailable(
                MaterialUnavailableReason.MATERIAL_NOT_PUBLISHED));
        LearningTaskPlanner planner = new DeterministicLearningTaskPlanner(catalog);

        PlanningResult result = planner.plan(request(10, Set.of()));

        assertUnavailable(result, SELECTED_MATERIAL_UNAVAILABLE);
    }

    @Test
    void failsClosedWhenResolvedMaterialViolatesRequestedLanguage() {
        FakeMaterialCatalog catalog = new FakeMaterialCatalog(List.of(summary(CAFE, "CAFE_SIMPLE_REQUEST")));
        catalog.results.put(CAFE, new MaterialQueryResult.Available(
                material(CAFE, "ja", "CAFE_SIMPLE_REQUEST"),
                scaffold("zh-cn")));
        LearningTaskPlanner planner = new DeterministicLearningTaskPlanner(catalog);

        PlanningResult result = planner.plan(request(10, Set.of()));

        assertUnavailable(result, SELECTED_MATERIAL_UNAVAILABLE);
    }

    @Test
    void failsClosedWhenResolvedScaffoldViolatesRequestedSupportLanguage(){
        FakeMaterialCatalog catalog = new FakeMaterialCatalog(List.of(summary(CAFE, "CAFE_SIMPLE_REQUEST")));
        catalog.results.put(CAFE, new MaterialQueryResult.Available(
                material(CAFE, "en", "CAFE_SIMPLE_REQUEST"),
                scaffold("ja")
        ));
        LearningTaskPlanner planner = new DeterministicLearningTaskPlanner(catalog);
        PlanningResult result = planner.plan(request(10, Set.of()));
        assertUnavailable(result, SELECTED_MATERIAL_UNAVAILABLE);
    }

    @Test
    void rejectsInvalidRequestValuesAtTheDomainBoundary() {
        assertThatThrownBy(() -> new PlanningRequest(
                ENGLISH_PROFILE, " ", FOUNDATION, 10, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supportLanguage");
        assertThatThrownBy(() -> new PlanningRequest(
                ENGLISH_PROFILE, "zh-cn", FOUNDATION, 0, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("availableMinutes");
        assertThatThrownBy(() -> new PlanningRequest(
                ENGLISH_PROFILE, "zh-cn", FOUNDATION, 10, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("excludedMaterials");
    }

    private static PlanningRequest request(int availableMinutes, Set<MaterialIdentity> excludedMaterials) {
        return new PlanningRequest(
                ENGLISH_PROFILE,
                "zh-cn",
                FOUNDATION,
                availableMinutes,
                excludedMaterials);
    }

    private static AvailableMaterialSummary summary(MaterialIdentity identity, String scenario) {
        return new AvailableMaterialSummary(identity, "en", FOUNDATION, scenario, List.of("zh-cn"));
    }

    private static PublishedLearningMaterial material(
            MaterialIdentity identity,
            String targetLanguage,
            String scenario
    ) {
        return new PublishedLearningMaterial(
                identity,
                new TargetPracticeCore(
                        targetLanguage,
                        MaterialDifficulty.FOUNDATION,
                        scenario,
                        "Goal for " + scenario,
                        "Target language text",
                        null,
                        List.of(),
                        "rubric/v1"),
                List.of(scaffold("zh-cn")),
                new MaterialSourceLineage("PROJECT_ORIGINAL", "1", "AGPL-3.0", "sha256:test"));
    }

    private static SupportScaffold scaffold(String supportLanguage) {
        return new SupportScaffold(supportLanguage, "instruction", "explanation", "hint", "note");
    }

    private static void assertUnavailable(
            PlanningResult result,
            PlanningResult.UnavailableReason expectedReason
    ) {
        assertThat(result).isEqualTo(new PlanningResult.Unavailable(expectedReason));
    }

    private static final class FakeMaterialCatalog implements LearningMaterialCatalog {

        private final List<AvailableMaterialSummary> summaries;
        private final Map<MaterialIdentity, MaterialQueryResult> results = new HashMap<>();
        private int listCalls;

        private FakeMaterialCatalog(List<AvailableMaterialSummary> summaries) {
            this.summaries = List.copyOf(summaries);
            for (AvailableMaterialSummary summary : summaries) {
                results.put(summary.identity(), new MaterialQueryResult.Available(
                        material(summary.identity(), summary.targetLanguage(), summary.scenario()),
                        scaffold("zh-cn")));
            }
        }

        @Override
        public MaterialQueryResult findByIdentity(MaterialIdentity identity, String supportLanguage) {
            return results.getOrDefault(identity, new MaterialQueryResult.Unavailable(
                    MaterialUnavailableReason.MATERIAL_NOT_PUBLISHED));
        }

        @Override
        public List<AvailableMaterialSummary> listAvailable(String targetLanguage, String supportLanguage) {
            listCalls++;
            return summaries;
        }
    }
}
