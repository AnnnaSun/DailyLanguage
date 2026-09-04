package com.dailylanguage.practice.domain;

import java.text.Normalizer;
import java.util.List;
import java.util.Objects;

/**
 * M1_TEXT_EXACT_V1 deterministic assessment policy：
 *
 * <pre>
 * submitted learner text
 * → strip outer whitespace
 * → Unicode NFC normalization
 * → case-sensitive exact comparison against each normalized acceptedAnswer
 * </pre>
 *
 * 明确不做：case folding、punctuation repair、internal whitespace collapsing、spelling correction、
 * semantic similarity、kanji/kana 或 word-order equivalence 推断。raw learner text 在
 * practice_response 中保持原样存储；normalization 只在本 policy 的比较计算内使用，不覆盖原文。
 * SEMANTIC_ONLY step 只产生 NOT_APPLICABLE，绝不声明 correctness、naturalness 或 task success。
 */
public final class DeterministicTextAssessmentPolicy {

    public static final String ASSESSMENT_POLICY_VERSION = "M1_TEXT_EXACT_V1";

    private DeterministicTextAssessmentPolicy() {
    }

    /**
     * 按持久化 stepKind 裁决单个 step 的 deterministic outcome。
     * EXACT 走 exact 比较；SEMANTIC_ONLY 固定 NOT_APPLICABLE。
     */
    public static DeterministicAssessment.StepOutcome outcomeFor(
            DeterministicAssessment.StepKind stepKind, String learnerText, List<String> acceptedAnswers) {
        Objects.requireNonNull(stepKind, "stepKind must not be null");
        return switch (stepKind) {
            case EXACT -> exactOutcome(learnerText, acceptedAnswers);
            case SEMANTIC_ONLY -> DeterministicAssessment.StepOutcome.NOT_APPLICABLE;
        };
    }

    /**
     * case-sensitive exact 比较。任一 normalized acceptedAnswer 等于 normalized learner text 即
     * MATCHED，否则 NOT_MATCHED；wrong-answer 不阻止 completion，只产生 NOT_MATCHED。
     */
    public static DeterministicAssessment.StepOutcome exactOutcome(
            String learnerText, List<String> acceptedAnswers) {
        Objects.requireNonNull(learnerText, "learnerText must not be null");
        String normalizedLearnerText = normalizeForExactMatch(learnerText);
        if (acceptedAnswers != null) {
            for (String acceptedAnswer : acceptedAnswers) {
                if (normalizedLearnerText.equals(normalizeForExactMatch(acceptedAnswer))) {
                    return DeterministicAssessment.StepOutcome.MATCHED;
                }
            }
        }
        return DeterministicAssessment.StepOutcome.NOT_MATCHED;
    }

    /** strip outer whitespace 后做 Unicode NFC normalization；不折叠内部空白、不改大小写。 */
    public static String normalizeForExactMatch(String text) {
        Objects.requireNonNull(text, "text must not be null");
        return Normalizer.normalize(text.strip(), Normalizer.Form.NFC);
    }
}
