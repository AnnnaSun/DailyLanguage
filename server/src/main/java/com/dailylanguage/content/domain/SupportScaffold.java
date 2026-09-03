package com.dailylanguage.content.domain;

/**
 * 辅助语言教学支架；contrastiveNote 只是 pair-relevant 教学提示，不构成对使用该 support language
 * 用户的长期学习事实假设。
 */
public record SupportScaffold(
        String supportLanguage,
        String instruction,
        String explanation,
        String hint,
        String contrastiveNote) {
}
