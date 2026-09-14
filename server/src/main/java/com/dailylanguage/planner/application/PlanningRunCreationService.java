package com.dailylanguage.planner.application;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.domain.NewModelCallJob;
import com.dailylanguage.modelcalljob.infrastructure.ModelCallJobRepository;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.planner.domain.PlanningCandidateSet;
import com.dailylanguage.planner.domain.PlanningRun;
import com.dailylanguage.planner.infrastructure.PlanningRunRepository;
import com.dailylanguage.security.domain.UserContext;

/**
 * 在任何 Model dispatch 之前，于独立 REQUIRES_NEW 事务内原子创建一个 PlanningRun、其 ordered
 * candidate snapshot 与唯一 route-unresolved PLANNING / TEXT_GENERATION ModelCallJob。
 * REQUIRES_NEW 保证方法返回时三者已 durable；任一写入失败由同一事务整体回滚，不留下 orphan
 * Job 或 partial snapshot。与 S8B 不同，planner 没有父 Session 前置条件：candidate set 已由
 * S9A 的 Java hard constraints 产生，非法组合属于 programming error，直接 fail closed，因此
 * 不引入 typed business 分支。本 slice 不 dispatch、不做 request idempotency 或 active-run
 * uniqueness；同一 Profile 的重复调用创建独立 Run。
 */
@Service
public class PlanningRunCreationService {

    /** S9 Planner workflow identity：绑定的 Job 与 Run 必须一致持有。 */
    public static final String WORKFLOW_STEP_ID = PlanningRun.WORKFLOW_STEP_ID;
    public static final long WORKFLOW_VERSION = PlanningRun.CURRENT_WORKFLOW_VERSION;

    private final ModelCallJobRepository modelCallJobRepository;
    private final PlanningRunRepository planningRunRepository;

    public PlanningRunCreationService(
            ModelCallJobRepository modelCallJobRepository,
            PlanningRunRepository planningRunRepository) {
        this.modelCallJobRepository =
                Objects.requireNonNull(modelCallJobRepository, "modelCallJobRepository must not be null");
        this.planningRunRepository =
                Objects.requireNonNull(planningRunRepository, "planningRunRepository must not be null");
    }

    /**
     * trustedUserId 只能来自 authenticated UserContext；candidate set 不是 authorization proof，
     * owner/profile 一致性由 planning_run 的 composite FK 与 INSERT … SELECT gate 在数据库内
     * 原子重裁决。resultExpiresAt 是内部可信参数（S9D 负责按配置生成），绑定到 Job 的
     * expires_at，不是 Model execution timeout。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Created create(
            UserContext userContext, PlanningCandidateSet candidateSet, OffsetDateTime resultExpiresAt) {
        Objects.requireNonNull(userContext, "userContext must not be null");
        Objects.requireNonNull(candidateSet, "candidateSet must not be null");
        Objects.requireNonNull(resultExpiresAt, "resultExpiresAt must not be null");

        PlanningRun.Snapshot snapshot = PlanningRun.Snapshot.fromCandidateSet(candidateSet);
        UUID userId = userContext.userId();
        UUID languageProfileId = snapshot.languageProfileId();

        // Run id 先于 Job 创建取得：model_call_job.workflow_id 必须精确等于 PlanningRun.id。
        UUID runId = planningRunRepository.nextRunId();
        ModelCallJob job = modelCallJobRepository.create(new NewModelCallJob(
                userId,
                Optional.of(languageProfileId),
                ModelPurpose.PLANNING,
                ModelOperation.TEXT_GENERATION,
                Optional.empty(),
                Optional.empty(),
                runId,
                WORKFLOW_STEP_ID,
                WORKFLOW_VERSION,
                resultExpiresAt));
        PlanningRun run = planningRunRepository.insertOwned(
                runId, job.id(), userId, languageProfileId, WORKFLOW_VERSION);
        planningRunRepository.insertCandidates(runId, userId, languageProfileId, snapshot);
        return new Created(run, job, snapshot);
    }

    /** 创建成功结果；run / job / snapshot 在同一 REQUIRES_NEW 事务内提交。 */
    public record Created(PlanningRun run, ModelCallJob job, PlanningRun.Snapshot snapshot) {

        public Created {
            Objects.requireNonNull(run, "run must not be null");
            Objects.requireNonNull(job, "job must not be null");
            Objects.requireNonNull(snapshot, "snapshot must not be null");
        }
    }
}
