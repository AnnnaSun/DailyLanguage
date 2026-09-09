package com.dailylanguage.content.domain;

/**
 * step 的教学目的，与 {@link TextStepKind}（评价方式）正交：learningPurpose 说明这一步在教学上承担什么，
 * kind 说明 learner response 如何被评价。旧 artifact 缺失 learningPurpose 时解释为 PRACTICE，
 * 因此 PRACTICE 同时覆盖 M1 普通练习步骤和全部 legacy material。
 */
public enum TextLearningPurpose {
    /** M1 普通练习步骤，也是缺失字段时的 legacy 解释值。 */
    PRACTICE,
    /** 检查 learner 是否理解公开示范（targetLanguageText / explanation）。 */
    COMPREHENSION_CHECK,
    /** 在 responseFrame 等答案性支架辅助下使用目标表达。 */
    SCAFFOLDED_USE,
    /** 不带答案性支架的独立迁移使用。 */
    INDEPENDENT_TRANSFER
}
