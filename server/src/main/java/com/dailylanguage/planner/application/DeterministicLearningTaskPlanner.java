package com.dailylanguage.planner.application;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.dailylanguage.content.domain.AvailableMaterialSummary;
import com.dailylanguage.content.domain.LearningMaterialCatalog;
import com.dailylanguage.content.domain.MaterialQueryResult;
import com.dailylanguage.content.domain.PublishedLearningMaterial;
import com.dailylanguage.content.domain.SupportScaffold;
import com.dailylanguage.content.domain.TargetPracticeCore;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.planner.domain.PlanningRequest;
import com.dailylanguage.planner.domain.PlanningResult;

import static com.dailylanguage.planner.domain.PlanningResult.UnavailableReason.AVAILABLE_TIME_TOO_SHORT;
import static com.dailylanguage.planner.domain.PlanningResult.UnavailableReason.NO_ELIGIBLE_MATERIAL;
import static com.dailylanguage.planner.domain.PlanningResult.UnavailableReason.SELECTED_MATERIAL_UNAVAILABLE;

/**
 * Provider-free Planner core：只从 Catalog 已发布候选中选择，不生成 Content，也不调用 Model、写数据库或
 * 修改 learner state。所有 hard constraints 与最终 material validation 都由 Java 执行。
 */
@Service
public final class DeterministicLearningTaskPlanner implements LearningTaskPlanner {

    static final int MINIMUM_AVAILABLE_MINUTES = 5;
    static final int MAXIMUM_PLANNED_MINUTES = 10;

    private static final Comparator<AvailableMaterialSummary> FALLBACK_ORDER = Comparator
            .comparing((AvailableMaterialSummary candidate) -> candidate.identity().materialId())
            .thenComparing(candidate -> candidate.identity().publishedVersion());

    private final LearningMaterialCatalog materialCatalog;

    public DeterministicLearningTaskPlanner(LearningMaterialCatalog materialCatalog) {
        this.materialCatalog = Objects.requireNonNull(materialCatalog, "materialCatalog must not be null");
    }

    @Override
    public PlanningResult plan(PlanningRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.availableMinutes() < MINIMUM_AVAILABLE_MINUTES) {
            return new PlanningResult.Unavailable(AVAILABLE_TIME_TOO_SHORT);
        }

        List<AvailableMaterialSummary> candidates = materialCatalog.listAvailable(
                request.languageProfile().languageCode(), request.supportLanguage());
        Optional<AvailableMaterialSummary> selectedCandidate = candidates.stream()
                .filter(candidate -> isEligible(candidate, request))
                // 不依赖具体 Catalog 的返回顺序，保证相同输入在不同 Content adapter 下仍可重放。
                .sorted(FALLBACK_ORDER)
                .findFirst();
        if (selectedCandidate.isEmpty()) {
            return new PlanningResult.Unavailable(NO_ELIGIBLE_MATERIAL);
        }

        AvailableMaterialSummary selectedSummary = selectedCandidate.get();
        MaterialQueryResult queryResult = materialCatalog.findByIdentity(
                selectedSummary.identity(), request.supportLanguage());
        if (!(queryResult instanceof MaterialQueryResult.Available available)
                || !isResolvedMaterialValid(selectedSummary, available, request)) {
            // list 与 resolve 的任何不一致都 fail closed，不能静默跨语言或用损坏 Content 创建任务。
            return new PlanningResult.Unavailable(SELECTED_MATERIAL_UNAVAILABLE);
        }

        TargetPracticeCore targetCore = available.material().targetCore();
        LearningTaskPlan task = new LearningTaskPlan(
                request.languageProfile().id(),
                selectedSummary.identity(),
                targetCore.targetLanguage(),
                available.selectedScaffold().supportLanguage(),
                targetCore.difficulty(),
                Math.min(request.availableMinutes(), MAXIMUM_PLANNED_MINUTES),
                targetCore.scenario(),
                targetCore.communicationObjective(),
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK);
        return new PlanningResult.Planned(task);
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
