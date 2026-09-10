package com.dailylanguage.content.domain;

import java.util.List;

/**
 * material 定义的一个 typed text 交互步骤；learner response 将以 sessionId + stepId 建立稳定 identity，
 * 因此 stepId 在 material 内必须唯一。acceptedAnswers 的非空约束由 kind 决定。
 * learningPurpose 与 kind 正交：前者是教学目的，后者是评价方式；缺失 learningPurpose 解释为 PRACTICE，
 * 使 legacy artifact 无需改写即可按原行为加载。
 */
public record TextPracticeStep(
        String stepId,
        TextStepKind kind,
        TextLearningPurpose learningPurpose,
        String prompt,
        List<String> acceptedAnswers) {

    public TextPracticeStep {
        learningPurpose = learningPurpose == null ? TextLearningPurpose.PRACTICE : learningPurpose;
        acceptedAnswers = acceptedAnswers == null ? null : List.copyOf(acceptedAnswers);
    }
}
