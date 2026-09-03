package com.dailylanguage.content.domain;

/**
 * EXACT 由 material 的 acceptedAnswers 确定性判断；SEMANTIC_ONLY 只保存 learner text，
 * deterministic assessment 不声明语义正确或自然。
 */
public enum TextStepKind {
    EXACT,
    SEMANTIC_ONLY
}
