package com.dailylanguage.content.domain;

import java.util.List;

/**
 * 由 target language 决定的练习核心。语言差异作为数据进入 core / scaffold 与 rubric resource，
 * 不在 Application Service 中散落语言分支。
 */
public record TargetPracticeCore(
        String targetLanguage,
        MaterialDifficulty difficulty,
        String scenario,
        String communicationObjective,
        /** learner 实际阅读的 target-language 场景文本。 */
        String targetLanguageText,
        TextReadingInfo readingInfo,
        List<TextPracticeStep> steps,
        /** versioned semantic rubric 引用，供后续 Grounded Evaluator 消费。 */
        String semanticRubricReference) {
}
