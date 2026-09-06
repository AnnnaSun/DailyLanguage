package com.dailylanguage.evaluator.domain;

import java.util.List;
import java.util.Objects;

import com.dailylanguage.evaluator.domain.SemanticEvaluationOutput.IssueType;

/**
 * 与 material semanticRubricReference 精确匹配的 versioned typed rubric resource。允许的
 * issue 集合是 Java grounding 校验的输入：rubric 外的 issue fail closed 为 UNSUPPORTED_ISSUE。
 * 语言差异进入 rubric 数据与后续 language pack，共用定位算法不分支。
 */
public record SemanticEvaluationRubric(
        String rubricReference,
        String targetLanguage,
        List<IssueDefinition> issueDefinitions) {

    public SemanticEvaluationRubric {
        requireNonBlank(rubricReference, "rubricReference");
        requireNonBlank(targetLanguage, "targetLanguage");
        Objects.requireNonNull(issueDefinitions, "issueDefinitions must not be null");
        if (issueDefinitions.isEmpty()) {
            throw new IllegalArgumentException("issueDefinitions must contain at least one issue");
        }
        issueDefinitions = List.copyOf(issueDefinitions);
        long distinctIssueTypes = issueDefinitions.stream().map(IssueDefinition::issueType).distinct().count();
        if (distinctIssueTypes != issueDefinitions.size()) {
            throw new IllegalArgumentException("issueDefinitions must not contain duplicate issueType");
        }
    }

    public boolean supports(IssueType issueType) {
        Objects.requireNonNull(issueType, "issueType must not be null");
        return issueDefinitions.stream().anyMatch(definition -> definition.issueType() == issueType);
    }

    /** issue 的允许范围与解释要求；只允许针对被引用 learner 文本的 Session-level diagnosis。 */
    public record IssueDefinition(IssueType issueType, String scope, String explanationRequirement) {

        public IssueDefinition {
            Objects.requireNonNull(issueType, "issueType must not be null");
            requireNonBlank(scope, "scope");
            requireNonBlank(explanationRequirement, "explanationRequirement");
        }
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
