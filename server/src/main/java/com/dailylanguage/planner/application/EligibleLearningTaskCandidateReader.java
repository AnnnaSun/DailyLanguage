package com.dailylanguage.planner.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.dailylanguage.content.domain.AvailableMaterialSummary;
import com.dailylanguage.content.domain.LearningMaterialCatalog;
import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.content.domain.MaterialQueryResult;
import com.dailylanguage.content.domain.PublishedLearningMaterial;
import com.dailylanguage.content.domain.SupportScaffold;
import com.dailylanguage.content.domain.TargetPracticeCore;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.planner.domain.PlanningCandidateSet;
import com.dailylanguage.planner.domain.PlanningCandidateSetResult;
import com.dailylanguage.planner.domain.PlanningRequest;
import com.dailylanguage.planner.domain.PlanningRun;

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
                    || !isResolvedMaterialValid(summary.identity(), available, request)) {
                // list 与 resolve 的任何不一致都 fail closed；损坏的 secondary candidate 也使整个
                // shortlist 失效，不能把 partial set 交给后续流程。
                return new PlanningCandidateSetResult.Unavailable(SELECTED_MATERIAL_UNAVAILABLE);
            }
            plans.add(toTaskPlan(summary.identity(), available, request));
        }
        return new PlanningCandidateSetResult.Available(new PlanningCandidateSet(plans));
    }

    /**
     * S9E2B 的 durable snapshot re-resolution：按 {@link PlanningRun.Snapshot} 的原始顺序对每个
     * candidate identity 重新执行 exact resolve 与同一套 metadata drift 校验。Snapshot 只证明创建期
     * offered 过该 identity，不是 Content authority——任何 candidate 缺失、identity 漂移或 metadata
     * 与当前 request 不一致都使整组 fail closed，调用方不得基于 partial 结果创建任何 task。
     * 不重新排序、不重新过滤 shortlist：index 0 仍是唯一 deterministic fallback。
     */
    public PlanningCandidateSetResult resolveSnapshot(PlanningRequest request, PlanningRun.Snapshot snapshot) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (!snapshot.languageProfileId().equals(request.languageProfile().id())) {
            throw new IllegalArgumentException(
                    "snapshot languageProfileId must match the planning request language profile");
        }
        if (request.availableMinutes() < MINIMUM_AVAILABLE_MINUTES) {
            return new PlanningCandidateSetResult.Unavailable(AVAILABLE_TIME_TOO_SHORT);
        }

        List<LearningTaskPlan> plans = new ArrayList<>(snapshot.candidates().size());
        for (PlanningRun.Candidate candidate : snapshot.candidates()) {
            // 与创建期 shortlist 相同的 Java hard constraint：被 request 排除的 identity 不得
            // 重新进入 planning；snapshot 固定不可裁剪，命中即整组 fail closed。
            if (request.excludedMaterials().contains(candidate.identity())) {
                return new PlanningCandidateSetResult.Unavailable(SELECTED_MATERIAL_UNAVAILABLE);
            }
            MaterialQueryResult queryResult = materialCatalog.findByIdentity(
                    candidate.identity(), request.supportLanguage());
            if (!(queryResult instanceof MaterialQueryResult.Available available)
                    || !isResolvedMaterialValid(candidate.identity(), available, request)) {
                return new PlanningCandidateSetResult.Unavailable(SELECTED_MATERIAL_UNAVAILABLE);
            }
            plans.add(toTaskPlan(candidate.identity(), available, request));
        }
        return new PlanningCandidateSetResult.Available(new PlanningCandidateSet(plans));
    }

    private static LearningTaskPlan toTaskPlan(
            MaterialIdentity identity,
            MaterialQueryResult.Available available,
            PlanningRequest request
    ) {
        TargetPracticeCore targetCore = available.material().targetCore();
        return new LearningTaskPlan(
                request.languageProfile().id(),
                identity,
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
            MaterialIdentity requestedIdentity,
            MaterialQueryResult.Available available,
            PlanningRequest request
    ) {
        PublishedLearningMaterial material = available.material();
        SupportScaffold scaffold = available.selectedScaffold();
        if (material == null || material.identity() == null || material.targetCore() == null || scaffold == null) {
            return false;
        }
        TargetPracticeCore targetCore = material.targetCore();
        return material.identity().equals(requestedIdentity)
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
