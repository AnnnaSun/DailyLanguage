package com.dailylanguage.planner.application;

import java.util.Objects;

import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.domain.PlanningResult;

/**
 * Owner-scoped planning 的互斥 Application 结果。只有 {@link Created} 携带状态，且只携带数据库
 * 创建后的 durable {@link LearningTask}；其余变体都是不产生任何数据库 mutation 的 fail-closed 结果。
 */
public sealed interface LearningTaskPlanningResult {

    record Created(LearningTask task) implements LearningTaskPlanningResult {

        public Created {
            Objects.requireNonNull(task, "task must not be null");
        }
    }

    /** 请求字段违反 M1 planning 约束；在读取 Profile 前裁决，不携带内部 validation detail。 */
    record InvalidRequest() implements LearningTaskPlanningResult {
    }

    /** Profile 不存在或不属于 caller；两者不可区分，避免资源枚举。 */
    record LanguageProfileNotFound() implements LearningTaskPlanningResult {
    }

    /** Planner 的 typed unavailable 原因原样透传，由调用方决定 HTTP 语义。 */
    record Unavailable(PlanningResult.UnavailableReason reason) implements LearningTaskPlanningResult {

        public Unavailable {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }
}
