package com.dailylanguage.planner.application;

import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.languageprofile.application.LanguageProfileAccessService;
import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.execution.FixedTextGenerationRoutes;
import com.dailylanguage.modelgateway.text.execution.TextGenerationRoute;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.domain.PlanningCandidateSet;
import com.dailylanguage.planner.domain.PlanningCandidateSetResult;
import com.dailylanguage.planner.domain.PlanningRequest;
import com.dailylanguage.planner.domain.PlanningResult;
import com.dailylanguage.planner.infrastructure.LearningTaskRepository;
import com.dailylanguage.security.domain.UserContext;

/**
 * Owner-scoped planning 的 HTTP-facing Application 编排：owned Profile 校验 → deterministic candidate
 * 读取 → optional Model enrichment。enrichment 参数只在 providerId 与 Credential 同时缺失或同时
 * 合法时成立：同时缺失时完全保持 deterministic planning（持久化 candidate index 0）；同时合法时
 * 复用 S9A–S9E2B 的 durable 链路（dispatch → bounded wait → 原子 finalization），最终始终返回
 * durable LearningTask。route / prompt unavailable 不创建 Run / Job，直接落 deterministic fallback；
 * Model failure、capacity rejection、invalid output 或 wait exhaustion 由 finalizer 裁决为 fallback
 * task；未知 dispatch / invariant / DB 异常原样传播，不伪装成功。外层禁止事务，保留 Reader、
 * Run 创建、dispatch、wait 与 finalization 各自已定义的事务边界；userId 只信任
 * {@link UserContext}，Credential 只在当前内存调用链中传播。
 */
@Service
public class LearningTaskPlanningService {

    private static final int MAX_SUPPORT_LANGUAGE_LENGTH = 35;

    private final LanguageProfileAccessService languageProfileAccessService;
    private final EligibleLearningTaskCandidateReader candidateReader;
    private final FixedTextGenerationRoutes routes;
    private final PlannerEnrichmentDispatchService dispatchService;
    private final PlannerEnrichmentJobAwaiter awaiter;
    private final PlannerEnrichmentFinalizationService finalizationService;
    private final LearningTaskRepository learningTaskRepository;

    public LearningTaskPlanningService(
            LanguageProfileAccessService languageProfileAccessService,
            EligibleLearningTaskCandidateReader candidateReader,
            FixedTextGenerationRoutes routes,
            PlannerEnrichmentDispatchService dispatchService,
            PlannerEnrichmentJobAwaiter awaiter,
            PlannerEnrichmentFinalizationService finalizationService,
            LearningTaskRepository learningTaskRepository) {
        this.languageProfileAccessService =
                Objects.requireNonNull(languageProfileAccessService, "languageProfileAccessService must not be null");
        this.candidateReader =
                Objects.requireNonNull(candidateReader, "candidateReader must not be null");
        this.routes = Objects.requireNonNull(routes, "routes must not be null");
        this.dispatchService =
                Objects.requireNonNull(dispatchService, "dispatchService must not be null");
        this.awaiter = Objects.requireNonNull(awaiter, "awaiter must not be null");
        this.finalizationService =
                Objects.requireNonNull(finalizationService, "finalizationService must not be null");
        this.learningTaskRepository =
                Objects.requireNonNull(learningTaskRepository, "learningTaskRepository must not be null");
    }

    @Transactional(propagation = Propagation.NEVER)
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

        // optional providerId / Credential pair 只允许同时缺失（null）或同时合法；任一字段
        // 出现（包括 blank 值）即进入 pair 校验：presence 用 null 判断，validity 用内容判断，
        // blank 不是 absent，必须在读取 Profile 前裁决为 400，不产生任何数据库交互。
        String providerIdValue = command.providerId();
        String credentialSecret = command.providerCredentialSecret();
        ProviderId providerId = null;
        if (providerIdValue != null || credentialSecret != null) {
            if (!hasText(providerIdValue)) {
                return new LearningTaskPlanningResult.InvalidProviderId();
            }
            try {
                providerId = new ProviderId(providerIdValue);
            } catch (IllegalArgumentException exception) {
                return new LearningTaskPlanningResult.InvalidProviderId();
            }
            if (!hasText(credentialSecret)) {
                return new LearningTaskPlanningResult.InvalidProviderCredential();
            }
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
        PlanningCandidateSetResult candidateResult = candidateReader.readCandidates(planningRequest);
        if (candidateResult instanceof PlanningCandidateSetResult.Unavailable unavailable) {
            return new LearningTaskPlanningResult.Unavailable(unavailable.reason());
        }
        PlanningCandidateSet candidateSet =
                ((PlanningCandidateSetResult.Available) candidateResult).candidateSet();

        if (providerId == null) {
            return createDeterministicTask(userContext.userId(), candidateSet);
        }
        return planWithOptionalEnrichment(
                userContext, planningRequest, candidateSet, providerId, credentialSecret);
    }

    private LearningTaskPlanningResult planWithOptionalEnrichment(
            UserContext userContext,
            PlanningRequest planningRequest,
            PlanningCandidateSet candidateSet,
            ProviderId providerId,
            String credentialSecret) {
        // Provider 调用只能经由 fixed PLANNING route：requested provider 不一致即 422 fail closed，
        // 零 Provider 调用、零 task；route 未配置时 enrichment 不可用，不创建 Run / Job，
        // 直接落 deterministic fallback。
        Optional<TextGenerationRoute> route = routes.findRoute(ModelPurpose.PLANNING);
        if (route.isEmpty()) {
            return createDeterministicTask(userContext.userId(), candidateSet);
        }
        if (!route.orElseThrow().providerId().equals(providerId)) {
            return new LearningTaskPlanningResult.ProviderMismatch();
        }

        PlannerEnrichmentDispatchService.DispatchResult dispatchResult = dispatchService.dispatch(
                planningRequest,
                candidateSet,
                userContext,
                new TransientProviderCredential(route.orElseThrow().providerId(), credentialSecret));
        if (dispatchResult instanceof PlannerEnrichmentDispatchService.DispatchResult.Unavailable) {
            // route / prompt unavailable：dispatch 未创建 Run / Job，落 candidate index 0。
            return createDeterministicTask(userContext.userId(), candidateSet);
        }
        PlannerEnrichmentDispatchService.DispatchResult.Created created =
                (PlannerEnrichmentDispatchService.DispatchResult.Created) dispatchResult;

        // 等待发生在事务之外；durable 裁决全部交给 finalizer（valid / invalid / failure /
        // capacity rejection / wait exhaustion 均可能产出 fallback task）。
        PlannerEnrichmentJobAwaiter.AwaitResult awaitResult = awaiter.await(created.jobId(), userContext);
        return switch (finalizationService.finalizeRun(
                created.run().id(), planningRequest, awaitResult, userContext)) {
            case PlannerEnrichmentFinalizationService.FinalizationResult.Created finalized ->
                    new LearningTaskPlanningResult.Created(finalized.task());
            case PlannerEnrichmentFinalizationService.FinalizationResult.Existing existing ->
                    new LearningTaskPlanningResult.Created(existing.task());
            case PlannerEnrichmentFinalizationService.FinalizationResult.NotFound ignored ->
                    throw new IllegalStateException("dispatched planning run cannot be found");
            case PlannerEnrichmentFinalizationService.FinalizationResult.Unavailable unavailable ->
                    new LearningTaskPlanningResult.Unavailable(unavailable.reason());
        };
    }

    /**
     * trustedUserId 只能来自 UserContext；candidate set 本身不是 authorization proof。
     * Repository 的 create gate 在数据库内原子重校验 owner/profile/target language，
     * empty 即 fail closed 为 404，不区分"不存在"与"不属于该 caller"。
     */
    private LearningTaskPlanningResult createDeterministicTask(
            UUID trustedUserId, PlanningCandidateSet candidateSet) {
        Optional<LearningTask> createdTask = learningTaskRepository
                .createOwned(trustedUserId, candidateSet.deterministicFallback());
        return createdTask
                .<LearningTaskPlanningResult>map(LearningTaskPlanningResult.Created::new)
                .orElseGet(LearningTaskPlanningResult.LanguageProfileNotFound::new);
    }

    /**
     * 未通过 Application 边界验证的 raw planning 请求字段；normalize 与 enum 解析只发生在 Service 内，
     * 调用方无法用未规范化的值构造 PlanningRequest。providerId / credentialSecret 保持 raw 值，
     * pair 合法性与 fixed route 匹配在本 Service 内裁决。
     */
    public record PlanningCommand(
            String supportLanguage,
            String requestedDifficulty,
            Integer availableMinutes,
            String providerId,
            String providerCredentialSecret) {

        /** 不请求 enrichment 的 legacy 构造入口：providerId 与 Credential 同时缺失。 */
        public PlanningCommand(String supportLanguage, String requestedDifficulty, Integer availableMinutes) {
            this(supportLanguage, requestedDifficulty, availableMinutes, null, null);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
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
