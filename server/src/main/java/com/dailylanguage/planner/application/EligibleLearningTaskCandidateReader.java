package com.dailylanguage.planner.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.dailylanguage.content.domain.AvailableMaterialSummary;
import com.dailylanguage.content.domain.LearningMaterialCatalog;
import com.dailylanguage.content.domain.MaterialQueryResult;
import com.dailylanguage.content.domain.PublishedLearningMaterial;
import com.dailylanguage.content.domain.SupportScaffold;
import com.dailylanguage.content.domain.TargetPracticeCore;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.planner.domain.PlanningCandidateSet;
import com.dailylanguage.planner.domain.PlanningCandidateSetResult;
import com.dailylanguage.planner.domain.PlanningRequest;

import static com.dailylanguage.planner.domain.PlanningResult.UnavailableReason.AVAILABLE_TIME_TOO_SHORT;
import static com.dailylanguage.planner.domain.PlanningResult.UnavailableReason.NO_ELIGIBLE_MATERIAL;
import static com.dailylanguage.planner.domain.PlanningResult.UnavailableReason.SELECTED_MATERIAL_UNAVAILABLE;

/**
 * Planner 的 deterministic candidate boundary：从 Catalog 已发布材料执行一次 hard filtering 与
 * stable sort，产生最多 {@link PlanningCandidateSet#MAXIMUM_CANDIDATE_COUNT} 个、每个都经过 exact
 * re-resolution 验证的 ordered task-plan candidate set。不生成 Content、不调用 Model、不写数据库。
 */
@Service
public final class EligibleLearningTaskCandidateReader {

    static final int MINIMUM_AVAILABLE_MINUTES = 5;
    static final int MAXIMUM_PLANNED_MINUTES = 10;

    private static final Comparator<AvailableMaterialSummary> STABLE_ORDER = Comparator
            .comparing((AvailableMaterialSummary candidate) -> candidate.identity().materialId())
            .thenComparing(candidate -> candidate.identity().publishedVersion());

    private final LearningMaterialCatalog materialCatalog;

    public EligibleLearningTaskCandidateReader(LearningMaterialCatalog materialCatalog) {
        this.materialCatalog = Objects.requireNonNull(materialCatalog, "materialCatalog must not be null");
    }

    public PlanningCandidateSetResult readCandidates(PlanningRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.availableMinutes() < MINIMUM_AVAILABLE_MINUTES) {
            return new PlanningCandidateSetResult.Unavailable(AVAILABLE_TIME_TOO_SHORT);
        }

        List<AvailableMaterialSummary> shortlist = materialCatalog
                .listAvailable(request.languageProfile().languageCode(), request.supportLanguage()).stream()
                .filter(candidate -> isEligible(candidate, request))
                // 不依赖具体 Catalog 的返回顺序，保证相同输入在不同 Content adapter 下仍可重放。
                .sorted(STABLE_ORDER)
                .limit(PlanningCandidateSet.MAXIMUM_CANDIDATE_COUNT)
                .toList();
        if (shortlist.isEmpty()) {
            return new PlanningCandidateSetResult.Unavailable(NO_ELIGIBLE_MATERIAL);
        }

        List<LearningTaskPlan> plans = new ArrayList<>(shortlist.size());
        for (AvailableMaterialSummary summary : shortlist) {
            MaterialQueryResult queryResult = materialCatalog.findByIdentity(
                    summary.identity(), request.supportLanguage());
            if (!(queryResult instanceof MaterialQueryResult.Available available)
                    || !isResolvedMaterialValid(summary, available, request)) {
                // list 与 resolve 的任何不一致都 fail closed；损坏的 secondary candidate 也使整个
                // shortlist 失效，不能把 partial set 交给后续流程。
                return new PlanningCandidateSetResult.Unavailable(SELECTED_MATERIAL_UNAVAILABLE);
            }
            plans.add(toTaskPlan(summary, available, request));
        }
        return new PlanningCandidateSetResult.Available(new PlanningCandidateSet(plans));
    }

    private static LearningTaskPlan toTaskPlan(
            AvailableMaterialSummary summary,
            MaterialQueryResult.Available available,
            PlanningRequest request
    ) {
        TargetPracticeCore targetCore = available.material().targetCore();
        return new LearningTaskPlan(
                request.languageProfile().id(),
                summary.identity(),
                targetCore.targetLanguage(),
                available.selectedScaffold().supportLanguage(),
                targetCore.difficulty(),
                Math.min(request.availableMinutes(), MAXIMUM_PLANNED_MINUTES),
                targetCore.scenario(),
                targetCore.communicationObjective(),
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK);
    }

    private static boolean isEligible(AvailableMaterialSummary candidate, PlanningRequest request) {
        return candidate != null
                && candidate.identity() != null
                && hasText(candidate.identity().materialId())
                && hasText(candidate.identity().publishedVersion())
                && candidate.targetLanguage() != null
                && candidate.targetLanguage().equals(request.languageProfile().languageCode())
                && candidate.difficulty() == request.requestedDifficulty()
                && candidate.supportLanguages() != null
                && candidate.supportLanguages().contains(request.supportLanguage())
                && !request.excludedMaterials().contains(candidate.identity());
    }

    private static boolean isResolvedMaterialValid(
            AvailableMaterialSummary selectedSummary,
            MaterialQueryResult.Available available,
            PlanningRequest request
    ) {
        PublishedLearningMaterial material = available.material();
        SupportScaffold scaffold = available.selectedScaffold();
        if (material == null || material.identity() == null || material.targetCore() == null || scaffold == null) {
            return false;
        }
        TargetPracticeCore targetCore = material.targetCore();
        return material.identity().equals(selectedSummary.identity())
                && request.languageProfile().languageCode().equals(targetCore.targetLanguage())
                && request.requestedDifficulty() == targetCore.difficulty()
                && request.supportLanguage().equals(scaffold.supportLanguage())
                && hasText(targetCore.scenario())
                && hasText(targetCore.communicationObjective());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
