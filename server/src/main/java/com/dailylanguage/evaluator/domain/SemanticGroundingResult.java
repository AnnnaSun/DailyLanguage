package com.dailylanguage.evaluator.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.evaluator.domain.SemanticEvaluationOutput.IssueType;

/**
 * Grounding 的互斥结果：Validated 只暴露经过 Java 校验的 Session-level candidate；Rejected
 * 只携带 failure category，不携带 learner text、完整 output 或 explanation。任一 claim 失败
 * 即整批拒绝。candidate 证明引用存在、来源正确、issue 在 rubric 内，不证明 diagnosis 语义
 * 必然正确，不得升级为 verified fact、Evidence qualification 或长期学习状态结论。
 */
public sealed interface SemanticGroundingResult permits
        SemanticGroundingResult.Validated,
        SemanticGroundingResult.Rejected {

    record Validated(ValidatedSemanticCandidate candidate) implements SemanticGroundingResult {

        public Validated {
            Objects.requireNonNull(candidate, "candidate must not be null");
        }
    }

    record Rejected(RejectionReason reason) implements SemanticGroundingResult {

        public Rejected {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /** 安全失败分类；同类内不区分具体 claim，避免泄露 output 细节。 */
    enum RejectionReason {
        /** JSON / record / enum binding 失败，或 output 违反自身 contract（如空白 explanation）。 */
        INVALID_STRUCTURE,
        /** trusted input 自身不一致（owner、profile、status、material、responses）。 */
        INVALID_INPUT,
        /** rubric reference 无法解析为该 target language 的已发布 rubric resource。 */
        RUBRIC_UNAVAILABLE,
        /** sourceTurnId 不能解析为当前 Session 的已接受 response。 */
        UNKNOWN_TURN,
        /** quote 为空白、在原始 learner text 中不存在，或匹配会切开 surrogate pair。 */
        QUOTE_MISMATCH,
        /** quote 多次匹配但 occurrenceIndex 未指定（-1）。 */
        AMBIGUOUS_OCCURRENCE,
        /** occurrenceIndex 非法（&lt; -1 或越过实际匹配数）。 */
        INVALID_OCCURRENCE,
        /** issueType 不在当前 rubric 的允许集合内。 */
        UNSUPPORTED_ISSUE,
        /** confidence 非有限或不在 [0,1]。 */
        INVALID_CONFIDENCE,
        /** claims 数量或 quote / explanation 长度超过上限。 */
        LIMIT_EXCEEDED
    }

    /** 通过全部 grounding 校验的 immutable Session-level candidate；identity 只到 profile/session/material。 */
    record ValidatedSemanticCandidate(
            UUID languageProfileId,
            UUID sessionId,
            MaterialIdentity materialIdentity,
            String rubricReference,
            String targetLanguage,
            String groundingPolicyVersion,
            List<GroundedClaim> claims) {

        public ValidatedSemanticCandidate {
            Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
            Objects.requireNonNull(sessionId, "sessionId must not be null");
            Objects.requireNonNull(materialIdentity, "materialIdentity must not be null");
            Objects.requireNonNull(rubricReference, "rubricReference must not be null");
            Objects.requireNonNull(targetLanguage, "targetLanguage must not be null");
            Objects.requireNonNull(groundingPolicyVersion, "groundingPolicyVersion must not be null");
            Objects.requireNonNull(claims, "claims must not be null");
            claims = List.copyOf(claims);
        }
    }

    /**
     * 单条已 grounding 的 claim。offsets 是 Java 计算的 UTF-16 code unit 区间
     * [startOffset, endOffset)；occurrenceIndex 是解析后的 0-based 实际匹配序号
     * （唯一匹配时模型的 -1 / 0 都归一为 0）。
     */
    record GroundedClaim(
            String sourceTurnId,
            String exactQuote,
            int occurrenceIndex,
            int startOffset,
            int endOffset,
            IssueType issueType,
            String explanation,
            double confidence) {

        public GroundedClaim {
            Objects.requireNonNull(sourceTurnId, "sourceTurnId must not be null");
            Objects.requireNonNull(exactQuote, "exactQuote must not be null");
            Objects.requireNonNull(issueType, "issueType must not be null");
            Objects.requireNonNull(explanation, "explanation must not be null");
        }
    }
}
