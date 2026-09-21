package com.dailylanguage.planner.domain;

import java.util.List;

/**
 * Java hard filtering 产生的 deterministic candidate set：immutable、non-empty、ordered，
 * 最多 {@link #MAXIMUM_CANDIDATE_COUNT} 个元素。第一个 candidate 是唯一 deterministic fallback；
 * 其余 candidate 只是后续 optional Model soft decision 的 shortlist，且同样已经过 Java exact 校验。
 */
public final class PlanningCandidateSet {

    public static final int MAXIMUM_CANDIDATE_COUNT = 8;

    private final List<LearningTaskPlan> candidates;

    public PlanningCandidateSet(List<LearningTaskPlan> candidates) {
        // defensive copy 同时保证 null list / null element 与外部后续修改都被拒绝。
        List<LearningTaskPlan> defensiveCopy = List.copyOf(candidates);
        if (defensiveCopy.isEmpty()) {
            throw new IllegalArgumentException("candidates must not be empty");
        }
        if (defensiveCopy.size() > MAXIMUM_CANDIDATE_COUNT) {
            throw new IllegalArgumentException(
                    "candidates must not exceed " + MAXIMUM_CANDIDATE_COUNT + " entries");
        }
        this.candidates = defensiveCopy;
    }

    /** 唯一 deterministic fallback；无 Model 或 Model 失败时 Planning 必须落到这个 candidate。 */
    public LearningTaskPlan deterministicFallback() {
        return candidates.getFirst();
    }

    /** 按 stable order 排列的只读 candidate 视图，调用方不能增删或重排。 */
    public List<LearningTaskPlan> candidates() {
        return candidates;
    }
}
