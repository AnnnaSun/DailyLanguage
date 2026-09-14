package com.dailylanguage.planner.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.dailylanguage.content.domain.MaterialIdentity;

/**
 * 已持久化 PlanningRun 的 durable 快照。PostgreSQL 是 id、status 与 lifecycle timestamp 的
 * authority；本类型只还原数据库已裁决的行，不提供任何 transition 操作。ownership 直接落列
 * （planner 没有父 Session），经 composite FK 与 language_profile 绑定为同一 owner。创建期
 * candidate snapshot 由 {@link Snapshot} 独立裁决后按原顺序持久化。
 */
public record PlanningRun(
        UUID id,
        UUID userId,
        UUID languageProfileId,
        UUID modelCallJobId,
        Status status,
        long workflowVersion,
        long rowVersion,
        OffsetDateTime createdAt,
        Optional<OffsetDateTime> completedAt) {

    /** S9 Planner enrichment workflow 第一版；绑定 Job 必须持有同一 version。 */
    public static final long CURRENT_WORKFLOW_VERSION = 0L;

    /** 创建该 Run 的 workflow step identity；Repository 的 insert gate 与绑定 Job 必须一致持有。 */
    public static final String WORKFLOW_STEP_ID = "PLANNER_ENRICHMENT";

    /** durable snapshot 的 candidate 上限；与 PlanningCandidateSet 的 max 8 对齐（index 0–7）。 */
    public static final int MAXIMUM_CANDIDATE_COUNT = PlanningCandidateSet.MAXIMUM_CANDIDATE_COUNT;

    public PlanningRun {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
        Objects.requireNonNull(modelCallJobId, "modelCallJobId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (workflowVersion < 0) {
            throw new IllegalArgumentException("workflowVersion must not be negative");
        }
        if (rowVersion < 0) {
            throw new IllegalArgumentException("rowVersion must not be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        if (completedAt.filter(value -> value.isBefore(createdAt)).isPresent()) {
            throw new IllegalArgumentException("completedAt must not be before createdAt");
        }
        switch (status) {
            case PENDING -> requireNoTerminalFact(completedAt);
        }
    }

    private static void requireNoTerminalFact(Optional<OffsetDateTime> completedAt) {
        if (completedAt.isPresent()) {
            throw new IllegalArgumentException("completedAt must be empty while status is PENDING");
        }
    }

    public enum Status {
        PENDING
    }

    /**
     * 创建期 ordered candidate snapshot：immutable、non-empty、最多
     * {@link #MAXIMUM_CANDIDATE_COUNT} 个元素，index 从 0 起连续且等于列表位置，同一 Run 内
     * exact material identity 不重复。index 0 是唯一 deterministic fallback。
     */
    public record Snapshot(UUID languageProfileId, List<Candidate> candidates) {

        public Snapshot {
            Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
            List<Candidate> defensiveCopy = List.copyOf(candidates);
            if (defensiveCopy.isEmpty()) {
                throw new IllegalArgumentException("candidates must not be empty");
            }
            if (defensiveCopy.size() > MAXIMUM_CANDIDATE_COUNT) {
                throw new IllegalArgumentException(
                        "candidates must not exceed " + MAXIMUM_CANDIDATE_COUNT + " entries");
            }
            HashSet<MaterialIdentity> seenIdentities = new HashSet<>();
            for (int index = 0; index < defensiveCopy.size(); index++) {
                Candidate candidate = defensiveCopy.get(index);
                if (candidate.index() != index) {
                    throw new IllegalArgumentException(
                            "candidate index must be dense, ordered and start at 0");
                }
                if (!seenIdentities.add(candidate.identity())) {
                    throw new IllegalArgumentException(
                            "candidates must not contain duplicate material identity");
                }
            }
            candidates = defensiveCopy;
        }

        /**
         * 把 S9A 的 deterministic candidate set 投影为创建期 snapshot：全部 plan 必须属于同一
         * languageProfileId，且仍为 deterministic（DETERMINISTIC_BUILT_IN_FALLBACK 且无
         * recommendationReason）；任何 enriched plan 都在持久化前 fail closed。
         */
        public static Snapshot fromCandidateSet(PlanningCandidateSet candidateSet) {
            Objects.requireNonNull(candidateSet, "candidateSet must not be null");
            List<LearningTaskPlan> plans = candidateSet.candidates();
            UUID languageProfileId = plans.getFirst().languageProfileId();
            List<Candidate> candidates = new ArrayList<>(plans.size());
            for (LearningTaskPlan plan : plans) {
                if (!languageProfileId.equals(plan.languageProfileId())) {
                    throw new IllegalArgumentException(
                            "candidate plans must share one languageProfileId");
                }
                if (plan.reason() != LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK
                        || plan.recommendationReason().isPresent()) {
                    throw new IllegalArgumentException(
                            "candidate plans must stay deterministic without recommendationReason");
                }
                candidates.add(new Candidate(candidates.size(), plan.materialIdentity()));
            }
            return new Snapshot(languageProfileId, candidates);
        }
    }

    /** snapshot 中一个 ordered candidate：位置 index 与 exact material identity。 */
    public record Candidate(int index, MaterialIdentity identity) {

        public Candidate {
            if (index < 0 || index >= MAXIMUM_CANDIDATE_COUNT) {
                throw new IllegalArgumentException(
                        "candidate index must be between 0 and " + (MAXIMUM_CANDIDATE_COUNT - 1));
            }
            Objects.requireNonNull(identity, "identity must not be null");
        }
    }
}
