package com.dailylanguage.evaluator.application;

import java.lang.reflect.RecordComponent;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.json.JsonMapper;

import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.content.domain.MaterialSourceLineage;
import com.dailylanguage.content.domain.PublishedLearningMaterial;
import com.dailylanguage.content.domain.SupportScaffold;
import com.dailylanguage.content.domain.TargetPracticeCore;
import com.dailylanguage.content.domain.TextPracticeStep;
import com.dailylanguage.content.domain.TextStepKind;
import com.dailylanguage.evaluator.domain.GroundedEvaluationInput;
import com.dailylanguage.evaluator.domain.SemanticEvaluationOutput.IssueType;
import com.dailylanguage.evaluator.domain.SemanticEvaluationRubric;
import com.dailylanguage.evaluator.domain.SemanticEvaluationRubric.IssueDefinition;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.GroundedClaim;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.RejectionReason;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.Validated;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.ValidatedSemanticCandidate;
import com.dailylanguage.modelgateway.structuredoutput.StructuredOutputValidator;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.practice.domain.DeterministicAssessment;
import com.dailylanguage.practice.domain.DeterministicAssessment.StepKind;
import com.dailylanguage.practice.domain.DeterministicAssessment.StepOutcome;
import com.dailylanguage.practice.domain.DeterministicAssessment.StepResult;
import com.dailylanguage.practice.domain.DeterministicTextAssessmentPolicy;
import com.dailylanguage.practice.domain.PracticeSession;
import com.dailylanguage.practice.domain.PracticeSession.LearnerResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticGroundingValidatorTests {

    private static final String RUBRIC_REFERENCE = "builtin-text-communication-rubric/v1";
    private static final UUID USER_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000051");
    private static final UUID PROFILE_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000052");
    private static final UUID OTHER_PROFILE_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000053");
    private static final UUID TASK_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000054");
    private static final UUID SESSION_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000055");
    private static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-09-05T10:15:30.123Z");
    private static final OffsetDateTime SUBMITTED_AT = OffsetDateTime.parse("2026-09-05T10:18:00.456Z");
    private static final OffsetDateTime COMPLETED_AT = OffsetDateTime.parse("2026-09-05T10:22:00.123Z");
    private static final MaterialIdentity CAFE_IDENTITY =
            new MaterialIdentity("en-builtin-cafe-request", "v1");
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    // 真实 classpath rubric：unit 测试同时回归 built-in English resource 的实际可用性。
    private final SemanticGroundingValidator validator = new SemanticGroundingValidator(
            new StructuredOutputValidator(JsonMapper.builder().build()),
            new SemanticGroundingValidator.ClasspathRubricSource());

    // --- happy path ---

    @Test
    void groundsValidClaimWithJavaComputedOffsets() {
        String learnerText = "To go, please. Thank you so much!";

        SemanticGroundingResult result = validator.validate(
                modelOutput(claim("answer-to-go", "Thank you", -1, "NATURALNESS",
                        "The quoted thanks reads as abrupt in this scenario.", 0.7)),
                validInput());

        assertThat(result).isInstanceOfSatisfying(Validated.class, validated -> {
            ValidatedSemanticCandidate candidate = validated.candidate();
            assertThat(candidate.languageProfileId()).isEqualTo(PROFILE_ID);
            assertThat(candidate.sessionId()).isEqualTo(SESSION_ID);
            assertThat(candidate.materialIdentity()).isEqualTo(CAFE_IDENTITY);
            assertThat(candidate.rubricReference()).isEqualTo(RUBRIC_REFERENCE);
            assertThat(candidate.targetLanguage()).isEqualTo("en");
            assertThat(candidate.groundingPolicyVersion())
                    .isEqualTo(SemanticGroundingValidator.GROUNDING_POLICY_VERSION);
            assertThat(candidate.claims()).hasSize(1);
            GroundedClaim claim = candidate.claims().getFirst();
            assertThat(claim.sourceTurnId()).isEqualTo("answer-to-go");
            assertThat(claim.exactQuote()).isEqualTo("Thank you");
            // 唯一匹配的 -1 归一为 0；offsets 由 Java 计算且可在原始 learner text 上复原。
            assertThat(claim.occurrenceIndex()).isZero();
            assertThat(claim.startOffset()).isEqualTo(learnerText.indexOf("Thank you"));
            assertThat(learnerText.substring(claim.startOffset(), claim.endOffset())).isEqualTo("Thank you");
            assertThat(claim.issueType()).isEqualTo(IssueType.NATURALNESS);
            assertThat(claim.explanation()).isEqualTo("The quoted thanks reads as abrupt in this scenario.");
            assertThat(claim.confidence()).isEqualTo(0.7);
        });
    }

    @Test
    void emptyClaimsIsValidEmptyCandidate() {
        SemanticGroundingResult result = validator.validate("{\"claims\":[]}", validInput());

        assertThat(result).isInstanceOfSatisfying(Validated.class, validated ->
                assertThat(validated.candidate().claims()).isEmpty());
    }

    @Test
    void groundsMultipleClaimsAcrossDifferentTurns() {
        SemanticGroundingResult result = validator.validate(
                modelOutput(
                        claim("order-drink", "medium coffee", -1, "GRAMMAR",
                                "Article use in the quoted span is fine but tense is off.", 0.9),
                        claim("answer-to-go", "Thank you", -1, "NATURALNESS",
                                "The quoted thanks reads as abrupt in this scenario.", 0.6)),
                validInput());

        assertThat(result).isInstanceOfSatisfying(Validated.class, validated -> {
            List<GroundedClaim> claims = validated.candidate().claims();
            assertThat(claims).hasSize(2);
            assertThat(claims.get(0).sourceTurnId()).isEqualTo("order-drink");
            assertThat(claims.get(1).sourceTurnId()).isEqualTo("answer-to-go");
        });
    }

    // --- occurrence rules ---

    @Test
    void uniqueMatchAcceptsUnspecifiedOrZeroOccurrenceOnly() {
        assertThat(validator.validate(
                modelOutput(claim("answer-to-go", "Thank you", 0, "NATURALNESS", "ok", 0.5)),
                validInput()))
                .isInstanceOf(Validated.class);
        assertThat(validator.validate(
                modelOutput(claim("answer-to-go", "Thank you", 1, "NATURALNESS", "ok", 0.5)),
                validInput()))
                .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.INVALID_OCCURRENCE));
    }

    @Test
    void ambiguousOccurrenceFollowsContractGoGoExample() {
        // contract 例："go go" 引用 "go"：未指定 occurrence 拒绝；index 1 得到 [3,5)。
        GroundedEvaluationInput input = inputWithAnswerToGoText("go go");

        assertThat(validator.validate(
                modelOutput(claim("answer-to-go", "go", -1, "GRAMMAR", "ok", 0.5)), input))
                .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.AMBIGUOUS_OCCURRENCE));
        assertThat(validator.validate(
                modelOutput(claim("answer-to-go", "go", 1, "GRAMMAR", "ok", 0.5)), input))
                .isInstanceOfSatisfying(Validated.class, validated -> {
                    GroundedClaim claim = validated.candidate().claims().getFirst();
                    assertThat(claim.startOffset()).isEqualTo(3);
                    assertThat(claim.endOffset()).isEqualTo(5);
                });
    }

    @Test
    void overlappingOccurrencesAreCountedForAmbiguityAndResolution() {
        GroundedEvaluationInput input = inputWithAnswerToGoText("aaa");

        assertThat(validator.validate(
                modelOutput(claim("answer-to-go", "aa", -1, "GRAMMAR", "ok", 0.5)), input))
                .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.AMBIGUOUS_OCCURRENCE));
        assertThat(validator.validate(
                modelOutput(claim("answer-to-go", "aa", 1, "GRAMMAR", "ok", 0.5)), input))
                .isInstanceOfSatisfying(Validated.class, validated -> {
                    GroundedClaim claim = validated.candidate().claims().getFirst();
                    assertThat(claim.startOffset()).isEqualTo(1);
                    assertThat(claim.endOffset()).isEqualTo(3);
                });
    }

    @Test
    void outOfRangeAndBelowMinusOneOccurrencesAreRejected() {
        GroundedEvaluationInput input = inputWithAnswerToGoText("go go");

        assertThat(validator.validate(
                modelOutput(claim("answer-to-go", "go", 2, "GRAMMAR", "ok", 0.5)), input))
                .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.INVALID_OCCURRENCE));
        assertThat(validator.validate(
                modelOutput(claim("answer-to-go", "go", -2, "GRAMMAR", "ok", 0.5)), input))
                .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.INVALID_OCCURRENCE));
    }

    // --- structure / output contract ---

    @ParameterizedTest
    @ValueSource(strings = {"not json", "", "[]", "{\"nope\":true}", "{\"claims\":[} {\"claims\":[]}"})
    void malformedOrNonObjectJsonIsRejectedAsInvalidStructure(String generatedJson) {
        assertThat(validator.validate(generatedJson, validInput()))
                .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.INVALID_STRUCTURE));
    }

    @Test
    void forgedOffsetsUnknownMissingAndNullFieldsAreRejectedAsInvalidStructure() {
        String forgedOffsets = "{\"claims\":[{\"sourceTurnId\":\"answer-to-go\",\"exactQuote\":\"Thank you\","
                + "\"occurrenceIndex\":-1,\"issueType\":\"NATURALNESS\",\"explanation\":\"ok\","
                + "\"confidence\":0.5,\"startOffset\":0,\"endOffset\":9}]}";
        String missingOccurrence = "{\"claims\":[{\"sourceTurnId\":\"answer-to-go\",\"exactQuote\":\"Thank you\","
                + "\"issueType\":\"NATURALNESS\",\"explanation\":\"ok\",\"confidence\":0.5}]}";
        String nullOccurrence = "{\"claims\":[{\"sourceTurnId\":\"answer-to-go\",\"exactQuote\":\"Thank you\","
                + "\"occurrenceIndex\":null,\"issueType\":\"NATURALNESS\",\"explanation\":\"ok\",\"confidence\":0.5}]}";
        String nullClaims = "{\"claims\":null}";

        for (String generatedJson : List.of(forgedOffsets, missingOccurrence, nullOccurrence, nullClaims)) {
            assertThat(validator.validate(generatedJson, validInput()))
                    .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.INVALID_STRUCTURE));
        }
    }

    @Test
    void invalidEnumTokenAndNullClaimElementAreRejectedAsInvalidStructure() {
        assertThat(validator.validate(
                modelOutput(claim("answer-to-go", "Thank you", -1, "PRONUNCIATION", "ok", 0.5)),
                validInput()))
                .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.INVALID_STRUCTURE));
        assertThat(validator.validate("{\"claims\":[null]}", validInput()))
                .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.INVALID_STRUCTURE));
    }

    @Test
    void blankExplanationIsInvalidStructureAndLongExplanationHitsTheLimit() {
        assertThat(validator.validate(
                modelOutput(claim("answer-to-go", "Thank you", -1, "NATURALNESS", "   ", 0.5)),
                validInput()))
                .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.INVALID_STRUCTURE));
        assertThat(validator.validate(
                modelOutput(claim("answer-to-go", "Thank you", -1, "NATURALNESS", "a".repeat(1001), 0.5)),
                validInput()))
                .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.LIMIT_EXCEEDED));
        // 恰好 1000 code points 仍可接受。
        assertThat(validator.validate(
                modelOutput(claim("answer-to-go", "Thank you", -1, "NATURALNESS", "a".repeat(1000), 0.5)),
                validInput()))
                .isInstanceOf(Validated.class);
    }

    @Test
    void blankQuoteIsQuoteMismatchAndOverlongQuoteHitsTheLimit() {
        assertThat(validator.validate(
                modelOutput(claim("answer-to-go", "  ", -1, "NATURALNESS", "ok", 0.5)),
                validInput()))
                .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.QUOTE_MISMATCH));
        assertThat(validator.validate(
                modelOutput(claim("answer-to-go", "a".repeat(2001), -1, "NATURALNESS", "ok", 0.5)),
                validInput()))
                .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.LIMIT_EXCEEDED));
    }

    @Test
    void claimCountOverTwentyIsRejectedAsLimitExceeded() {
        String singleClaim = claim("answer-to-go", "Thank you", -1, "NATURALNESS", "ok", 0.5);
        String twentyOneClaims = (singleClaim + ",").repeat(20) + singleClaim;

        assertThat(validator.validate(modelOutput(twentyOneClaims), validInput()))
                .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.LIMIT_EXCEEDED));
        // 恰好 20 claims 仍可接受。
        String twentyClaims = (singleClaim + ",").repeat(19) + singleClaim;
        assertThat(validator.validate(modelOutput(twentyClaims), validInput()))
                .isInstanceOf(Validated.class);
    }

    @Test
    void confidenceOutsideTheFiniteUnitIntervalIsRejected() {
        for (String confidence : List.of("1.5", "-0.01", "1e999")) {
            String generatedJson = modelOutput(
                    claim("answer-to-go", "Thank you", -1, "NATURALNESS", "ok", confidence));
            assertThat(validator.validate(generatedJson, validInput()))
                    .as("confidence %s", confidence)
                    .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.INVALID_CONFIDENCE));
        }
        assertThat(validator.validate(
                modelOutput(claim("answer-to-go", "Thank you", -1, "NATURALNESS", "ok", "0.0")),
                validInput()))
                .isInstanceOf(Validated.class);
    }

    @Test
    void illegalScalarTokenTypesAreRejectedInsteadOfBeingCoerced() {
        // Codex review HIGH 回归：共享 binding 默认 coercion 会把 1.9 截断为 1、"1" 转成 1、
        // 0 映射成 enum；Evaluator 输入边界必须按 JSON token 真实类型拒绝，不允许静默转换。
        List<String> occurrenceTokens = List.of("1.9", "1.0", "-1.5", "\"1\"", "true", "null", "1e2",
                "100000000000000000000000");
        for (String occurrenceToken : occurrenceTokens) {
            assertThat(validator.validate(
                    modelOutput(rawClaim("\"answer-to-go\"", "\"Thank you\"", occurrenceToken,
                            "\"NATURALNESS\"", "\"ok\"", "0.5")),
                    validInput()))
                    .as("occurrenceIndex %s", occurrenceToken)
                    .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.INVALID_STRUCTURE));
        }
        List<String> issueTypeTokens = List.of("0", "1", "true", "null");
        for (String issueTypeToken : issueTypeTokens) {
            assertThat(validator.validate(
                    modelOutput(rawClaim("\"answer-to-go\"", "\"Thank you\"", "-1",
                            issueTypeToken, "\"ok\"", "0.5")),
                    validInput()))
                    .as("issueType %s", issueTypeToken)
                    .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.INVALID_STRUCTURE));
        }
        List<String> textualFieldTokens = List.of("12345", "true", "null", "1.9");
        for (String token : textualFieldTokens) {
            assertThat(validator.validate(
                    modelOutput(rawClaim(token, "\"Thank you\"", "-1", "\"NATURALNESS\"", "\"ok\"", "0.5")),
                    validInput()))
                    .as("sourceTurnId %s", token)
                    .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.INVALID_STRUCTURE));
            assertThat(validator.validate(
                    modelOutput(rawClaim("\"answer-to-go\"", token, "-1", "\"NATURALNESS\"", "\"ok\"", "0.5")),
                    validInput()))
                    .as("exactQuote %s", token)
                    .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.INVALID_STRUCTURE));
            assertThat(validator.validate(
                    modelOutput(rawClaim("\"answer-to-go\"", "\"Thank you\"", "-1", "\"NATURALNESS\"",
                            token, "0.5")),
                    validInput()))
                    .as("explanation %s", token)
                    .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.INVALID_STRUCTURE));
        }
        // confidence 必须是 number token：字符串（含 "NaN"/"Infinity"）与 boolean 都不允许。
        for (String confidenceToken : List.of("\"0.8\"", "\"NaN\"", "\"Infinity\"", "true", "null")) {
            assertThat(validator.validate(
                    modelOutput(rawClaim("\"answer-to-go\"", "\"Thank you\"", "-1", "\"NATURALNESS\"",
                            "\"ok\"", confidenceToken)),
                    validInput()))
                    .as("confidence %s", confidenceToken)
                    .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.INVALID_STRUCTURE));
        }
        // claims 容器与 claim 元素的 token 类型同样封闭。
        for (String generatedJson : List.of(
                "{\"claims\":\"not an array\"}",
                "{\"claims\":{\"sourceTurnId\":\"answer-to-go\"}}",
                "{\"claims\":[\"not an object\"]}",
                "{\"claims\":[1.9]}",
                "{\"claims\":[null]}")) {
            assertThat(validator.validate(generatedJson, validInput()))
                    .as("%s", generatedJson)
                    .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.INVALID_STRUCTURE));
        }
        // 合法 token（整数 occurrence、number confidence）不受防线影响。
        assertThat(validator.validate(
                modelOutput(rawClaim("\"answer-to-go\"", "\"Thank you\"", "-1", "\"NATURALNESS\"",
                        "\"ok\"", "0.5")),
                validInput()))
                .isInstanceOf(Validated.class);
    }

    @Test
    void codexReproFractionalOccurrenceNeverProducesAGroundedSpan() {
        // Codex 复现例：learner text "go go"、quote "go"、occurrenceIndex 1.9 —— 修复前被截断为 1
        // 并错误产出第二次匹配 [3,5)；修复后必须整批拒绝且不产生任何 candidate。
        GroundedEvaluationInput input = inputWithAnswerToGoText("go go");

        SemanticGroundingResult result = validator.validate(
                modelOutput(rawClaim("\"answer-to-go\"", "\"go\"", "1.9", "\"GRAMMAR\"", "\"ok\"", "0.5")),
                input);

        assertThat(result).isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.INVALID_STRUCTURE));
    }

    // --- trusted input consistency ---

    @Test
    void ownerOrProfileMismatchOnTaskIsRejectedAsInvalidInput() {
        GroundedEvaluationInput wrongOwner = new GroundedEvaluationInput(
                UUID.randomUUID(), PROFILE_ID, completedTask(), completedSession(), assessment(),
                standardResponses(), cafeMaterial());
        GroundedEvaluationInput wrongProfile = new GroundedEvaluationInput(
                USER_ID, OTHER_PROFILE_ID, completedTask(), completedSession(), assessment(),
                standardResponses(), cafeMaterial());

        for (GroundedEvaluationInput input : List.of(wrongOwner, wrongProfile)) {
            assertThat(validator.validate(validClaimJson(), input))
                    .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.INVALID_INPUT));
        }
    }

    @Test
    void sessionTaskAssessmentBelongingAndStatusMismatchesAreRejectedAsInvalidInput() {
        PracticeSession sessionOfOtherTask = new PracticeSession(
                UUID.randomUUID(), UUID.randomUUID(), PracticeSession.Status.COMPLETED,
                STARTED_AT, Optional.of(COMPLETED_AT), Optional.empty());
        PracticeSession inProgressSession = new PracticeSession(
                SESSION_ID, TASK_ID, PracticeSession.Status.IN_PROGRESS,
                STARTED_AT, Optional.empty(), Optional.empty());
        LearningTask startedTask = task(LearningTask.Status.STARTED);
        DeterministicAssessment foreignAssessment = new DeterministicAssessment(
                UUID.randomUUID(), DeterministicTextAssessmentPolicy.ASSESSMENT_POLICY_VERSION,
                10L, COMPLETED_AT, List.of(new StepResult("order-drink", StepKind.EXACT, StepOutcome.MATCHED)));

        List<GroundedEvaluationInput> invalidInputs = List.of(
                input(completedTask(), sessionOfOtherTask, assessment(), standardResponses(), cafeMaterial()),
                input(completedTask(), inProgressSession, assessment(), standardResponses(), cafeMaterial()),
                input(startedTask, completedSession(), assessment(), standardResponses(), cafeMaterial()),
                input(completedTask(), completedSession(), foreignAssessment, standardResponses(), cafeMaterial()));

        for (GroundedEvaluationInput input : invalidInputs) {
            assertThat(validator.validate(validClaimJson(), input))
                    .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.INVALID_INPUT));
        }
    }

    @Test
    void materialIdentityOrTargetLanguageMismatchIsRejectedAsInvalidInput() {
        PublishedLearningMaterial otherVersion = materialWithIdentity(
                new MaterialIdentity("en-builtin-cafe-request", "v2"));
        PublishedLearningMaterial japaneseMaterial = material(RUBRIC_REFERENCE, "ja");

        List<GroundedEvaluationInput> invalidInputs = List.of(
                input(completedTask(), completedSession(), assessment(), standardResponses(), otherVersion),
                input(completedTask(), completedSession(), assessment(), standardResponses(), japaneseMaterial));

        for (GroundedEvaluationInput input : invalidInputs) {
            assertThat(validator.validate(validClaimJson(), input))
                    .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.INVALID_INPUT));
        }
    }

    @Test
    void responsesOutsideSessionMaterialOrIncompleteSetsAreRejectedAsInvalidInput() {
        LearnerResponse foreignSessionResponse = new LearnerResponse(
                UUID.randomUUID(), "answer-to-go", "To go, please.", SUBMITTED_AT);
        LearnerResponse unknownStepResponse = new LearnerResponse(
                SESSION_ID, "unknown-step", "To go, please.", SUBMITTED_AT);
        List<LearnerResponse> incompleteResponses = List.of(
                new LearnerResponse(SESSION_ID, "order-drink", "Could I have a medium coffee, please?", SUBMITTED_AT),
                new LearnerResponse(SESSION_ID, "ask-price", "How much is it?", SUBMITTED_AT));
        List<LearnerResponse> duplicatedStepResponses = List.of(
                new LearnerResponse(SESSION_ID, "order-drink", "Could I have a medium coffee, please?", SUBMITTED_AT),
                new LearnerResponse(SESSION_ID, "order-drink", "How much is it?", SUBMITTED_AT),
                new LearnerResponse(SESSION_ID, "answer-to-go", "To go, please.", SUBMITTED_AT));

        List<List<LearnerResponse>> invalidResponseSets = List.of(
                List.of(
                        new LearnerResponse(SESSION_ID, "order-drink", "Could I have a medium coffee, please?", SUBMITTED_AT),
                        new LearnerResponse(SESSION_ID, "ask-price", "How much is it?", SUBMITTED_AT),
                        foreignSessionResponse),
                List.of(
                        new LearnerResponse(SESSION_ID, "order-drink", "Could I have a medium coffee, please?", SUBMITTED_AT),
                        new LearnerResponse(SESSION_ID, "ask-price", "How much is it?", SUBMITTED_AT),
                        unknownStepResponse),
                incompleteResponses,
                duplicatedStepResponses);

        for (List<LearnerResponse> responses : invalidResponseSets) {
            assertThat(validator.validate(validClaimJson(),
                    input(completedTask(), completedSession(), assessment(), responses, cafeMaterial())))
                    .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.INVALID_INPUT));
        }
    }

    // --- rubric boundary ---

    @Test
    void unknownRubricReferenceOrLanguageMismatchFailsClosedAsRubricUnavailable() {
        PublishedLearningMaterial unknownRubricMaterial = material("unknown-rubric/v9", "en");
        // reference 存在但 material/task 的 target language 是 ja：en rubric 不落位。
        PublishedLearningMaterial japaneseMaterial = material(RUBRIC_REFERENCE, "ja");
        LearningTask japaneseTask = task("ja");

        List<GroundedEvaluationInput> inputs = List.of(
                input(completedTask(), completedSession(), assessment(), standardResponses(), unknownRubricMaterial),
                input(japaneseTask, completedSession(), assessment(), japaneseResponses(), japaneseMaterial));

        for (GroundedEvaluationInput input : inputs) {
            assertThat(validator.validate(validClaimJson(), input))
                    .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.RUBRIC_UNAVAILABLE));
        }
    }

    @Test
    void issueOutsideTheRubricAllowedSetIsRejectedAsUnsupported() {
        SemanticGroundingValidator grammarOnlyValidator = new SemanticGroundingValidator(
                new StructuredOutputValidator(JsonMapper.builder().build()),
                rubricSupporting(IssueType.GRAMMAR));

        assertThat(grammarOnlyValidator.validate(
                modelOutput(claim("answer-to-go", "Thank you", -1, "NATURALNESS", "ok", 0.5)),
                validInput()))
                .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.UNSUPPORTED_ISSUE));
        assertThat(grammarOnlyValidator.validate(
                modelOutput(claim("answer-to-go", "Thank you", -1, "GRAMMAR", "ok", 0.5)),
                validInput()))
                .isInstanceOf(Validated.class);
    }

    // --- grounding boundary ---

    @Test
    void fakeTurnIsRejectedAsUnknownTurn() {
        assertThat(validator.validate(
                modelOutput(claim("introduce-yourself", "Thank you", -1, "NATURALNESS", "ok", 0.5)),
                validInput()))
                .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.UNKNOWN_TURN));
    }

    @Test
    void quoteSearchIsLiteralCaseSensitiveAndNormalizationFree() {
        // 大小写差异不允许匹配。
        assertThat(validator.validate(
                modelOutput(claim("order-drink", "Medium coffee", -1, "GRAMMAR", "ok", 0.5)),
                validInput()))
                .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.QUOTE_MISMATCH));
        // NFC/NFD 差异不允许匹配：learner text 是 NFD（e + 组合重音），NFD quote 逐字符相等才命中，
        // NFC 预组合 quote 不做 normalization 直接拒绝。
        GroundedEvaluationInput nfdInput = inputWithAnswerToGoText("To go, pleas\u0065\u0301.");
        assertThat(validator.validate(
                modelOutput(claim("answer-to-go", "pleas\u0065\u0301", -1, "GRAMMAR", "ok", 0.5)), nfdInput))
                .isInstanceOf(Validated.class);
        assertThat(validator.validate(
                modelOutput(claim("answer-to-go", "pleas\u00E9", -1, "GRAMMAR", "ok", 0.5)), nfdInput))
                .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.QUOTE_MISMATCH));
        // 只搜索 learner response：prompt / acceptedAnswers 中的文本不构成匹配。
        assertThat(validator.validate(
                modelOutput(claim("order-drink", "Order a medium coffee politely.", -1, "TASK_RESPONSE", "ok", 0.5)),
                validInput()))
                .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.QUOTE_MISMATCH));
        assertThat(validator.validate(
                modelOutput(claim("ask-price", "Could I have a medium coffee, please?", -1, "GRAMMAR", "ok", 0.5)),
                validInput()))
                .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.QUOTE_MISMATCH));
    }

    @Test
    void surrogatePairQuotesGetSafeOffsetsAndLoneSurrogatesAreRejected() {
        GroundedEvaluationInput emojiInput = inputWithAnswerToGoText("To go, please. \uD83D\uDE00");

        assertThat(validator.validate(
                modelOutput(claim("answer-to-go", "\uD83D\uDE00", -1, "NATURALNESS", "ok", 0.5)), emojiInput))
                .isInstanceOfSatisfying(Validated.class, validated -> {
                    GroundedClaim claim = validated.candidate().claims().getFirst();
                    assertThat(claim.startOffset()).isEqualTo("To go, please. ".length());
                    assertThat(claim.endOffset()).isEqualTo(claim.startOffset() + 2);
                });
        // lone low surrogate 只能匹配进 emoji 内部：span 会切开 surrogate pair，fail closed。
        // JSON 转义手工构造，避免 Jackson 对无效 surrogate 的序列化行为影响 fixture。
        String loneSurrogateClaim = "{\"sourceTurnId\":\"answer-to-go\",\"exactQuote\":\"\\uDE00\","
                + "\"occurrenceIndex\":-1,\"issueType\":\"NATURALNESS\",\"explanation\":\"ok\",\"confidence\":0.5}";
        assertThat(validator.validate(modelOutput(loneSurrogateClaim), emojiInput))
                .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.QUOTE_MISMATCH));
    }

    @Test
    void japaneseLearnerTextUsesTheSameLiteralAlgorithm() {
        String learnerText = "はい、ありがとうございます。ありがとう。";
        GroundedEvaluationInput input = inputWithAnswerToGoText(learnerText);

        assertThat(validator.validate(
                modelOutput(claim("answer-to-go", "ありがとう", -1, "GRAMMAR", "ok", 0.5)), input))
                .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.AMBIGUOUS_OCCURRENCE));
        assertThat(validator.validate(
                modelOutput(claim("answer-to-go", "ありがとう", 1, "GRAMMAR", "ok", 0.5)), input))
                .isInstanceOfSatisfying(Validated.class, validated -> {
                    GroundedClaim claim = validated.candidate().claims().getFirst();
                    assertThat(claim.startOffset()).isEqualTo(learnerText.lastIndexOf("ありがとう"));
                    assertThat(learnerText.substring(claim.startOffset(), claim.endOffset()))
                            .isEqualTo("ありがとう");
                });
    }

    @Test
    void anyFailingClaimRejectsTheWholeBatchWithoutACandidate() {
        String generatedJson = modelOutput(
                claim("order-drink", "medium coffee", -1, "GRAMMAR", "ok", 0.5),
                claim("answer-to-go", "no such text in the response", -1, "GRAMMAR", "ok", 0.5));

        assertThat(validator.validate(generatedJson, validInput()))
                .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.QUOTE_MISMATCH));
    }

    @Test
    void candidateAndInputCollectionsAreImmutable() {
        List<LearnerResponse> mutableResponses = new ArrayList<>(standardResponses());
        GroundedEvaluationInput input = new GroundedEvaluationInput(
                USER_ID, PROFILE_ID, completedTask(), completedSession(), assessment(),
                mutableResponses, cafeMaterial());
        mutableResponses.clear();

        assertThat(input.responses()).hasSize(3);
        assertThatThrownBy(() -> input.responses().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(validator.validate(validClaimJson(), input)).isInstanceOf(Validated.class);

        SemanticGroundingResult result = validator.validate(validClaimJson(), validInput());
        assertThat(result).isInstanceOf(Validated.class);
        ValidatedSemanticCandidate candidate = ((Validated) result).candidate();
        assertThatThrownBy(() -> candidate.claims().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void outputContractIsClosedAgainstAuthorityLeakage() {
        // Model output contract 的字段集封闭：不携带 offsets、owner、profile 或长期状态字段。
        List<String> componentNames = componentNames(
                com.dailylanguage.evaluator.domain.SemanticEvaluationOutput.Claim.class);
        assertThat(componentNames).containsExactly(
                "sourceTurnId", "exactQuote", "occurrenceIndex", "issueType", "explanation", "confidence");
        assertThat(componentNames(
                com.dailylanguage.evaluator.domain.SemanticEvaluationOutput.class))
                .containsExactly("claims");
    }

    // --- fixtures ---

    private static String validClaimJson() {
        return modelOutput(claim("answer-to-go", "Thank you", -1, "NATURALNESS", "ok", 0.5));
    }

    private static GroundedEvaluationInput validInput() {
        return input(completedTask(), completedSession(), assessment(), standardResponses(), cafeMaterial());
    }

    private static GroundedEvaluationInput inputWithAnswerToGoText(String answerToGoText) {
        return input(completedTask(), completedSession(), assessment(),
                responses("Could I have a medium coffee, please?", "How much is it?", answerToGoText),
                cafeMaterial());
    }

    private static GroundedEvaluationInput input(
            LearningTask task,
            PracticeSession session,
            DeterministicAssessment assessment,
            List<LearnerResponse> responses,
            PublishedLearningMaterial material) {
        return new GroundedEvaluationInput(
                USER_ID, PROFILE_ID, task, session, assessment, responses, material);
    }

    private static SemanticGroundingValidator.RubricSource rubricSupporting(IssueType... issueTypes) {
        List<IssueDefinition> definitions = Arrays.stream(issueTypes)
                .map(issueType -> new IssueDefinition(issueType, "scope", "requirement"))
                .toList();
        SemanticEvaluationRubric rubric = new SemanticEvaluationRubric(RUBRIC_REFERENCE, "en", definitions);
        return (rubricReference, targetLanguage) -> RUBRIC_REFERENCE.equals(rubricReference)
                && "en".equals(targetLanguage) ? Optional.of(rubric) : Optional.empty();
    }

    private static String modelOutput(String... claims) {
        return "{\"claims\":[" + String.join(",", claims) + "]}";
    }

    private static String claim(
            String sourceTurnId, String exactQuote, int occurrenceIndex,
            String issueType, String explanation, String confidence) {
        return "{\"sourceTurnId\":" + jsonString(sourceTurnId)
                + ",\"exactQuote\":" + jsonString(exactQuote)
                + ",\"occurrenceIndex\":" + occurrenceIndex
                + ",\"issueType\":\"" + issueType + "\""
                + ",\"explanation\":" + jsonString(explanation)
                + ",\"confidence\":" + confidence + "}";
    }

    private static String claim(
            String sourceTurnId, String exactQuote, int occurrenceIndex,
            String issueType, String explanation, double confidence) {
        return claim(sourceTurnId, exactQuote, occurrenceIndex, issueType, explanation,
                String.valueOf(confidence));
    }

    /** 直接拼接 raw token（不经 JSON escaping），用于构造非法 scalar 类型的 adversarial output。 */
    private static String rawClaim(
            String sourceTurnId, String exactQuote, String occurrenceIndex,
            String issueType, String explanation, String confidence) {
        return "{\"sourceTurnId\":" + sourceTurnId
                + ",\"exactQuote\":" + exactQuote
                + ",\"occurrenceIndex\":" + occurrenceIndex
                + ",\"issueType\":" + issueType
                + ",\"explanation\":" + explanation
                + ",\"confidence\":" + confidence + "}";
    }

    private static String jsonString(String value) {
        return JSON_MAPPER.writeValueAsString(value);
    }

    private static LearningTask completedTask() {
        return task("en");
    }

    private static LearningTask task(String targetLanguage) {
        return task(LearningTask.Status.COMPLETED, targetLanguage);
    }

    private static LearningTask task(LearningTask.Status status) {
        return task(status, "en");
    }

    private static LearningTask task(LearningTask.Status status, String targetLanguage) {
        return new LearningTask(
                TASK_ID,
                USER_ID,
                PROFILE_ID,
                CAFE_IDENTITY,
                targetLanguage,
                "zh-cn",
                MaterialDifficulty.FOUNDATION,
                10,
                "CAFE_SIMPLE_REQUEST",
                "Make a polite request, ask about price, and answer a follow-up question in a coffee shop.",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK,
                status,
                STARTED_AT.minusMinutes(5),
                Optional.of(STARTED_AT),
                status == LearningTask.Status.COMPLETED ? Optional.of(COMPLETED_AT) : Optional.empty());
    }

    private static PracticeSession completedSession() {
        return new PracticeSession(
                SESSION_ID, TASK_ID, PracticeSession.Status.COMPLETED, STARTED_AT,
                Optional.of(COMPLETED_AT), Optional.empty());
    }

    private static DeterministicAssessment assessment() {
        return new DeterministicAssessment(
                SESSION_ID,
                DeterministicTextAssessmentPolicy.ASSESSMENT_POLICY_VERSION,
                390L,
                COMPLETED_AT,
                List.of(
                        new StepResult("order-drink", StepKind.EXACT, StepOutcome.MATCHED),
                        new StepResult("ask-price", StepKind.EXACT, StepOutcome.MATCHED),
                        new StepResult("answer-to-go", StepKind.SEMANTIC_ONLY, StepOutcome.NOT_APPLICABLE)));
    }

    private static List<LearnerResponse> standardResponses() {
        return responses(
                "Could I have a medium coffee, please?",
                "How much is it?",
                "To go, please. Thank you so much!");
    }

    private static List<LearnerResponse> japaneseResponses() {
        return responses(
                "コーヒーをください。",
                "いくらですか。",
                "持ち帰りでお願いします。ありがとうございます。");
    }

    private static List<LearnerResponse> responses(String orderDrink, String askPrice, String answerToGo) {
        return List.of(
                new LearnerResponse(SESSION_ID, "order-drink", orderDrink, SUBMITTED_AT),
                new LearnerResponse(SESSION_ID, "ask-price", askPrice, SUBMITTED_AT),
                new LearnerResponse(SESSION_ID, "answer-to-go", answerToGo, SUBMITTED_AT));
    }

    private static PublishedLearningMaterial cafeMaterial() {
        return material(RUBRIC_REFERENCE, "en");
    }

    private static PublishedLearningMaterial material(String rubricReference, String targetLanguage) {
        return materialWithIdentity(rubricReference, targetLanguage, CAFE_IDENTITY);
    }

    private static PublishedLearningMaterial materialWithIdentity(MaterialIdentity identity) {
        return materialWithIdentity(RUBRIC_REFERENCE, "en", identity);
    }

    private static PublishedLearningMaterial materialWithIdentity(
            String rubricReference, String targetLanguage, MaterialIdentity identity) {
        return new PublishedLearningMaterial(
                identity,
                new TargetPracticeCore(
                        targetLanguage,
                        MaterialDifficulty.FOUNDATION,
                        "CAFE_SIMPLE_REQUEST",
                        "Make a polite request, ask about price, and answer a follow-up question in a coffee shop.",
                        "You are at a coffee shop.",
                        null,
                        List.of(
                                new TextPracticeStep("order-drink", TextStepKind.EXACT,
                                        "Order a medium coffee politely.",
                                        List.of("Could I have a medium coffee, please?")),
                                new TextPracticeStep("ask-price", TextStepKind.EXACT,
                                        "Ask how much the coffee costs.",
                                        List.of("How much is it?")),
                                new TextPracticeStep("answer-to-go", TextStepKind.SEMANTIC_ONLY,
                                        "Answer that you want it to go.", List.of())),
                        rubricReference),
                List.of(new SupportScaffold("zh-cn", "instruction", "explanation", "hint", null)),
                new MaterialSourceLineage("PROJECT_ORIGINAL", "1", "AGPL-3.0",
                        "sha256:" + "0".repeat(64)));
    }

    private static List<String> componentNames(Class<? extends Record> recordType) {
        return Stream.of(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
