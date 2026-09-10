package com.dailylanguage.planner.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

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
import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.planner.domain.PlanningCandidateSet;
import com.dailylanguage.planner.domain.PlanningCandidateSetResult;
import com.dailylanguage.planner.domain.PlanningRequest;

import static com.dailylanguage.content.domain.MaterialDifficulty.FOUNDATION;
import static com.dailylanguage.planner.domain.PlanningResult.UnavailableReason.AVAILABLE_TIME_TOO_SHORT;
import static com.dailylanguage.planner.domain.PlanningResult.UnavailableReason.NO_ELIGIBLE_MATERIAL;
import static com.dailylanguage.planner.domain.PlanningResult.UnavailableReason.SELECTED_MATERIAL_UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EligibleLearningTaskCandidateReaderTests {

    private static final UUID USER_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000001");
    private static final UUID PROFILE_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000002");
    private static final MaterialIdentity CAFE = new MaterialIdentity("en-builtin-cafe-request", "v1");
    private static final MaterialIdentity GREETING = new MaterialIdentity("en-builtin-greeting-intro", "v1");

    @Test
    void returnsUnavailableWithoutReadingCatalogWhenAvailableTimeIsTooShort() {
        FakeMaterialCatalog catalog = new FakeMaterialCatalog(List.of(summary(CAFE, "CAFE_SIMPLE_REQUEST")));
        EligibleLearningTaskCandidateReader reader = new EligibleLearningTaskCandidateReader(catalog);

        PlanningCandidateSetResult result = reader.readCandidates(request(4, Set.of()));

        assertThat(result).isEqualTo(new PlanningCandidateSetResult.Unavailable(AVAILABLE_TIME_TOO_SHORT));
        assertThat(catalog.listCalls).isZero();
        assertThat(catalog.resolvedIdentities).isEmpty();
    }

    @Test
    void filtersWrongTargetLanguageUnsupportedLanguageAndExcludedIdentities() {
        AvailableMaterialSummary japaneseSummary = new AvailableMaterialSummary(
                new MaterialIdentity("ja-builtin-clarify-repeat", "v1"),
                "ja",
                FOUNDATION,
                "CLARIFICATION",
                List.of("zh-cn"));
        AvailableMaterialSummary unsupportedSupportLanguage = new AvailableMaterialSummary(
                new MaterialIdentity("en-builtin-fr-only", "v1"),
                "en",
                FOUNDATION,
                "FRENCH_ONLY",
                List.of("fr"));
        FakeMaterialCatalog catalog = new FakeMaterialCatalog(List.of(
                japaneseSummary,
                unsupportedSupportLanguage,
                summary(GREETING, "GREETING_INTRODUCTION"),
                summary(CAFE, "CAFE_SIMPLE_REQUEST")));
        EligibleLearningTaskCandidateReader reader = new EligibleLearningTaskCandidateReader(catalog);

        PlanningCandidateSetResult result = reader.readCandidates(request(10, Set.of(CAFE)));

        assertThat(result).isInstanceOfSatisfying(PlanningCandidateSetResult.Available.class, available -> {
            List<MaterialIdentity> identities = available.candidateSet().candidates().stream()
                    .map(LearningTaskPlan::materialIdentity)
                    .toList();
            assertThat(identities).containsExactly(GREETING);
        });
        assertThat(catalog.resolvedIdentities).containsExactly(GREETING);
    }

    @Test
    void filtersMalformedIdentitySummariesWithoutResolvingThem() {
        AvailableMaterialSummary malformed = new AvailableMaterialSummary(
                new MaterialIdentity(null, "v1"),
                "en",
                FOUNDATION,
                "BROKEN",
                List.of("zh-cn"));
        FakeMaterialCatalog catalog = new FakeMaterialCatalog(List.of(
                malformed,
                summary(CAFE, "CAFE_SIMPLE_REQUEST")));
        EligibleLearningTaskCandidateReader reader = new EligibleLearningTaskCandidateReader(catalog);

        PlanningCandidateSetResult result = reader.readCandidates(request(10, Set.of()));

        assertThat(result).isInstanceOfSatisfying(PlanningCandidateSetResult.Available.class, available ->
                assertThat(available.candidateSet().candidates())
                        .extracting(LearningTaskPlan::materialIdentity)
                        .containsExactly(CAFE));
        assertThat(catalog.resolvedIdentities).containsExactly(CAFE);
    }

    @Test
    void sortsShortlistByStableIdentityOrderRegardlessOfCatalogOrder() {
        FakeMaterialCatalog catalog = new FakeMaterialCatalog(List.of(
                summary(GREETING, "GREETING_INTRODUCTION"),
                summary(new MaterialIdentity("en-builtin-cafe-request", "v2"), "CAFE_SIMPLE_REQUEST"),
                summary(CAFE, "CAFE_SIMPLE_REQUEST")));
        EligibleLearningTaskCandidateReader reader = new EligibleLearningTaskCandidateReader(catalog);

        PlanningCandidateSetResult result = reader.readCandidates(request(10, Set.of()));

        assertThat(result).isInstanceOfSatisfying(PlanningCandidateSetResult.Available.class, available -> {
            PlanningCandidateSet candidateSet = available.candidateSet();
            assertThat(candidateSet.candidates())
                    .extracting(LearningTaskPlan::materialIdentity)
                    .containsExactly(
                            CAFE,
                            new MaterialIdentity("en-builtin-cafe-request", "v2"),
                            GREETING);
            assertThat(candidateSet.deterministicFallback().materialIdentity()).isEqualTo(CAFE);
        });
    }

    @Test
    void capsShortlistAtEightCandidatesAndResolvesOnlyShortlistedOnes() {
        FakeMaterialCatalog catalog = new FakeMaterialCatalog(reversedRangeSummaries(10));
        EligibleLearningTaskCandidateReader reader = new EligibleLearningTaskCandidateReader(catalog);

        PlanningCandidateSetResult result = reader.readCandidates(request(10, Set.of()));

        assertThat(result).isInstanceOfSatisfying(PlanningCandidateSetResult.Available.class, available ->
                assertThat(available.candidateSet().candidates())
                        .extracting(LearningTaskPlan::materialIdentity)
                        .containsExactlyElementsOf(expectedShortlistIdentities(8)));
        assertThat(catalog.resolvedIdentities)
                .containsExactlyElementsOf(expectedShortlistIdentities(8));
    }

    @Test
    void returnsNoEligibleMaterialWhenShortlistIsEmpty() {
        AvailableMaterialSummary wrongLanguage = new AvailableMaterialSummary(
                new MaterialIdentity("ja-builtin-clarify-repeat", "v1"),
                "ja",
                FOUNDATION,
                "CLARIFICATION",
                List.of("zh-cn"));
        FakeMaterialCatalog catalog = new FakeMaterialCatalog(List.of(wrongLanguage));
        EligibleLearningTaskCandidateReader reader = new EligibleLearningTaskCandidateReader(catalog);

        PlanningCandidateSetResult result = reader.readCandidates(request(10, Set.of()));

        assertThat(result).isEqualTo(new PlanningCandidateSetResult.Unavailable(NO_ELIGIBLE_MATERIAL));
        assertThat(catalog.resolvedIdentities).isEmpty();
    }

    @Test
    void exactResolvesEveryShortlistedCandidateIntoOrderedTaskPlans() {
        FakeMaterialCatalog catalog = new FakeMaterialCatalog(List.of(
                summary(GREETING, "GREETING_INTRODUCTION"),
                summary(CAFE, "CAFE_SIMPLE_REQUEST")));
        EligibleLearningTaskCandidateReader reader = new EligibleLearningTaskCandidateReader(catalog);

        PlanningCandidateSetResult result = reader.readCandidates(request(30, Set.of()));

        assertThat(result).isInstanceOfSatisfying(PlanningCandidateSetResult.Available.class, available -> {
            List<LearningTaskPlan> plans = available.candidateSet().candidates();
            assertThat(plans).hasSize(2);
            LearningTaskPlan cafePlan = plans.getFirst();
            assertThat(cafePlan.languageProfileId()).isEqualTo(PROFILE_ID);
            assertThat(cafePlan.materialIdentity()).isEqualTo(CAFE);
            assertThat(cafePlan.targetLanguage()).isEqualTo("en");
            assertThat(cafePlan.supportLanguage()).isEqualTo("zh-cn");
            assertThat(cafePlan.difficulty()).isEqualTo(FOUNDATION);
            assertThat(cafePlan.estimatedDurationMinutes()).isEqualTo(10);
            assertThat(cafePlan.scenario()).isEqualTo("CAFE_SIMPLE_REQUEST");
            assertThat(cafePlan.primaryGoal()).isEqualTo("Goal for CAFE_SIMPLE_REQUEST");
            assertThat(cafePlan.taskType()).isEqualTo(LearningTaskPlan.TaskType.TEXT_PRACTICE);
            assertThat(cafePlan.reason()).isEqualTo(
                    LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK);
            assertThat(plans.getLast().materialIdentity()).isEqualTo(GREETING);
            assertThat(plans.getLast().estimatedDurationMinutes()).isEqualTo(10);
        });
        assertThat(catalog.resolvedIdentities).containsExactly(CAFE, GREETING);
    }

    @Test
    void failsClosedWhenAnyShortlistedCandidateCannotBeResolved() {
        FakeMaterialCatalog catalog = new FakeMaterialCatalog(List.of(
                summary(GREETING, "GREETING_INTRODUCTION"),
                summary(CAFE, "CAFE_SIMPLE_REQUEST")));
        catalog.results.put(GREETING, new MaterialQueryResult.Unavailable(
                MaterialUnavailableReason.MATERIAL_NOT_PUBLISHED));
        EligibleLearningTaskCandidateReader reader = new EligibleLearningTaskCandidateReader(catalog);

        PlanningCandidateSetResult result = reader.readCandidates(request(10, Set.of()));

        // 首选 CAFE 本身可解析，但 secondary GREETING 不一致时整个 shortlist fail closed。
        assertThat(result).isEqualTo(new PlanningCandidateSetResult.Unavailable(SELECTED_MATERIAL_UNAVAILABLE));
        assertThat(catalog.resolvedIdentities).containsExactly(CAFE, GREETING);
    }

    @Test
    void failsClosedWhenFirstShortlistedCandidateCannotBeResolved() {
        FakeMaterialCatalog catalog = new FakeMaterialCatalog(List.of(summary(CAFE, "CAFE_SIMPLE_REQUEST")));
        catalog.results.put(CAFE, new MaterialQueryResult.Unavailable(
                MaterialUnavailableReason.MATERIAL_NOT_PUBLISHED));
        EligibleLearningTaskCandidateReader reader = new EligibleLearningTaskCandidateReader(catalog);

        PlanningCandidateSetResult result = reader.readCandidates(request(10, Set.of()));

        assertThat(result).isEqualTo(new PlanningCandidateSetResult.Unavailable(SELECTED_MATERIAL_UNAVAILABLE));
    }

    @Test
    void failsClosedWhenSecondaryResolvedMaterialViolatesTargetLanguage() {
        FakeMaterialCatalog catalog = new FakeMaterialCatalog(List.of(
                summary(GREETING, "GREETING_INTRODUCTION"),
                summary(CAFE, "CAFE_SIMPLE_REQUEST")));
        catalog.results.put(GREETING, new MaterialQueryResult.Available(
                material(GREETING, "ja", "GREETING_INTRODUCTION"),
                scaffold("zh-cn")));
        EligibleLearningTaskCandidateReader reader = new EligibleLearningTaskCandidateReader(catalog);

        PlanningCandidateSetResult result = reader.readCandidates(request(10, Set.of()));

        assertThat(result).isEqualTo(new PlanningCandidateSetResult.Unavailable(SELECTED_MATERIAL_UNAVAILABLE));
    }

    @Test
    void failsClosedWhenSecondaryResolvedMaterialCarriesDifferentMaterialId() {
        FakeMaterialCatalog catalog = new FakeMaterialCatalog(List.of(
                summary(GREETING, "GREETING_INTRODUCTION"),
                summary(CAFE, "CAFE_SIMPLE_REQUEST")));
        // 请求 GREETING 却解析回 CAFE identity 的 material：exact identity 不一致必须整组 fail closed。
        catalog.results.put(GREETING, new MaterialQueryResult.Available(
                material(CAFE, "en", "GREETING_INTRODUCTION"),
                scaffold("zh-cn")));
        EligibleLearningTaskCandidateReader reader = new EligibleLearningTaskCandidateReader(catalog);

        PlanningCandidateSetResult result = reader.readCandidates(request(10, Set.of()));

        assertThat(result).isEqualTo(new PlanningCandidateSetResult.Unavailable(SELECTED_MATERIAL_UNAVAILABLE));
    }

    @Test
    void failsClosedWhenSecondaryResolvedMaterialCarriesDifferentPublishedVersion() {
        MaterialIdentity greetingV2 = new MaterialIdentity("en-builtin-greeting-intro", "v2");
        FakeMaterialCatalog catalog = new FakeMaterialCatalog(List.of(
                summary(GREETING, "GREETING_INTRODUCTION"),
                summary(CAFE, "CAFE_SIMPLE_REQUEST")));
        catalog.results.put(GREETING, new MaterialQueryResult.Available(
                material(greetingV2, "en", "GREETING_INTRODUCTION"),
                scaffold("zh-cn")));
        EligibleLearningTaskCandidateReader reader = new EligibleLearningTaskCandidateReader(catalog);

        PlanningCandidateSetResult result = reader.readCandidates(request(10, Set.of()));

        assertThat(result).isEqualTo(new PlanningCandidateSetResult.Unavailable(SELECTED_MATERIAL_UNAVAILABLE));
    }

    @Test
    void failsClosedWhenResolvedMaterialCarriesNullDifficulty() {
        // MaterialDifficulty 当前只有 FOUNDATION，无法构造真实的值级 mismatch；
        // null difficulty 覆盖 resolved difficulty 比较分支的负向路径。
        FakeMaterialCatalog catalog = new FakeMaterialCatalog(List.of(summary(CAFE, "CAFE_SIMPLE_REQUEST")));
        catalog.results.put(CAFE, new MaterialQueryResult.Available(
                material(CAFE, "en", "CAFE_SIMPLE_REQUEST", null),
                scaffold("zh-cn")));
        EligibleLearningTaskCandidateReader reader = new EligibleLearningTaskCandidateReader(catalog);

        PlanningCandidateSetResult result = reader.readCandidates(request(10, Set.of()));

        assertThat(result).isEqualTo(new PlanningCandidateSetResult.Unavailable(SELECTED_MATERIAL_UNAVAILABLE));
    }

    @Test
    void failsClosedWhenResolvedScaffoldViolatesSupportLanguage() {
        FakeMaterialCatalog catalog = new FakeMaterialCatalog(List.of(summary(CAFE, "CAFE_SIMPLE_REQUEST")));
        catalog.results.put(CAFE, new MaterialQueryResult.Available(
                material(CAFE, "en", "CAFE_SIMPLE_REQUEST"),
                scaffold("ja")));
        EligibleLearningTaskCandidateReader reader = new EligibleLearningTaskCandidateReader(catalog);

        PlanningCandidateSetResult result = reader.readCandidates(request(10, Set.of()));

        assertThat(result).isEqualTo(new PlanningCandidateSetResult.Unavailable(SELECTED_MATERIAL_UNAVAILABLE));
    }

    @Test
    void candidateSetRejectsEmptyAndOversizedLists() {
        assertThatThrownBy(() -> new PlanningCandidateSet(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
        assertThatThrownBy(() -> new PlanningCandidateSet(rangePlans(PlanningCandidateSet.MAXIMUM_CANDIDATE_COUNT + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed 8");
    }

    @Test
    void candidateSetRejectsNullListAndNullCandidates() {
        assertThatThrownBy(() -> new PlanningCandidateSet(null))
                .isInstanceOf(NullPointerException.class);
        // List.of 本身禁止 null；用可暂存 null 的 mutable list 才能真正执行到 defensive copy。
        List<LearningTaskPlan> withNullElement = new ArrayList<>();
        withNullElement.add(plan(CAFE));
        withNullElement.add(null);
        assertThatThrownBy(() -> new PlanningCandidateSet(withNullElement))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void candidateSetDefensivelyCopiesSourceAndExposesUnmodifiableView() {
        List<LearningTaskPlan> source = new ArrayList<>(rangePlans(2));
        PlanningCandidateSet candidateSet = new PlanningCandidateSet(source);

        source.removeLast();
        assertThat(candidateSet.candidates()).hasSize(2);
        assertThatThrownBy(() -> candidateSet.candidates().add(plan(GREETING)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void candidateSetDeterministicFallbackIsAlwaysTheFirstCandidate() {
        PlanningCandidateSet candidateSet = new PlanningCandidateSet(rangePlans(3));

        assertThat(candidateSet.deterministicFallback()).isEqualTo(candidateSet.candidates().getFirst());
        assertThat(candidateSet.deterministicFallback().materialIdentity())
                .isEqualTo(candidateSet.candidates().getFirst().materialIdentity());
    }

    private static PlanningRequest request(int availableMinutes, Set<MaterialIdentity> excludedMaterials) {
        return new PlanningRequest(
                new LanguageProfileIdentity(PROFILE_ID, USER_ID, "en"),
                "zh-cn",
                FOUNDATION,
                availableMinutes,
                excludedMaterials);
    }

    private static List<AvailableMaterialSummary> reversedRangeSummaries(int count) {
        List<AvailableMaterialSummary> summaries = new ArrayList<>();
        for (int index = count; index >= 1; index--) {
            summaries.add(summary(rangeIdentity(index), "SCENARIO_" + index));
        }
        return summaries;
    }

    private static List<MaterialIdentity> expectedShortlistIdentities(int count) {
        List<MaterialIdentity> identities = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            identities.add(rangeIdentity(index));
        }
        return identities;
    }

    private static List<LearningTaskPlan> rangePlans(int count) {
        List<LearningTaskPlan> plans = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            plans.add(plan(rangeIdentity(index)));
        }
        return plans;
    }

    private static MaterialIdentity rangeIdentity(int index) {
        return new MaterialIdentity("en-builtin-range-" + String.format("%02d", index), "v1");
    }

    private static LearningTaskPlan plan(MaterialIdentity identity) {
        return new LearningTaskPlan(
                PROFILE_ID,
                identity,
                "en",
                "zh-cn",
                FOUNDATION,
                10,
                "SCENARIO",
                "Goal for SCENARIO",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK);
    }

    private static AvailableMaterialSummary summary(MaterialIdentity identity, String scenario) {
        return new AvailableMaterialSummary(identity, "en", FOUNDATION, scenario, List.of("zh-cn"));
    }

    private static PublishedLearningMaterial material(
            MaterialIdentity identity,
            String targetLanguage,
            String scenario
    ) {
        return material(identity, targetLanguage, scenario, MaterialDifficulty.FOUNDATION);
    }

    private static PublishedLearningMaterial material(
            MaterialIdentity identity,
            String targetLanguage,
            String scenario,
            MaterialDifficulty difficulty
    ) {
        return new PublishedLearningMaterial(
                identity,
                new TargetPracticeCore(
                        targetLanguage,
                        difficulty,
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
        return new SupportScaffold(supportLanguage, "instruction", "explanation", "hint", "note", List.of());
    }

    private static final class FakeMaterialCatalog implements LearningMaterialCatalog {

        private final List<AvailableMaterialSummary> summaries;
        private final Map<MaterialIdentity, MaterialQueryResult> results = new HashMap<>();
        private final List<MaterialIdentity> resolvedIdentities = new ArrayList<>();
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
            resolvedIdentities.add(identity);
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
