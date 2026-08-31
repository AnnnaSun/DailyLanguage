package com.dailylanguage.modelgateway.structuredoutput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Objects;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StructuredOutputValidatorTests {

    private final StructuredOutputValidator validator =
            new StructuredOutputValidator(JsonMapper.builder().build());

    @Test
    void returnsTypedRecordForAValidJsonObject() {
        StructuredOutputValidation<EvaluationOutput> result = validator.validateJsonObject(
                """
                        {"result":"PASS","feedback":"Natural answer"}
                        """,
                EvaluationOutput.class);

        assertThat(result).isEqualTo(StructuredOutputValidation.valid(
                new EvaluationOutput(EvaluationResult.PASS, "Natural answer")));
    }

    @Test
    void rejectsMalformedOrDecoratedJsonWithoutExposingTheGeneratedText() {
        String unsafeGeneratedText = "```json\n{\"result\":\"PASS\"}\n```";

        StructuredOutputValidation<EvaluationOutput> empty = validator.validateJsonObject(
                "",
                EvaluationOutput.class);
        StructuredOutputValidation<EvaluationOutput> blank = validator.validateJsonObject(
                "   ",
                EvaluationOutput.class);
        StructuredOutputValidation<EvaluationOutput> fenced = validator.validateJsonObject(
                unsafeGeneratedText,
                EvaluationOutput.class);
        StructuredOutputValidation<EvaluationOutput> trailing = validator.validateJsonObject(
                "{\"result\":\"PASS\",\"feedback\":\"ok\"} trailing",
                EvaluationOutput.class);

        assertThat(empty).isEqualTo(StructuredOutputValidation.invalid(
                StructuredOutputFailure.MALFORMED_JSON));
        assertThat(blank).isEqualTo(StructuredOutputValidation.invalid(
                StructuredOutputFailure.MALFORMED_JSON));
        assertThat(fenced).isEqualTo(StructuredOutputValidation.invalid(
                StructuredOutputFailure.MALFORMED_JSON));
        assertThat(trailing).isEqualTo(StructuredOutputValidation.invalid(
                StructuredOutputFailure.MALFORMED_JSON));
        assertThat(fenced.toString()).doesNotContain(unsafeGeneratedText, "result", "PASS");
    }

    @Test
    void rejectsNonObjectRootAsInvalidShape() {
        StructuredOutputValidation<EvaluationOutput> result = validator.validateJsonObject(
                "[]",
                EvaluationOutput.class);

        assertThat(result).isEqualTo(StructuredOutputValidation.invalid(
                StructuredOutputFailure.SHAPE_INVALID));
    }

    @Test
    void rejectsMissingUnknownOrWronglyTypedFieldsAsInvalidShape() {
        StructuredOutputValidation<EvaluationOutput> missing = validator.validateJsonObject(
                "{\"result\":\"PASS\"}",
                EvaluationOutput.class);
        StructuredOutputValidation<EvaluationOutput> unknown = validator.validateJsonObject(
                "{\"result\":\"PASS\",\"feedback\":\"ok\",\"score\":1}",
                EvaluationOutput.class);
        StructuredOutputValidation<EvaluationOutput> wrongType = validator.validateJsonObject(
                "{\"result\":\"PASS\",\"feedback\":{}}",
                EvaluationOutput.class);

        assertThat(missing).isEqualTo(StructuredOutputValidation.invalid(
                StructuredOutputFailure.SHAPE_INVALID));
        assertThat(unknown).isEqualTo(StructuredOutputValidation.invalid(
                StructuredOutputFailure.SHAPE_INVALID));
        assertThat(wrongType).isEqualTo(StructuredOutputValidation.invalid(
                StructuredOutputFailure.SHAPE_INVALID));
    }

    @Test
    void distinguishesUnknownEnumTokenFromOtherShapeFailures() {
        StructuredOutputValidation<EvaluationOutput> result = validator.validateJsonObject(
                "{\"result\":\"UNSURE\",\"feedback\":\"ok\"}",
                EvaluationOutput.class);

        assertThat(result).isEqualTo(StructuredOutputValidation.invalid(
                StructuredOutputFailure.ENUM_INVALID));
    }

    @Test
    void runsSemanticRuleOnlyAfterTypedBinding() {
        StructuredOutputValidation<EvaluationOutput> result = validator.validateJsonObject(
                "{\"result\":\"PASS\",\"feedback\":\"too short\"}",
                EvaluationOutput.class,
                output -> output.feedback().length() >= 20);

        assertThat(result).isEqualTo(StructuredOutputValidation.invalid(
                StructuredOutputFailure.SEMANTIC_INVALID));
    }

    @Test
    void rejectsNonRecordOutputTypeAsProgrammingMisuse() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validator.validateJsonObject("{}", String.class))
                .withMessage("outputType must be a record");
    }

    private record EvaluationOutput(EvaluationResult result, String feedback) {

        private EvaluationOutput {
            Objects.requireNonNull(result, "result must not be null");
            Objects.requireNonNull(feedback, "feedback must not be null");
        }
    }

    private enum EvaluationResult {
        PASS,
        FAIL
    }
}
