package com.dailylanguage.planner.infrastructure;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.planner.domain.PlanningRun;

@Repository
public class PlanningRunRepository {

    private final PlanningRunMapper planningRunMapper;

    public PlanningRunRepository(PlanningRunMapper planningRunMapper) {
        this.planningRunMapper =
                Objects.requireNonNull(planningRunMapper, "planningRunMapper must not be null");
    }

    /** Run id 必须先于 Job 创建取得：model_call_job.workflow_id 精确等于 PlanningRun.id。 */
    public UUID nextRunId() {
        UUID runId = planningRunMapper.nextRunId();
        if (runId == null) {
            throw new IllegalStateException("database did not provide a uuidv7 planning run id");
        }
        return runId;
    }

    /**
     * INSERT … SELECT gate：insert 时在数据库内原子重校验 profile 归属同一 owner，并 JOIN
     * model_call_job 核对完整创建期 identity——owner/profile、PLANNING / TEXT_GENERATION、
     * workflow_id = run id、workflow_step_id 与 workflow_version。任一不匹配产生零行并以
     * 异常 fail closed，由外层事务回滚同事务内已创建的 Job，不留下 orphan。
     */
    public PlanningRun insertOwned(
            UUID planningRunId,
            UUID modelCallJobId,
            UUID trustedUserId,
            UUID languageProfileId,
            long workflowVersion) {
        Objects.requireNonNull(planningRunId, "planningRunId must not be null");
        Objects.requireNonNull(modelCallJobId, "modelCallJobId must not be null");
        Objects.requireNonNull(trustedUserId, "trustedUserId must not be null");
        Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
        if (workflowVersion < 0) {
            throw new IllegalArgumentException("workflowVersion must not be negative");
        }
        StoredPlanningRun inserted = planningRunMapper.insertOwnedAndReturn(
                new NewPlanningRunRow(
                        planningRunId,
                        modelCallJobId,
                        trustedUserId,
                        languageProfileId,
                        ModelPurpose.PLANNING.name(),
                        ModelOperation.TEXT_GENERATION.name(),
                        PlanningRun.WORKFLOW_STEP_ID,
                        workflowVersion));
        if (inserted == null) {
            throw new IllegalStateException(
                    "planning run insert requires an owned language profile"
                            + " and a planning workflow job with matching creation identity");
        }
        return toDomain(inserted);
    }

    /**
     * 按 Snapshot 内的原始顺序写入 candidate snapshot；写入侧直接接收已裁决的 domain type，
     * profile 一致性在方法内复核。每一行都经 run ownership gate，影响行数不为 1 即以异常
     * fail closed，由外层事务整体回滚已创建的 Run 与 Job。
     */
    public void insertCandidates(
            UUID planningRunId,
            UUID trustedUserId,
            UUID languageProfileId,
            PlanningRun.Snapshot snapshot) {
        Objects.requireNonNull(planningRunId, "planningRunId must not be null");
        Objects.requireNonNull(trustedUserId, "trustedUserId must not be null");
        Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (!snapshot.languageProfileId().equals(languageProfileId)) {
            throw new IllegalArgumentException(
                    "snapshot languageProfileId must match the owned planning profile");
        }
        for (PlanningRun.Candidate candidate : snapshot.candidates()) {
            int inserted = planningRunMapper.insertCandidate(new NewPlanningRunCandidateRow(
                    planningRunId,
                    trustedUserId,
                    languageProfileId,
                    candidate.index(),
                    candidate.identity().materialId(),
                    candidate.identity().publishedVersion()));
            if (inserted != 1) {
                throw new IllegalStateException(
                        "planning run candidate insert requires the owned pending planning run");
            }
        }
    }

    /** owned read：还原 run 本体；ownership 由 planning_run 自身的 owner 列裁决。 */
    public Optional<PlanningRun> findOwned(UUID planningRunId, UUID trustedUserId, UUID languageProfileId) {
        validateOwnedArguments(planningRunId, trustedUserId, languageProfileId);
        return planningRunMapper
                .findOwned(planningRunId, trustedUserId, languageProfileId)
                .map(PlanningRunRepository::toDomain);
    }

    /**
     * owned read：按 candidate_index 升序还原完整 snapshot，并通过 Snapshot 构造器重新裁决
     * non-empty、dense ordered index 与 identity 唯一性；损坏的 durable rows（空、稀疏、重复）
     * 在读取时 fail closed，而不是作为正常结果返回。
     */
    public Optional<PlanningRun.Snapshot> findOwnedSnapshot(
            UUID planningRunId, UUID trustedUserId, UUID languageProfileId) {
        validateOwnedArguments(planningRunId, trustedUserId, languageProfileId);
        return planningRunMapper
                .findOwned(planningRunId, trustedUserId, languageProfileId)
                .map(run -> new PlanningRun.Snapshot(
                        languageProfileId,
                        planningRunMapper
                                .findOwnedCandidates(planningRunId, trustedUserId, languageProfileId)
                                .stream()
                                .map(row -> new PlanningRun.Candidate(
                                        row.candidateIndex(),
                                        new MaterialIdentity(row.materialId(), row.publishedVersion())))
                                .toList()));
    }

    private static PlanningRun toDomain(StoredPlanningRun run) {
        return new PlanningRun(
                run.id(),
                run.userId(),
                run.languageProfileId(),
                run.modelCallJobId(),
                PlanningRun.Status.valueOf(run.status()),
                run.workflowVersion(),
                run.rowVersion(),
                run.createdAt(),
                Optional.ofNullable(run.completedAt()));
    }

    private static void validateOwnedArguments(
            UUID planningRunId, UUID trustedUserId, UUID languageProfileId) {
        Objects.requireNonNull(planningRunId, "planningRunId must not be null");
        Objects.requireNonNull(trustedUserId, "trustedUserId must not be null");
        Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
    }
}

record NewPlanningRunRow(
        UUID id,
        UUID modelCallJobId,
        UUID trustedUserId,
        UUID languageProfileId,
        String modelPurpose,
        String modelOperation,
        String workflowStepId,
        long workflowVersion) {
}

record StoredPlanningRun(
        UUID id,
        UUID userId,
        UUID languageProfileId,
        UUID modelCallJobId,
        String status,
        long workflowVersion,
        long rowVersion,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt) {
}

record NewPlanningRunCandidateRow(
        UUID planningRunId,
        UUID trustedUserId,
        UUID languageProfileId,
        int candidateIndex,
        String materialId,
        String publishedVersion) {
}

record StoredPlanningRunCandidate(int candidateIndex, String materialId, String publishedVersion) {
}
