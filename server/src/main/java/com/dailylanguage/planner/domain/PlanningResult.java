package com.dailylanguage.planner.domain;

import java.util.Objects;

/**
 * Planning 的互斥结果。Unavailable 是可预期的 fail-closed 业务结果，不要求调用方通过 exception
 * 推断无材料、时间不足或 Content contract 不一致。
 */
public sealed interface PlanningResult {

    record Planned(LearningTaskPlan task) implements PlanningResult {
        public Planned {
            Objects.requireNonNull(task, "task must not be null");
        }
    }

    record Unavailable(UnavailableReason reason) implements PlanningResult {
        public Unavailable {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    enum UnavailableReason {
        AVAILABLE_TIME_TOO_SHORT,
        NO_ELIGIBLE_MATERIAL,
        SELECTED_MATERIAL_UNAVAILABLE
    }
}
