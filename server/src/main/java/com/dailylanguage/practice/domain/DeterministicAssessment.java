package com.dailylanguage.practice.domain;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Java 根据 trusted learner responses 计算、并由 PostgreSQL 在 completion transaction 内持久化的
 * deterministic assessment 快照。数据库是 sessionId、duration 与 createdAt 的 authority；本类型只还原
 * 已裁决的行。stepKind 与 outcome 的组合约束与 V10 的数据库 CHECK 一致：EXACT step 只声明 exact 比较
 * 结果，SEMANTIC_ONLY step 只记录 NOT_APPLICABLE——不伪造 semantic correctness、naturalness、
 * task success 或任何长期学习状态；这些属于后续 Evaluator / Evidence scope。
 */
public record DeterministicAssessment(
        UUID sessionId,
        String assessmentPolicyVersion,
        long durationSeconds,
        OffsetDateTime createdAt,
        List<StepResult> stepResults) {

    public DeterministicAssessment {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (!DeterministicTextAssessmentPolicy.ASSESSMENT_POLICY_VERSION.equals(assessmentPolicyVersion)) {
            throw new IllegalArgumentException(
                    "assessmentPolicyVersion must be "
                            + DeterministicTextAssessmentPolicy.ASSESSMENT_POLICY_VERSION);
        }
        if (durationSeconds < 0) {
            throw new IllegalArgumentException("durationSeconds must not be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(stepResults, "stepResults must not be null");
        if (stepResults.isEmpty()) {
            throw new IllegalArgumentException("stepResults must contain at least one step result");
        }
        stepResults = List.copyOf(stepResults);
        requireDistinctStepIds(stepResults);
    }

    private static void requireDistinctStepIds(List<StepResult> stepResults) {
        Set<String> stepIds = new HashSet<>();
        for (StepResult stepResult : stepResults) {
            if (!stepIds.add(stepResult.stepId())) {
                throw new IllegalArgumentException(
                        "stepResults must not contain duplicate stepId " + stepResult.stepId());
            }
        }
    }

    /** 持久化时的 material step kind；与 content 的 TextStepKind 词汇一致，但由 practice 契约独立封闭。 */
    public enum StepKind {
        EXACT, SEMANTIC_ONLY
    }

    public enum StepOutcome {
        MATCHED, NOT_MATCHED, NOT_APPLICABLE
    }

    /**
     * 一个 material step 的 deterministic 结果。stepId 约束与 practice_response 一致；
     * outcome 的合法取值由 stepKind 决定，映射 V10 的 outcome CHECK。
     */
    public record StepResult(String stepId, StepKind stepKind, StepOutcome outcome) {

        public StepResult {
            if (stepId == null || stepId.isBlank() || !stepId.equals(stepId.strip())
                    || stepId.length() > PracticeSession.LearnerResponse.MAXIMUM_STEP_ID_LENGTH) {
                throw new IllegalArgumentException(
                        "stepId must not be blank, contain surrounding whitespace, or exceed "
                                + PracticeSession.LearnerResponse.MAXIMUM_STEP_ID_LENGTH + " characters");
            }
            Objects.requireNonNull(stepKind, "stepKind must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
            boolean outcomeAllowedForKind = switch (stepKind) {
                case EXACT -> outcome == StepOutcome.MATCHED || outcome == StepOutcome.NOT_MATCHED;
                case SEMANTIC_ONLY -> outcome == StepOutcome.NOT_APPLICABLE;
            };
            if (!outcomeAllowedForKind) {
                throw new IllegalArgumentException(
                        "outcome " + outcome + " is not allowed for step kind " + stepKind);
            }
        }
    }
}
