package com.dailylanguage.planner.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.planner.domain.PlannerEnrichmentResult;
import com.dailylanguage.planner.domain.PlannerEnrichmentResult.RejectionReason;

class PlannerEnrichmentOutputValidatorTests {

    private static final MaterialIdentity CAFE_IDENTITY =
            new MaterialIdentity("en-builtin-cafe-request", "v2");
    private static final MaterialIdentity GREETING_IDENTITY =
            new MaterialIdentity("en-builtin-greeting-intro", "v1");

    private final PlannerEnrichmentOutputValidator validator = new PlannerEnrichmentOutputValidator();

    @Test
    void acceptsOfferedExactIdentityAndCarriesMatchedIdentityWithNormalizedReason() {
        PlannerEnrichmentResult result = validator.validate(
                """
                        {"materialId":"en-builtin-greeting-intro","publishedVersion":"v1","recommendationReason":"  这个练习帮助你在咖啡店先理解示范，再尝试独立点单。  "}
                        """,
                PlannerEnrichmentTextRequestFactoryTests.candidateSet());

        assertThat(result).isEqualTo(new PlannerEnrichmentResult.Valid(
                GREETING_IDENTITY, "这个练习帮助你在咖啡店先理解示范，再尝试独立点单。"));
    }

    @Test
    void normalizesDecomposedReasonToNfcBeforeBounding() {
        PlannerEnrichmentResult result = validator.validate(
                """
                        {"materialId":"en-builtin-cafe-request","publishedVersion":"v2","recommendationReason":"cafe\u0301 ordering practice"}
                        """,
                PlannerEnrichmentTextRequestFactoryTests.candidateSet());

        assertThat(result).isEqualTo(new PlannerEnrichmentResult.Valid(
                CAFE_IDENTITY, "café ordering practice"));
    }

    @Test
    void boundsReasonByCodePointsNotUtf16Units() {
        String emojiReason = "😀".repeat(240);
        assertThat(emojiReason.length()).isEqualTo(480);

        assertThat(validator.validate(candidateJson(CAFE_IDENTITY, emojiReason),
                PlannerEnrichmentTextRequestFactoryTests.candidateSet()))
                .isEqualTo(new PlannerEnrichmentResult.Valid(CAFE_IDENTITY, emojiReason));
        assertThat(validator.validate(candidateJson(CAFE_IDENTITY, "😀".repeat(241)),
                PlannerEnrichmentTextRequestFactoryTests.candidateSet()))
                .isEqualTo(new PlannerEnrichmentResult.Rejected(RejectionReason.LIMIT_EXCEEDED));
    }

    @Test
    void rejectsUnknownOrPartialIdentity() {
        assertThat(validator.validate(
                candidateJson(new MaterialIdentity("en-builtin-unknown", "v1"), "reason"),
                PlannerEnrichmentTextRequestFactoryTests.candidateSet()))
                .isEqualTo(new PlannerEnrichmentResult.Rejected(RejectionReason.CANDIDATE_UNKNOWN));
        assertThat(validator.validate(
                candidateJson(new MaterialIdentity("en-builtin-cafe-request", "v1"), "reason"),
                PlannerEnrichmentTextRequestFactoryTests.candidateSet()))
                .isEqualTo(new PlannerEnrichmentResult.Rejected(RejectionReason.CANDIDATE_UNKNOWN));
    }

    @Test
    void rejectsExtraMissingNullFieldAsInvalidShape() {
        assertThat(validator.validate(
                """
                        {"materialId":"en-builtin-cafe-request","publishedVersion":"v2","recommendationReason":"reason","scenario":"rewritten"}
                        """,
                PlannerEnrichmentTextRequestFactoryTests.candidateSet()))
                .isEqualTo(new PlannerEnrichmentResult.Rejected(RejectionReason.SHAPE_INVALID));
        assertThat(validator.validate(
                """
                        {"materialId":"en-builtin-cafe-request","publishedVersion":"v2"}
                        """,
                PlannerEnrichmentTextRequestFactoryTests.candidateSet()))
                .isEqualTo(new PlannerEnrichmentResult.Rejected(RejectionReason.SHAPE_INVALID));
        assertThat(validator.validate(
                """
                        {"materialId":"en-builtin-cafe-request","publishedVersion":null,"recommendationReason":"reason"}
                        """,
                PlannerEnrichmentTextRequestFactoryTests.candidateSet()))
                .isEqualTo(new PlannerEnrichmentResult.Rejected(RejectionReason.SHAPE_INVALID));
    }

    @Test
    void rejectsWrongTokenTypeFieldWithoutScalarCoercion() {
        assertThat(validator.validate(
                """
                        {"materialId":123,"publishedVersion":"v2","recommendationReason":"reason"}
                        """,
                PlannerEnrichmentTextRequestFactoryTests.candidateSet()))
                .isEqualTo(new PlannerEnrichmentResult.Rejected(RejectionReason.SHAPE_INVALID));
    }

    @Test
    void rejectsNonObjectRootAsInvalidShape() {
        assertThat(validator.validate(
                "[\"en-builtin-cafe-request\"]",
                PlannerEnrichmentTextRequestFactoryTests.candidateSet()))
                .isEqualTo(new PlannerEnrichmentResult.Rejected(RejectionReason.SHAPE_INVALID));
    }

    @Test
    void rejectsMalformedFencedBlankAndTrailingJson() {
        assertThat(validator.validate(
                "```json\n{\"materialId\":\"en-builtin-cafe-request\"}\n```",
                PlannerEnrichmentTextRequestFactoryTests.candidateSet()))
                .isEqualTo(new PlannerEnrichmentResult.Rejected(RejectionReason.MALFORMED_JSON));
        assertThat(validator.validate(
                "   ",
                PlannerEnrichmentTextRequestFactoryTests.candidateSet()))
                .isEqualTo(new PlannerEnrichmentResult.Rejected(RejectionReason.MALFORMED_JSON));
        assertThat(validator.validate(
                candidateJson(CAFE_IDENTITY, "reason") + " trailing",
                PlannerEnrichmentTextRequestFactoryTests.candidateSet()))
                .isEqualTo(new PlannerEnrichmentResult.Rejected(RejectionReason.MALFORMED_JSON));
    }

    @Test
    void rejectsDuplicateKeyJson() {
        PlannerEnrichmentResult result = validator.validate(
                """
                        {"materialId":"en-builtin-cafe-request","materialId":"en-builtin-greeting-intro","publishedVersion":"v1","recommendationReason":"reason"}
                        """,
                PlannerEnrichmentTextRequestFactoryTests.candidateSet());

        assertThat(result).isInstanceOf(PlannerEnrichmentResult.Rejected.class);
    }

    @Test
    void rejectsBlankReasonAfterNormalization() {
        assertThat(validator.validate(
                candidateJson(CAFE_IDENTITY, "   "),
                PlannerEnrichmentTextRequestFactoryTests.candidateSet()))
                .isEqualTo(new PlannerEnrichmentResult.Rejected(RejectionReason.REASON_INVALID));
        assertThat(validator.validate(
                candidateJson(CAFE_IDENTITY, "\u3000"),
                PlannerEnrichmentTextRequestFactoryTests.candidateSet()))
                .isEqualTo(new PlannerEnrichmentResult.Rejected(RejectionReason.REASON_INVALID));
    }

    @Test
    void rejectsReasonWithLineBreakOrControlCharacter() {
        // JSON 转义后的换行 / control character 会通过 JSON 解析，由 reason 内容检查拒绝。
        assertThat(validator.validate(
                candidateJsonWithEscapedReason(CAFE_IDENTITY, "first line\\nsecond line"),
                PlannerEnrichmentTextRequestFactoryTests.candidateSet()))
                .isEqualTo(new PlannerEnrichmentResult.Rejected(RejectionReason.REASON_INVALID));
        assertThat(validator.validate(
                candidateJsonWithEscapedReason(CAFE_IDENTITY, "beep\\u0007"),
                PlannerEnrichmentTextRequestFactoryTests.candidateSet()))
                .isEqualTo(new PlannerEnrichmentResult.Rejected(RejectionReason.REASON_INVALID));
        assertThat(validator.validate(
                candidateJsonWithEscapedReason(CAFE_IDENTITY, "a\\u2028b"),
                PlannerEnrichmentTextRequestFactoryTests.candidateSet()))
                .isEqualTo(new PlannerEnrichmentResult.Rejected(RejectionReason.REASON_INVALID));
    }

    @Test
    void rejectsRawControlByteInsideJsonStringAsMalformed() {
        // 未经 JSON 转义的 raw control byte 本身就是非法 JSON，在解析层被拒绝。
        assertThat(validator.validate(
                candidateJson(CAFE_IDENTITY, "first line\nsecond line"),
                PlannerEnrichmentTextRequestFactoryTests.candidateSet()))
                .isEqualTo(new PlannerEnrichmentResult.Rejected(RejectionReason.MALFORMED_JSON));
    }

    @Test
    void rejectsOversizedRawJsonBeforeParsingWithoutEchoingIt() {
        String oversizedReason = "a".repeat(9_000);

        PlannerEnrichmentResult result = validator.validate(
                candidateJson(CAFE_IDENTITY, oversizedReason),
                PlannerEnrichmentTextRequestFactoryTests.candidateSet());

        assertThat(result)
                .isEqualTo(new PlannerEnrichmentResult.Rejected(RejectionReason.LIMIT_EXCEEDED));
        assertThat(result.toString()).doesNotContain(oversizedReason);
    }

    private static String candidateJson(MaterialIdentity identity, String reason) {
        return candidateJsonWithEscapedReason(identity, reason);
    }

    /** reason 以已 JSON 转义的形式（如 {@code \\n}、{@code \\u0007}）原样嵌入。 */
    private static String candidateJsonWithEscapedReason(MaterialIdentity identity, String jsonEscapedReason) {
        return "{\"materialId\":\"" + identity.materialId()
                + "\",\"publishedVersion\":\"" + identity.publishedVersion()
                + "\",\"recommendationReason\":\"" + jsonEscapedReason + "\"}";
    }
}
