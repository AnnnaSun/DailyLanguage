package com.dailylanguage.evaluator.domain;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 已持久化 EvaluationRun 的 durable 快照。PostgreSQL 是 id、status 与 lifecycle timestamp 的
 * authority；本类型只还原数据库已裁决的行，不提供任何 transition 操作。ownership 不在此重复
 * 存储，经 session → learning_task 链路还原。生命周期：PENDING（尚无 durable semantic
 * outcome）→ SUCCEEDED（存在唯一 validated candidate）或 FAILED。FAILED 通过 FailureReason
 * 区分 grounding rejection、Model call failure 与不可再消费的 Model result。
 */
public record EvaluationRun(
        UUID id,
        UUID sessionId,
        UUID modelCallJobId,
        Status status,
        long workflowVersion,
        long rowVersion,
        OffsetDateTime createdAt,
        Optional<OffsetDateTime> completedAt,
        Optional<FailureReason> failureReason,
        Optional<SemanticGroundingResult.RejectionReason> groundingRejectionReason) {

    /** S8 Evaluation workflow 第一版；绑定 Job 必须持有同一 version。 */
    public static final long CURRENT_WORKFLOW_VERSION = 0L;

    /** 创建该 Run 的 workflow step identity；Repository 的 insert gate 与绑定 Job 必须一致持有。 */
    public static final String WORKFLOW_STEP_ID = "SEMANTIC_EVALUATION";

    public EvaluationRun {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(modelCallJobId, "modelCallJobId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (workflowVersion < 0) {
            throw new IllegalArgumentException("workflowVersion must not be negative");
        }
        if (rowVersion < 0) {
            throw new IllegalArgumentException("rowVersion must not be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        Objects.requireNonNull(failureReason, "failureReason must not be null");
        Objects.requireNonNull(groundingRejectionReason, "groundingRejectionReason must not be null");
        if (completedAt.filter(value -> value.isBefore(createdAt)).isPresent()) {
            throw new IllegalArgumentException("completedAt must not be before createdAt");
        }
        // 与 V13 的 ck_evaluation_run_outcome 一致：terminal pairing 由数据库封闭枚举。
        switch (status) {
            case PENDING -> requireNoTerminalOutcome(completedAt, failureReason, groundingRejectionReason);
            case SUCCEEDED -> requireSuccessfulOutcome(completedAt, failureReason, groundingRejectionReason);
            case FAILED -> {
                requireFailedOutcome(completedAt, failureReason, groundingRejectionReason);
            }
        }
    }

    private static void requireNoTerminalOutcome(
            Optional<OffsetDateTime> completedAt,
            Optional<FailureReason> failureReason,
            Optional<SemanticGroundingResult.RejectionReason> groundingRejectionReason) {
        if (completedAt.isPresent() || failureReason.isPresent() || groundingRejectionReason.isPresent()) {
            throw new IllegalArgumentException("PENDING evaluation run cannot have terminal facts");
        }
    }

    private static void requireSuccessfulOutcome(
            Optional<OffsetDateTime> completedAt,
            Optional<FailureReason> failureReason,
            Optional<SemanticGroundingResult.RejectionReason> groundingRejectionReason) {
        if (completedAt.isEmpty() || failureReason.isPresent() || groundingRejectionReason.isPresent()) {
            throw new IllegalArgumentException("SUCCEEDED evaluation run requires only completedAt");
        }
    }

    private static void requireFailedOutcome(
            Optional<OffsetDateTime> completedAt,
            Optional<FailureReason> failureReason,
            Optional<SemanticGroundingResult.RejectionReason> groundingRejectionReason) {
        if (completedAt.isEmpty() || failureReason.isEmpty()) {
            throw new IllegalArgumentException("FAILED evaluation run requires completedAt and failureReason");
        }
        FailureReason durableFailureReason = failureReason.orElseThrow();
        boolean groundingPair = durableFailureReason == FailureReason.GROUNDING_REJECTED
                && groundingRejectionReason.isPresent();
        boolean modelPair = durableFailureReason != FailureReason.GROUNDING_REJECTED
                && groundingRejectionReason.isEmpty();
        if (!groundingPair && !modelPair) {
            throw new IllegalArgumentException(
                    "grounding rejection reason must match evaluation failure reason");
        }
    }

    public enum Status {
        PENDING, SUCCEEDED, FAILED
    }

    /** Workflow-level failure category；ModelCallJob 继续保存具体 execution/result 状态。 */
    public enum FailureReason {
        GROUNDING_REJECTED,
        MODEL_CALL_FAILED,
        MODEL_RESULT_UNAVAILABLE
    }
}
