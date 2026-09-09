package com.dailylanguage.evaluator.application;

import java.util.Objects;

import com.dailylanguage.evaluator.domain.GroundedEvaluationInput;

/**
 * Owner-scoped 读取 GroundedEvaluationInput 的互斥 Application 结果。只有 {@link Ready} 携带
 * 状态，且只携带已通过 ownership、completion 与 snapshot 完整性检查的 trusted input；其余变体
 * 都是不产生任何数据库 mutation、不携带 private text 的 fail-closed 结果。
 * {@link Ready} 只代表输入可用于 evaluation，不代表 Model diagnosis 正确，也不授权任何长期
 * 学习状态变化。
 */
public sealed interface GroundedEvaluationInputResult {

    record Ready(GroundedEvaluationInput input) implements GroundedEvaluationInputResult {

        public Ready {
            Objects.requireNonNull(input, "input must not be null");
        }
    }

    /** Session 不存在，或不属于该 caller/Profile；两者对外不可区分，且未读取任何 private text。 */
    record NotFound() implements GroundedEvaluationInputResult {
    }

    /** owned Session 存在但尚未 completed；不读取 assessment、responses 与 material。 */
    record NotCompleted() implements GroundedEvaluationInputResult {
    }

    /** Task 保存的 exact material identity + supportLanguage 无法解析出 material/scaffold。 */
    record MaterialUnavailable() implements GroundedEvaluationInputResult {
    }

    /** durable 数据或依赖返回不满足已完成 Practice 的结构约束；不静默拼装输入。 */
    record InconsistentSnapshot() implements GroundedEvaluationInputResult {
    }
}
