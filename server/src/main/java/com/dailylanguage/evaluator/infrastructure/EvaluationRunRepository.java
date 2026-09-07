package com.dailylanguage.evaluator.infrastructure;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.dailylanguage.evaluator.domain.EvaluationRun;
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

    private static EvaluationRun toDomain(StoredEvaluationRun run) {
        return new EvaluationRun(
                run.id(),
                run.sessionId(),
                run.modelCallJobId(),
                EvaluationRun.Status.valueOf(run.status()),
                run.workflowVersion(),
                run.rowVersion(),
                run.createdAt(),
                Optional.ofNullable(run.completedAt()));
    }

    private static void validateOwnedArguments(
            UUID sessionId, UUID trustedUserId, UUID languageProfileId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
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
        OffsetDateTime completedAt) {
}
