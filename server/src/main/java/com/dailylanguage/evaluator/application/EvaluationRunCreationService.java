package com.dailylanguage.evaluator.application;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.evaluator.domain.EvaluationRun;
import com.dailylanguage.evaluator.domain.GroundedEvaluationInput;
import com.dailylanguage.evaluator.infrastructure.EvaluationRunRepository;
import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.domain.NewModelCallJob;
import com.dailylanguage.modelcalljob.infrastructure.ModelCallJobRepository;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.practice.domain.PracticeSession;
import com.dailylanguage.practice.infrastructure.PracticeSessionRepository;
import com.dailylanguage.security.domain.UserContext;

/**
 * 在任何 Model dispatch 之前，于独立 REQUIRES_NEW 事务内原子建立一个 EvaluationRun 及其唯一
 * ModelCallJob。REQUIRES_NEW 保证方法返回时两条记录已 durable，后续 dispatch（S8D）只能发生在
 * 提交之后；即使调用方意外存在外层事务，也不会让 dispatch 落在 Run/Job 未提交的窗口内。
 * 并发 / 重复请求经 practice_session 行锁串行化：后到者查询到既有关联并返回同一 Run + Job，
 * 本 slice 不创建第二次 Evaluation，也不把重复解释为 retry。不调用 TextGenerationJobStart 或
 * 任何 submission / dispatch boundary。
 */
@Service
public class EvaluationRunCreationService {

    /** S8 Evaluation workflow identity：绑定的 Job 与 Run 必须一致持有。 */
    public static final String WORKFLOW_STEP_ID = EvaluationRun.WORKFLOW_STEP_ID;
    public static final long WORKFLOW_VERSION = EvaluationRun.CURRENT_WORKFLOW_VERSION;

    private final PracticeSessionRepository practiceSessionRepository;
    private final ModelCallJobRepository modelCallJobRepository;
    private final EvaluationRunRepository evaluationRunRepository;

    public EvaluationRunCreationService(
            PracticeSessionRepository practiceSessionRepository,
            ModelCallJobRepository modelCallJobRepository,
            EvaluationRunRepository evaluationRunRepository) {
        this.practiceSessionRepository =
                Objects.requireNonNull(practiceSessionRepository, "practiceSessionRepository must not be null");
        this.modelCallJobRepository =
                Objects.requireNonNull(modelCallJobRepository, "modelCallJobRepository must not be null");
        this.evaluationRunRepository =
                Objects.requireNonNull(evaluationRunRepository, "evaluationRunRepository must not be null");
    }

    /**
     * trustedUserId 只能来自 authenticated UserContext；Ready input 不是 authorization proof，
     * userId 一致性在读取 Session 前裁决，ownership / completion / task 关系由锁定后的 durable
     * Session 与数据库 insert gate 重新验证。resultExpiresAt 是内部可信参数（S8D 负责按配置生成）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreationResult createForReadyInput(
            GroundedEvaluationInputResult.Ready ready,
            UserContext userContext,
            OffsetDateTime resultExpiresAt) {
        Objects.requireNonNull(ready, "ready must not be null");
        Objects.requireNonNull(userContext, "userContext must not be null");
        Objects.requireNonNull(resultExpiresAt, "resultExpiresAt must not be null");

        GroundedEvaluationInput input = ready.input();
        UUID userId = userContext.userId();
        if (!userId.equals(input.userId())) {
            return new CreationResult.InconsistentInput();
        }
        UUID languageProfileId = input.languageProfileId();
        UUID sessionId = input.session().id();

        Optional<PracticeSession> lockedSession = practiceSessionRepository
                .findOwnedForUpdate(sessionId, userId, languageProfileId);
        if (lockedSession.isEmpty()) {
            return new CreationResult.NotFound();
        }
        PracticeSession session = lockedSession.orElseThrow();
        if (session.status() != PracticeSession.Status.COMPLETED) {
            return new CreationResult.NotCompleted();
        }
        if (!session.taskId().equals(input.task().id())) {
            return new CreationResult.InconsistentInput();
        }

        Optional<EvaluationRun> existingRun = evaluationRunRepository
                .findOwnedBySessionId(sessionId, userId, languageProfileId);
        if (existingRun.isPresent()) {
            EvaluationRun run = existingRun.orElseThrow();
            ModelCallJob job = modelCallJobRepository
                    .findByIdAndUserId(run.modelCallJobId(), userId)
                    .orElse(null);
            if (job == null || !isEvaluationWorkflowJob(job, run, userId, languageProfileId)) {
                // 持久化不变量损坏（Run 的关联 Job 缺失或创建期 identity 不一致）：这是 durable
                // state 问题而非 caller input 问题，以异常 fail closed——不创建替代 Job、不静默
                // 修复，异常不携带 ids / 敏感内容，避免被上层当作普通输入拒绝。
                throw new IllegalStateException(
                        "existing evaluation run has a missing or inconsistent evaluation job");
            }
            return new CreationResult.Existing(run, job);
        }

        // Run id 先于 Job 创建取得：model_call_job.workflow_id 必须精确等于 EvaluationRun.id。
        UUID evaluationRunId = evaluationRunRepository.nextRunId();
        ModelCallJob job = modelCallJobRepository.create(new NewModelCallJob(
                userId,
                Optional.of(languageProfileId),
                ModelPurpose.EVALUATION,
                ModelOperation.TEXT_GENERATION,
                Optional.empty(),
                Optional.empty(),
                evaluationRunId,
                WORKFLOW_STEP_ID,
                WORKFLOW_VERSION,
                resultExpiresAt));
        EvaluationRun run = evaluationRunRepository.insertOwned(
                evaluationRunId, sessionId, job.id(), userId, languageProfileId, WORKFLOW_VERSION);
        return new CreationResult.Created(run, job);
    }

    /** 既有 Run 的关联 Job 必须精确匹配 Evaluation workflow identity；任何偏差都是不变量损坏。 */
    private static boolean isEvaluationWorkflowJob(
            ModelCallJob job, EvaluationRun run, UUID userId, UUID languageProfileId) {
        return job.workflowId().equals(run.id())
                && WORKFLOW_STEP_ID.equals(job.workflowStepId())
                && job.workflowVersion() == run.workflowVersion()
                && job.modelPurpose() == ModelPurpose.EVALUATION
                && job.modelOperation() == ModelOperation.TEXT_GENERATION
                && job.userId().equals(userId)
                && job.languageProfileId().equals(Optional.of(languageProfileId));
    }

    /** 互斥创建结果；只有 Created / Existing 携带 durable 状态，其余为 fail-closed 业务分支。 */
    public sealed interface CreationResult {

        record Created(EvaluationRun run, ModelCallJob job) implements CreationResult {

            public Created {
                Objects.requireNonNull(run, "run must not be null");
                Objects.requireNonNull(job, "job must not be null");
            }
        }

        record Existing(EvaluationRun run, ModelCallJob job) implements CreationResult {

            public Existing {
                Objects.requireNonNull(run, "run must not be null");
                Objects.requireNonNull(job, "job must not be null");
            }
        }

        /** owner/profile 范围内不存在该 Session。 */
        record NotFound() implements CreationResult {
        }

        /** Session 尚未 COMPLETED，还不能进入 Evaluation。 */
        record NotCompleted() implements CreationResult {
        }

        /** Ready、authenticated identity 或锁定后的实体关系不一致。 */
        record InconsistentInput() implements CreationResult {
        }
    }
}
