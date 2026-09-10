package com.dailylanguage.planner.domain;

import java.util.Objects;

/**
 * candidate set 读取的互斥结果。Unavailable 复用 {@link PlanningResult.UnavailableReason}，
 * 使 deterministic Planner 可以直接映射到既有 typed result，不引入第二套 reason 语义。
 */
public sealed interface PlanningCandidateSetResult {

    record Available(PlanningCandidateSet candidateSet) implements PlanningCandidateSetResult {
        public Available {
            Objects.requireNonNull(candidateSet, "candidateSet must not be null");
        }
    }

    record Unavailable(PlanningResult.UnavailableReason reason) implements PlanningCandidateSetResult {
        public Unavailable {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }
}
