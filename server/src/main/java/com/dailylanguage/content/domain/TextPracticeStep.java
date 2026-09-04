package com.dailylanguage.content.domain;

import java.util.List;

/**
 * material 定义的一个 typed text 交互步骤；learner response 将以 sessionId + stepId 建立稳定 identity，
 * 因此 stepId 在 material 内必须唯一。acceptedAnswers 的非空约束由 kind 决定。
 */
public record TextPracticeStep(String stepId, TextStepKind kind, String prompt, List<String> acceptedAnswers) {

    public TextPracticeStep {
        acceptedAnswers = acceptedAnswers == null ? null : List.copyOf(acceptedAnswers);
    }
}
