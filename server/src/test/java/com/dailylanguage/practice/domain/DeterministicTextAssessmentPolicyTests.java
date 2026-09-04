package com.dailylanguage.practice.domain;

import java.text.Normalizer;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.dailylanguage.practice.domain.DeterministicAssessment.StepKind;
import com.dailylanguage.practice.domain.DeterministicAssessment.StepOutcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeterministicTextAssessmentPolicyTests {

    private static final String ACCEPTED = "Could I have a medium coffee, please?";

    @Test
    void policyVersionIsPinnedToM1TextExactV1() {
        assertThat(DeterministicTextAssessmentPolicy.ASSESSMENT_POLICY_VERSION)
                .isEqualTo("M1_TEXT_EXACT_V1");
    }

    @Test
    void matchesExactTextAfterStrippingOuterWhitespace() {
        assertThat(DeterministicTextAssessmentPolicy.exactOutcome(
                "  Could I have a medium coffee, please?\t\n",
                List.of(ACCEPTED)))
                .isEqualTo(StepOutcome.MATCHED);
        // acceptedAnswers 同样只做 strip + NFC，不要求 material 侧预先归一。
        assertThat(DeterministicTextAssessmentPolicy.exactOutcome(
                ACCEPTED, List.of("  Could I have a medium coffee, please?  ")))
                .isEqualTo(StepOutcome.MATCHED);
    }

    @Test
    void nfcNormalizationMakesCanonicallyEquivalentTextMatch() {
        // "café"：NFC 单一 U+00E9 与 NFD「e + combining acute」在 NFC 比较下等价。
        String nfc = Normalizer.normalize("cafe\u00e9", Normalizer.Form.NFC);
        String nfd = Normalizer.normalize("cafe\u0065\u0301", Normalizer.Form.NFD);

        assertThat(DeterministicTextAssessmentPolicy.exactOutcome(nfd, List.of(nfc)))
                .isEqualTo(StepOutcome.MATCHED);
        assertThat(DeterministicTextAssessmentPolicy.normalizeForExactMatch(nfd))
                .isEqualTo(DeterministicTextAssessmentPolicy.normalizeForExactMatch(nfc))
                .isEqualTo(nfc);
    }

    @Test
    void comparisonStaysCaseSensitive() {
        assertThat(DeterministicTextAssessmentPolicy.exactOutcome(
                "could i have a medium coffee, please?", List.of(ACCEPTED)))
                .isEqualTo(StepOutcome.NOT_MATCHED);
        assertThat(DeterministicTextAssessmentPolicy.exactOutcome(
                "Could I Have A Medium Coffee, Please?", List.of(ACCEPTED)))
                .isEqualTo(StepOutcome.NOT_MATCHED);
    }

    @Test
    void noPunctuationRepairInternalWhitespaceCollapsingOrSpellingCorrection() {
        // 标点差异不修复。
        assertThat(DeterministicTextAssessmentPolicy.exactOutcome(
                "Could I have a medium coffee please", List.of(ACCEPTED)))
                .isEqualTo(StepOutcome.NOT_MATCHED);
        // 内部空白不折叠。
        assertThat(DeterministicTextAssessmentPolicy.exactOutcome(
                "Could I have a  medium coffee, please?", List.of(ACCEPTED)))
                .isEqualTo(StepOutcome.NOT_MATCHED);
        assertThat(DeterministicTextAssessmentPolicy.exactOutcome(
                "Could I have a\tmedium coffee, please?", List.of(ACCEPTED)))
                .isEqualTo(StepOutcome.NOT_MATCHED);
        // 拼写不纠正。
        assertThat(DeterministicTextAssessmentPolicy.exactOutcome(
                "Could I have a medium cofee, please?", List.of(ACCEPTED)))
                .isEqualTo(StepOutcome.NOT_MATCHED);
        // 词序不重排。
        assertThat(DeterministicTextAssessmentPolicy.exactOutcome(
                "A medium coffee could I have, please?", List.of(ACCEPTED)))
                .isEqualTo(StepOutcome.NOT_MATCHED);
    }

    @Test
    void kanjiKanaOrTransliterationEquivalenceIsNeverInferred() {
        // hiragana / katakana / romaji 之间的等价属于语义判断，M1 policy 只做 exact 比较。
        assertThat(DeterministicTextAssessmentPolicy.exactOutcome(
                "コーヒーをください", List.of("コーヒーをおねがいします")))
                .isEqualTo(StepOutcome.NOT_MATCHED);
        assertThat(DeterministicTextAssessmentPolicy.exactOutcome(
                "コーヒー", List.of("こーひー")))
                .isEqualTo(StepOutcome.NOT_MATCHED);
        assertThat(DeterministicTextAssessmentPolicy.exactOutcome(
                "koohii", List.of("コーヒー")))
                .isEqualTo(StepOutcome.NOT_MATCHED);
    }

    @Test
    void matchesAnyAcceptedAnswerWithoutPreference() {
        assertThat(DeterministicTextAssessmentPolicy.exactOutcome(
                "A large coffee, please.",
                List.of(ACCEPTED, "A large coffee, please.", "One espresso, thanks.")))
                .isEqualTo(StepOutcome.MATCHED);
        assertThat(DeterministicTextAssessmentPolicy.exactOutcome(
                "An espresso, thanks.",
                List.of(ACCEPTED, "A large coffee, please.", "One espresso, thanks.")))
                .isEqualTo(StepOutcome.NOT_MATCHED);
        // 空 acceptedAnswers 只产生 NOT_MATCHED，不抛异常。
        assertThat(DeterministicTextAssessmentPolicy.exactOutcome("any text", List.of()))
                .isEqualTo(StepOutcome.NOT_MATCHED);
        assertThat(DeterministicTextAssessmentPolicy.exactOutcome("any text", null))
                .isEqualTo(StepOutcome.NOT_MATCHED);
    }

    @Test
    void blankStoredTextCanNeverMatchAndNeverThrows() {
        // practice_response 契约排除了 blank text；此处仅保证 fail-closed 而非异常。
        assertThat(DeterministicTextAssessmentPolicy.exactOutcome("  ", List.of(ACCEPTED)))
                .isEqualTo(StepOutcome.NOT_MATCHED);
    }

    @Test
    void semanticOnlyStepsAlwaysProduceNotApplicable() {
        // SEMANTIC_ONLY 不伪造 correctness / naturalness：无论提交什么文本都只记录 NOT_APPLICABLE。
        assertThat(DeterministicTextAssessmentPolicy.outcomeFor(
                StepKind.SEMANTIC_ONLY, "To go, please.", List.of()))
                .isEqualTo(StepOutcome.NOT_APPLICABLE);
        assertThat(DeterministicTextAssessmentPolicy.outcomeFor(
                StepKind.SEMANTIC_ONLY, "anything at all", List.of("To go.")))
                .isEqualTo(StepOutcome.NOT_APPLICABLE);
        assertThat(DeterministicTextAssessmentPolicy.outcomeFor(
                StepKind.SEMANTIC_ONLY, "To go, please.", List.of("To go, please.")))
                .isEqualTo(StepOutcome.NOT_APPLICABLE);
    }

    @Test
    void exactStepsDelegateToTheExactComparisonRule() {
        assertThat(DeterministicTextAssessmentPolicy.outcomeFor(
                StepKind.EXACT, "  " + ACCEPTED + "  ", List.of(ACCEPTED)))
                .isEqualTo(StepOutcome.MATCHED);
        assertThat(DeterministicTextAssessmentPolicy.outcomeFor(
                StepKind.EXACT, "Something else", List.of(ACCEPTED)))
                .isEqualTo(StepOutcome.NOT_MATCHED);
    }

    @Test
    void normalizeRequiresNonNullText() {
        assertThatThrownBy(() -> DeterministicTextAssessmentPolicy.normalizeForExactMatch(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("text");
        assertThatThrownBy(() -> DeterministicTextAssessmentPolicy.exactOutcome(null, List.of(ACCEPTED)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("learnerText");
    }
}
