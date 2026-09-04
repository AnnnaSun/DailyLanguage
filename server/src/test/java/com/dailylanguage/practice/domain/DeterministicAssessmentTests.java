package com.dailylanguage.practice.domain;

import java.lang.reflect.RecordComponent;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.dailylanguage.practice.domain.DeterministicAssessment.StepKind;
import com.dailylanguage.practice.domain.DeterministicAssessment.StepOutcome;
import com.dailylanguage.practice.domain.DeterministicAssessment.StepResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeterministicAssessmentTests {

    private static final UUID SESSION_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000061");
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-04T10:25:00.789Z");

    @Test
    void keepsDurableValuesAndMirrorsThePolicyVersionVocabulary() {
        List<StepResult> stepResults = List.of(
                new StepResult("order-drink", StepKind.EXACT, StepOutcome.MATCHED),
                new StepResult("answer-to-go", StepKind.SEMANTIC_ONLY, StepOutcome.NOT_APPLICABLE));

        DeterministicAssessment assessment = new DeterministicAssessment(
                SESSION_ID, DeterministicTextAssessmentPolicy.ASSESSMENT_POLICY_VERSION, 125, CREATED_AT,
                stepResults);

        assertThat(assessment.sessionId()).isEqualTo(SESSION_ID);
        assertThat(assessment.assessmentPolicyVersion()).isEqualTo("M1_TEXT_EXACT_V1");
        assertThat(assessment.durationSeconds()).isEqualTo(125);
        assertThat(assessment.createdAt()).isEqualTo(CREATED_AT);
        assertThat(assessment.stepResults()).containsExactlyElementsOf(stepResults);
    }

    @Test
    void policyVersionIsClosedToM1TextExactV1() {
        List<StepResult> stepResults =
                List.of(new StepResult("order-drink", StepKind.EXACT, StepOutcome.MATCHED));

        assertThatThrownBy(() -> new DeterministicAssessment(
                SESSION_ID, "M2_SEMANTIC_V1", 0, CREATED_AT, stepResults))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("M1_TEXT_EXACT_V1");
        assertThatThrownBy(() -> new DeterministicAssessment(
                SESSION_ID, null, 0, CREATED_AT, stepResults))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeDurationMissingTimestampsAndEmptySteps() {
        StepResult stepResult = new StepResult("order-drink", StepKind.EXACT, StepOutcome.MATCHED);
        String policyVersion = DeterministicTextAssessmentPolicy.ASSESSMENT_POLICY_VERSION;

        assertThatThrownBy(() -> new DeterministicAssessment(
                SESSION_ID, policyVersion, -1, CREATED_AT, List.of(stepResult)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durationSeconds");
        assertThatThrownBy(() -> new DeterministicAssessment(
                SESSION_ID, policyVersion, 0, null, List.of(stepResult)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("createdAt");
        assertThatThrownBy(() -> new DeterministicAssessment(
                SESSION_ID, policyVersion, 0, CREATED_AT, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one step result");
        assertThatThrownBy(() -> new DeterministicAssessment(
                SESSION_ID, policyVersion, 0, CREATED_AT, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("stepResults");
    }

    @Test
    void rejectsDuplicateStepIdsAndDefensivelyCopiesTheStepList() {
        StepResult first = new StepResult("order-drink", StepKind.EXACT, StepOutcome.MATCHED);
        StepResult duplicate = new StepResult("order-drink", StepKind.EXACT, StepOutcome.NOT_MATCHED);
        String policyVersion = DeterministicTextAssessmentPolicy.ASSESSMENT_POLICY_VERSION;

        assertThatThrownBy(() -> new DeterministicAssessment(
                SESSION_ID, policyVersion, 0, CREATED_AT, List.of(first, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate stepId order-drink");

        List<StepResult> mutable = new ArrayList<>(List.of(first));
        DeterministicAssessment assessment =
                new DeterministicAssessment(SESSION_ID, policyVersion, 0, CREATED_AT, mutable);
        mutable.add(duplicate);
        assertThat(assessment.stepResults()).hasSize(1);
    }

    @Test
    void stepResultOutcomeIsConstrainedByStepKind() {
        assertThat(new StepResult("order-drink", StepKind.EXACT, StepOutcome.MATCHED).outcome())
                .isEqualTo(StepOutcome.MATCHED);
        assertThat(new StepResult("order-drink", StepKind.EXACT, StepOutcome.NOT_MATCHED).outcome())
                .isEqualTo(StepOutcome.NOT_MATCHED);
        assertThat(new StepResult("answer-to-go", StepKind.SEMANTIC_ONLY, StepOutcome.NOT_APPLICABLE)
                        .outcome())
                .isEqualTo(StepOutcome.NOT_APPLICABLE);

        // EXACT 不得声明 NOT_APPLICABLE；SEMANTIC_ONLY 不得伪造 MATCHED / NOT_MATCHED。
        assertThatThrownBy(() -> new StepResult("order-drink", StepKind.EXACT, StepOutcome.NOT_APPLICABLE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOT_APPLICABLE is not allowed for step kind EXACT");
        assertThatThrownBy(() -> new StepResult("answer-to-go", StepKind.SEMANTIC_ONLY, StepOutcome.MATCHED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StepResult("answer-to-go", StepKind.SEMANTIC_ONLY, StepOutcome.NOT_MATCHED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stepIdFollowsTheDurableIdentifierContract() {
        StepResult valid = new StepResult("order-drink", StepKind.EXACT, StepOutcome.MATCHED);
        assertThat(valid.stepId()).isEqualTo("order-drink");

        assertThatThrownBy(() -> new StepResult(null, StepKind.EXACT, StepOutcome.MATCHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stepId");
        assertThatThrownBy(() -> new StepResult(" ", StepKind.EXACT, StepOutcome.MATCHED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StepResult(" order-drink", StepKind.EXACT, StepOutcome.MATCHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("surrounding whitespace");
        assertThatThrownBy(() -> new StepResult(
                "a".repeat(PracticeSession.LearnerResponse.MAXIMUM_STEP_ID_LENGTH + 1),
                StepKind.EXACT, StepOutcome.MATCHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceed " + PracticeSession.LearnerResponse.MAXIMUM_STEP_ID_LENGTH);
    }

    @Test
    void assessmentShapeCarriesNoLearnerTextOrAcceptedAnswers() {
        // durable contract 自身不携带 private learner 数据；completion API 亦不派生它们。
        List<String> components = Stream.of(DeterministicAssessment.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(components).containsExactly(
                "sessionId", "assessmentPolicyVersion", "durationSeconds", "createdAt", "stepResults");

        List<String> stepComponents = Stream.of(StepResult.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(stepComponents).containsExactly("stepId", "stepKind", "outcome");
    }
}
