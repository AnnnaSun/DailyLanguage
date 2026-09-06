package com.dailylanguage.evaluator.domain;

import java.util.List;
import java.util.Objects;

/**
 * Evaluator 的不可信 Model output contract：字段集封闭，unknown / missing / null 字段由
 * StructuredOutputValidator 拒绝。Model 只能声明文字依据，不得提供 offsets、owner、profile
 * 或长期学习状态。occurrenceIndex 为 0-based 匹配序号，-1 表示未指定；真实 offsets 一律由
 * Java grounding 计算。claims 为空是合法空 candidate，不代表“没有语言问题”或“已经掌握”。
 */
public record SemanticEvaluationOutput(List<Claim> claims) {

    public SemanticEvaluationOutput {
        Objects.requireNonNull(claims, "claims must not be null");
        claims = List.copyOf(claims);
    }

    /** Session-level diagnosis 的封闭 issue 词汇表；可用子集由 target-language rubric 决定。 */
    public enum IssueType {
        GRAMMAR, NATURALNESS, TASK_RESPONSE
    }

    /**
     * 对当前 Session 内某个 learner response 的单条诊断声明。sourceTurnId 在 M1 中取 material
     * stepId，完整 identity 为 (sessionId, sourceTurnId)，不引入独立 turn UUID。
     */
    public record Claim(
            String sourceTurnId,
            String exactQuote,
            int occurrenceIndex,
            IssueType issueType,
            String explanation,
            double confidence) {

        public Claim {
            Objects.requireNonNull(sourceTurnId, "sourceTurnId must not be null");
            Objects.requireNonNull(exactQuote, "exactQuote must not be null");
            Objects.requireNonNull(issueType, "issueType must not be null");
            Objects.requireNonNull(explanation, "explanation must not be null");
        }
    }
}
