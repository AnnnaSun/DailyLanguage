package com.dailylanguage.evaluator.infrastructure;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.evaluator.domain.EvaluationRun;
import com.dailylanguage.evaluator.domain.SemanticEvaluationOutput.IssueType;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.GroundedClaim;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.RejectionReason;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.ValidatedSemanticCandidate;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;

@Repository
public class EvaluationRunRepository {

    private final EvaluationRunMapper evaluationRunMapper;

    public EvaluationRunRepository(EvaluationRunMapper evaluationRunMapper) {
        this.evaluationRunMapper =
                Objects.requireNonNull(evaluationRunMapper, "evaluationRunMapper must not be null");
    }

    /** Run id 必须先于 Job 创建取得：model_call_job.workflow_id 精确等于 EvaluationRun.id。 */
    public UUID nextRunId() {
        UUID runId = evaluationRunMapper.nextRunId();
        if (runId == null) {
            throw new IllegalStateException("database did not provide a uuidv7 evaluation run id");
        }
        return runId;
    }

    /** owner-scoped 读取：ownership 经 evaluation_run → session → learning_task 链路重校验。 */
    public Optional<EvaluationRun> findOwnedBySessionId(
            UUID sessionId, UUID trustedUserId, UUID languageProfileId) {
        validateOwnedArguments(sessionId, trustedUserId, languageProfileId);
        return evaluationRunMapper
                .findOwnedBySessionId(sessionId, trustedUserId, languageProfileId)
                .map(EvaluationRunRepository::toDomain);
    }

    /** 消费串行化点：锁定 owner-scoped Run 行后，terminal 判定与 outcome 写入才有稳定前提。 */
    public Optional<EvaluationRun> findOwnedBySessionIdForUpdate(
            UUID sessionId, UUID trustedUserId, UUID languageProfileId) {
        validateOwnedArguments(sessionId, trustedUserId, languageProfileId);
        return Optional.ofNullable(evaluationRunMapper
                .findOwnedBySessionIdForUpdate(sessionId, trustedUserId, languageProfileId))
                .map(EvaluationRunRepository::toDomain);
    }

    /**
     * 前置条件：调用方已在同一事务内锁定该 Session 并确认 COMPLETED。INSERT … SELECT gate 在
     * 数据库内原子重校验 owner/profile/status，并核对关联 Job 的完整创建期 identity
     * （owner/profile、EVALUATION / TEXT_GENERATION、workflowId=runId、stepId、version）；
     * 任一不匹配产生零行并以异常 fail closed，由外层事务回滚同事务内已创建的 Job，不留下 orphan。
     */
    public EvaluationRun insertOwned(
            UUID evaluationRunId,
            UUID sessionId,
            UUID modelCallJobId,
            UUID trustedUserId,
            UUID languageProfileId,
            long workflowVersion) {
        Objects.requireNonNull(evaluationRunId, "evaluationRunId must not be null");
        validateOwnedArguments(sessionId, trustedUserId, languageProfileId);
        Objects.requireNonNull(modelCallJobId, "modelCallJobId must not be null");
        if (workflowVersion < 0) {
            throw new IllegalArgumentException("workflowVersion must not be negative");
        }
        StoredEvaluationRun inserted = evaluationRunMapper.insertOwnedAndReturn(
                new NewEvaluationRunRow(
                        evaluationRunId,
                        sessionId,
                        modelCallJobId,
                        trustedUserId,
                        languageProfileId,
                        ModelPurpose.EVALUATION.name(),
                        ModelOperation.TEXT_GENERATION.name(),
                        EvaluationRun.WORKFLOW_STEP_ID,
                        workflowVersion));
        if (inserted == null) {
            throw new IllegalStateException(
                    "evaluation run insert requires an owned completed practice session"
                            + " and an evaluation workflow job with matching creation identity");
        }
        return toDomain(inserted);
    }

    /**
     * 前置条件：调用方已在同一事务内锁定该 Run 且 Run 为 PENDING。PENDING → terminal 的 CAS
     * 由数据库以 status + rowVersion 原子裁决；零行（版本竞争 / gate 不匹配）以异常 fail closed，
     * 与外层事务一起回滚 Job consumption，保证 CONSUMED 与 outcome 原子提交。
     */
    public EvaluationRun tryFinalizeOwned(
            UUID evaluationRunId,
            UUID trustedUserId,
            UUID languageProfileId,
            EvaluationRun.Status status,
            Optional<RejectionReason> groundingRejectionReason,
            long expectedRowVersion) {
        Objects.requireNonNull(evaluationRunId, "evaluationRunId must not be null");
        validateOwnedArguments(evaluationRunId, trustedUserId, languageProfileId);
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(groundingRejectionReason, "groundingRejectionReason must not be null");
        if (status == EvaluationRun.Status.PENDING) {
            throw new IllegalArgumentException("evaluation run can only finalize to a terminal status");
        }
        if ((status == EvaluationRun.Status.SUCCEEDED) != groundingRejectionReason.isEmpty()) {
            throw new IllegalArgumentException(
                    "grounding rejection reason must match the evaluation terminal status");
        }
        if (expectedRowVersion < 0) {
            throw new IllegalArgumentException("expectedRowVersion must not be negative");
        }
        StoredEvaluationRun finalized = evaluationRunMapper.tryFinalizeOwnedAndReturn(
                new FinalizeEvaluationRunRow(
                        evaluationRunId,
                        trustedUserId,
                        languageProfileId,
                        status.name(),
                        groundingRejectionReason.map(RejectionReason::name).orElse(null),
                        expectedRowVersion));
        if (finalized == null) {
            throw new IllegalStateException(
                    "evaluation run finalize transition was not recorded");
        }
        return toDomain(finalized);
    }

    /**
     * owner-scoped 保存 candidate header；数据库同时核对 Run 仍 PENDING、exact Session/material/language
     * provenance，以及绑定 Job 已按完整 Evaluation identity 进入 CONSUMED。零行以异常 fail closed。
     */
    public void insertValidatedCandidate(
            UUID evaluationRunId,
            UUID trustedUserId,
            UUID languageProfileId,
            ValidatedSemanticCandidate candidate) {
        Objects.requireNonNull(evaluationRunId, "evaluationRunId must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        validateOwnedArguments(evaluationRunId, trustedUserId, languageProfileId);
        if (!candidate.languageProfileId().equals(languageProfileId)) {
            throw new IllegalArgumentException(
                    "candidate languageProfileId must match the owned evaluation profile");
        }
        StoredValidatedCandidate inserted = evaluationRunMapper.insertValidatedCandidateAndReturn(
                new NewValidatedCandidateRow(
                        evaluationRunId,
                        trustedUserId,
                        languageProfileId,
                        candidate.sessionId(),
                        candidate.materialIdentity().materialId(),
                        candidate.materialIdentity().publishedVersion(),
                        candidate.rubricReference(),
                        candidate.targetLanguage(),
                        candidate.groundingPolicyVersion()));
        if (inserted == null) {
            throw new IllegalStateException(
                    "validated semantic candidate insert requires an owned evaluation run");
        }
    }

    /** owner-scoped 保存单条 claim；零行（candidate 不属于该 owner/profile）以异常 fail closed。 */
    public void insertValidatedClaim(
            UUID evaluationRunId,
            UUID trustedUserId,
            UUID languageProfileId,
            int claimIndex,
            GroundedClaim claim) {
        Objects.requireNonNull(claim, "claim must not be null");
        validateOwnedArguments(evaluationRunId, trustedUserId, languageProfileId);
        if (claimIndex < 0) {
            throw new IllegalArgumentException("claimIndex must not be negative");
        }
        int inserted = evaluationRunMapper.insertValidatedClaim(new NewValidatedClaimRow(
                evaluationRunId,
                trustedUserId,
                languageProfileId,
                claimIndex,
                claim.sourceTurnId(),
                claim.exactQuote(),
                claim.occurrenceIndex(),
                claim.startOffset(),
                claim.endOffset(),
                claim.issueType().name(),
                claim.explanation(),
                claim.confidence()));
        if (inserted != 1) {
            throw new IllegalStateException(
                    "validated semantic claim insert requires an owned validated candidate");
        }
    }

    /** terminal replay 读取：header 与 claims 还原为完整 domain candidate，不重新 grounding。 */
    public Optional<ValidatedSemanticCandidate> findOwnedCandidateByRunId(
            UUID evaluationRunId, UUID trustedUserId, UUID languageProfileId) {
        Objects.requireNonNull(evaluationRunId, "evaluationRunId must not be null");
        return evaluationRunMapper
                .findOwnedCandidateByRunId(evaluationRunId, trustedUserId, languageProfileId)
                .map(header -> {
                    List<GroundedClaim> claims = evaluationRunMapper
                            .findOwnedClaimsByRunId(evaluationRunId, trustedUserId, languageProfileId)
                            .stream()
                            .map(EvaluationRunRepository::toDomain)
                            .toList();
                    return new ValidatedSemanticCandidate(
                            languageProfileId,
                            header.sessionId(),
                            new MaterialIdentity(header.materialId(), header.materialPublishedVersion()),
                            header.rubricReference(),
                            header.targetLanguage(),
                            header.groundingPolicyVersion(),
                            claims);
                });
    }

    private static EvaluationRun toDomain(StoredEvaluationRun run) {
        return new EvaluationRun(
                run.id(),
                run.sessionId(),
                run.modelCallJobId(),
                EvaluationRun.Status.valueOf(run.status()),
                run.workflowVersion(),
                run.rowVersion(),
                run.createdAt(),
                Optional.ofNullable(run.completedAt()),
                Optional.ofNullable(run.groundingRejectionReason()).map(RejectionReason::valueOf));
    }

    private static GroundedClaim toDomain(StoredValidatedClaim claim) {
        return new GroundedClaim(
                claim.sourceTurnId(),
                claim.exactQuote(),
                claim.occurrenceIndex(),
                claim.startOffset(),
                claim.endOffset(),
                IssueType.valueOf(claim.issueType()),
                claim.explanation(),
                claim.confidence());
    }

    private static void validateOwnedArguments(
            UUID sessionIdOrRunId, UUID trustedUserId, UUID languageProfileId) {
        Objects.requireNonNull(sessionIdOrRunId, "sessionId must not be null");
        Objects.requireNonNull(trustedUserId, "trustedUserId must not be null");
        Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
    }
}

record NewEvaluationRunRow(
        UUID id,
        UUID sessionId,
        UUID modelCallJobId,
        UUID trustedUserId,
        UUID languageProfileId,
        String modelPurpose,
        String modelOperation,
        String workflowStepId,
        long workflowVersion) {
}

record StoredEvaluationRun(
        UUID id,
        UUID sessionId,
        UUID modelCallJobId,
        String status,
        long workflowVersion,
        long rowVersion,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt,
        String groundingRejectionReason) {
}

record FinalizeEvaluationRunRow(
        UUID evaluationRunId,
        UUID trustedUserId,
        UUID languageProfileId,
        String status,
        String rejectionReason,
        long expectedRowVersion) {
}

record NewValidatedCandidateRow(
        UUID evaluationRunId,
        UUID trustedUserId,
        UUID languageProfileId,
        UUID sessionId,
        String materialId,
        String materialPublishedVersion,
        String rubricReference,
        String targetLanguage,
        String groundingPolicyVersion) {
}

record StoredValidatedCandidate(
        UUID evaluationRunId,
        UUID sessionId,
        String materialId,
        String materialPublishedVersion,
        String rubricReference,
        String targetLanguage,
        String groundingPolicyVersion,
        OffsetDateTime createdAt) {
}

record NewValidatedClaimRow(
        UUID evaluationRunId,
        UUID trustedUserId,
        UUID languageProfileId,
        int claimIndex,
        String sourceTurnId,
        String exactQuote,
        int occurrenceIndex,
        int startOffset,
        int endOffset,
        String issueType,
        String explanation,
        double confidence) {
}

record StoredValidatedClaim(
        int claimIndex,
        String sourceTurnId,
        String exactQuote,
        int occurrenceIndex,
        int startOffset,
        int endOffset,
        String issueType,
        String explanation,
        double confidence) {
}
