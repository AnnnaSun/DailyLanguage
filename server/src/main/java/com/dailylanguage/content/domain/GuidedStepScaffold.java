package com.dailylanguage.content.domain;

/**
 * Guided material 中单个 guided step 的 support-language 支架项：instruction 是该步骤的辅助语言指导，
 * responseFrame 是答案性表达框架（如 Could I have ___, please?），只允许用于 SCAFFOLDED_USE step——
 * 理解检查与独立迁移携带答案性辅助会削弱对应 evidence 的语义。它与 targetCore 的 acceptedAnswers、
 * semantic rubric 严格分离——只服务教学呈现，不参与 deterministic assessment，也不得混入评分依据。
 */
public record GuidedStepScaffold(String stepId, String instruction, String responseFrame) {
}
