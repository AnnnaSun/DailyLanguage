package com.dailylanguage.planner.application;

import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.languageprofile.application.LanguageProfileAccessService;
import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.planner.domain.PlanningRequest;
import com.dailylanguage.planner.domain.PlanningResult;
import com.dailylanguage.planner.infrastructure.LearningTaskRepository;
import com.dailylanguage.security.domain.UserContext;

/**
 * Owner-scoped planning 的 Application 编排：owned Profile 校验 → deterministic Planner → durable create。
 * 不开启覆盖 Profile read 与 persistence 的大事务，真正的 mutation 由 Repository 在自身事务内
 * 原子重校验 owner/profile/target language。userId 只信任 {@link UserContext}，请求携带的任何
 * identity 字段都不参与 ownership 判断。
 */
@Service
public class LearningTaskPlanningService {

    private static final int MAX_SUPPORT_LANGUAGE_LENGTH = 35;

    private final LanguageProfileAccessService languageProfileAccessService;
    private final LearningTaskPlanner learningTaskPlanner;
    private final LearningTaskRepository learningTaskRepository;

    public LearningTaskPlanningService(
            LanguageProfileAccessService languageProfileAccessService,
            LearningTaskPlanner learningTaskPlanner,
            LearningTaskRepository learningTaskRepository) {
        this.languageProfileAccessService =
                Objects.requireNonNull(languageProfileAccessService, "languageProfileAccessService must not be null");
        this.learningTaskPlanner =
                Objects.requireNonNull(learningTaskPlanner, "learningTaskPlanner must not be null");
        this.learningTaskRepository =
                Objects.requireNonNull(learningTaskRepository, "learningTaskRepository must not be null");
    }

    public LearningTaskPlanningResult plan(UUID languageProfileId, UserContext userContext, PlanningCommand command) {
        Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
        Objects.requireNonNull(userContext, "userContext must not be null");
        Objects.requireNonNull(command, "command must not be null");

        String supportLanguage = normalizeSupportLanguage(command.supportLanguage());
        MaterialDifficulty requestedDifficulty = resolveRequestedDifficulty(command.requestedDifficulty());
        Integer availableMinutes = command.availableMinutes();
        if (supportLanguage == null
                || requestedDifficulty == null
                || availableMinutes == null
                || availableMinutes <= 0) {
            return new LearningTaskPlanningResult.InvalidRequest();
        }

        Optional<LanguageProfileIdentity> ownedProfile =
                languageProfileAccessService.findProfileOwnedByUser(languageProfileId, userContext);
        if (ownedProfile.isEmpty()) {
            return new LearningTaskPlanningResult.LanguageProfileNotFound();
        }
        LanguageProfileIdentity profile = ownedProfile.orElseThrow();

        PlanningRequest planningRequest = new PlanningRequest(
                profile,
                supportLanguage,
                requestedDifficulty,
                availableMinutes,
                Set.of());
        PlanningResult planningResult = learningTaskPlanner.plan(planningRequest);
        if (planningResult instanceof PlanningResult.Unavailable unavailable) {
            // Planner unavailable 时不调用 Repository，不产生任何数据库记录。
            return new LearningTaskPlanningResult.Unavailable(unavailable.reason());
        }
        LearningTaskPlan plan = ((PlanningResult.Planned) planningResult).task();
        // Repository 的 create gate 只验证 plan 中 Profile 属于该 user，不验证等于本次请求的 Profile；
        // Planner 是可被替换/组合的 port，结果必须仍绑定请求的 Profile 才能落库，mismatch fail closed。
        if (!plan.languageProfileId().equals(profile.id())) {
            return new LearningTaskPlanningResult.Unavailable(
                    PlanningResult.UnavailableReason.SELECTED_MATERIAL_UNAVAILABLE);
        }

        // trustedUserId 只能来自 UserContext；LearningTaskPlan 本身不是 authorization proof。
        Optional<LearningTask> createdTask = learningTaskRepository.createOwned(userContext.userId(), plan);
        // empty 表示 Profile 在规划与 insert 之间失效，数据库原子 create gate 已裁决 fail closed。
        return createdTask
                .<LearningTaskPlanningResult>map(LearningTaskPlanningResult.Created::new)
                .orElseGet(LearningTaskPlanningResult.LanguageProfileNotFound::new);
    }

    /**
     * 未通过 Application 边界验证的 raw planning 请求字段；normalize 与 enum 解析只发生在 Service 内，
     * 调用方无法用未规范化的值构造 PlanningRequest。
     */
    public record PlanningCommand(String supportLanguage, String requestedDifficulty, Integer availableMinutes) {
    }

    // 与 language_profile 的 languageCode 使用同一 BCP 47 lowercase contract，保证 supportLanguage
    // 与 Catalog scaffold 的精确匹配不受大小数或区域差异影响。
    private static String normalizeSupportLanguage(String supportLanguage) {
        if (supportLanguage == null) {
            return null;
        }
        String trimmedSupportLanguage = supportLanguage.strip();
        if (trimmedSupportLanguage.isEmpty()
                || trimmedSupportLanguage.length() > MAX_SUPPORT_LANGUAGE_LENGTH) {
            return null;
        }
        try {
            return new Locale.Builder()
                    .setLanguageTag(trimmedSupportLanguage)
                    .build()
                    .toLanguageTag()
                    .toLowerCase(Locale.ROOT);
        } catch (IllformedLocaleException exception) {
            return null;
        }
    }

    // M1 仅发布 FOUNDATION；解析失败即违反 difficulty framework，不静默降级。
    private static MaterialDifficulty resolveRequestedDifficulty(String requestedDifficulty) {
        if (requestedDifficulty == null) {
            return null;
        }
        try {
            return MaterialDifficulty.valueOf(requestedDifficulty);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
