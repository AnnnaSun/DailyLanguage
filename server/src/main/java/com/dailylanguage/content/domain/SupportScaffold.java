package com.dailylanguage.content.domain;

import java.util.List;

/**
 * 辅助语言教学支架；contrastiveNote 只是 pair-relevant 教学提示，不构成对使用该 support language
 * 用户的长期学习事实假设。guidedSteps 为 guided material 的每个 guided step 提供逐项支架；
 * legacy artifact 缺失该字段时解释为空集合。
 */
public record SupportScaffold(
        String supportLanguage,
        String instruction,
        String explanation,
        String hint,
        String contrastiveNote,
        List<GuidedStepScaffold> guidedSteps) {

    public SupportScaffold {
        guidedSteps = guidedSteps == null ? List.of() : List.copyOf(guidedSteps);
    }
}
