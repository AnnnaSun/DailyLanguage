package com.dailylanguage.evaluator.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import com.dailylanguage.evaluator.domain.EvaluationRun;
import com.dailylanguage.evaluator.domain.GroundedEvaluationInput;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.RejectionReason;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.Validated;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.ValidatedSemanticCandidate;
import com.dailylanguage.evaluator.infrastructure.EvaluationRunRepository;
import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.infrastructure.ModelCallJobRepository;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.structuredoutput.StructuredOutputValidator;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.practice.domain.PracticeSession;
import com.dailylanguage.security.domain.UserContext;

/**
 * 把 Run 绑定 Job 的 SUCCEEDED raw result 原子转换为 durable Evaluation outcome：同一事务内
 * 完成 Job NOT_READY → CONSUMED（现有 consumption CAS）、validated candidate / safe rejection
 * 持久化与 Run PENDING → terminal CAS——CONSUMED 与 business outcome 要么同时提交，要么同时回滚，
 * 不出现 Job 已消费但 outcome 丢失。terminal Run 直接读取 durable outcome replay，不重新
 * grounding。CREATED / RUNNING 保持 Pending；Model failure、expired、stale 或 discarded result
 * 只终止 Model branch，不影响 deterministic outcome。Job 缺失、
 * identity 不一致、raw result 缺失或无法解释的 CAS 冲突是持久化不变量损坏，以异常 fail closed
 * 并整体回滚，不创建替代数据、不重新调用 Provider。
 */
@Service
public class EvaluationResultConsumptionService {

    private final EvaluationRunRepository evaluationRunRepository;
    private final ModelCallJobRepository modelCallJobRepository;
    private final SemanticGroundingValidator semanticGroundingValidator;
    private final long currentWorkflowVersion;

    @Autowired
    public EvaluationResultConsumptionService(
            EvaluationRunRepository evaluationRunRepository,
            ModelCallJobRepository modelCallJobRepository) {
        this(evaluationRunRepository, modelCallJobRepository, new SemanticGroundingValidator(
                        new StructuredOutputValidator(JsonMapper.builder().build()),
                        new ClasspathRubricSource()),
                EvaluationRun.CURRENT_WORKFLOW_VERSION);
    }

    /** 供测试替换 validator 的 seam；Production 构造使用真实 classpath rubric。 */
    EvaluationResultConsumptionService(
            EvaluationRunRepository evaluationRunRepository,
            ModelCallJobRepository modelCallJobRepository,
            SemanticGroundingValidator semanticGroundingValidator) {
        this(evaluationRunRepository, modelCallJobRepository, semanticGroundingValidator,
                EvaluationRun.CURRENT_WORKFLOW_VERSION);
    }

    /** 供旧 workflow-version reconciliation 测试替换 current version。 */
    EvaluationResultConsumptionService(
            EvaluationRunRepository evaluationRunRepository,
            ModelCallJobRepository modelCallJobRepository,
            SemanticGroundingValidator semanticGroundingValidator,
            long currentWorkflowVersion) {
        this.evaluationRunRepository =
                Objects.requireNonNull(evaluationRunRepository, "evaluationRunRepository must not be null");
        this.modelCallJobRepository =
                Objects.requireNonNull(modelCallJobRepository, "modelCallJobRepository must not be null");
        this.semanticGroundingValidator =
                Objects.requireNonNull(semanticGroundingValidator, "semanticGroundingValidator must not be null");
        if (currentWorkflowVersion < 0) {
            throw new IllegalArgumentException("currentWorkflowVersion must not be negative");
        }
        this.currentWorkflowVersion = currentWorkflowVersion;
    }

    /**
     * userId 只信任 UserContext；jobId 只能从 owner-scoped Run 取得，generated text 只能从该
     * Run 绑定 Job 的 PostgreSQL result 行读取，不接受 HTTP / 调用方提供的 raw JSON 或 jobId。
     */
    @Transactional
    public ConsumptionResult consumeForReadyInput(
            GroundedEvaluationInputResult.Ready ready, UserContext userContext) {
        Objects.requireNonNull(ready, "ready must not be null");
        Objects.requireNonNull(userContext, "userContext must not be null");

        GroundedEvaluationInput input = ready.input();
        UUID userId = userContext.userId();
        if (!userId.equals(input.userId())) {
            return new ConsumptionResult.InconsistentInput();
        }
        UUID languageProfileId = input.languageProfileId();
        UUID sessionId = input.session().id();

        Optional<EvaluationRun> lockedRun = evaluationRunRepository
                .findOwnedBySessionIdForUpdate(sessionId, userId, languageProfileId);
        if (lockedRun.isEmpty()) {
            return new ConsumptionResult.NotFound();
        }
        EvaluationRun run = lockedRun.orElseThrow();
        if (!isReadyBoundToRun(input, run, userId, languageProfileId)) {
            return new ConsumptionResult.InconsistentInput();
        }
        if (run.status() != EvaluationRun.Status.PENDING) {
            return new ConsumptionResult.Existing(readDurableOutcome(run, userId, languageProfileId));
        }

        ModelCallJob job = modelCallJobRepository
                .findByIdAndUserId(run.modelCallJobId(), userId)
                .orElse(null);
        if (job == null || !isEvaluationWorkflowJob(job, run, userId, languageProfileId)) {
            throw new IllegalStateException(
                    "evaluation run has a missing or inconsistent evaluation job");
        }
        switch (job.executionStatus()) {
            case CREATED, RUNNING -> {
                return new ConsumptionResult.Pending();
            }
            case FAILED, TIMED_OUT, OUTCOME_UNKNOWN, SUBMISSION_REJECTED -> {
                return finalizeModelFailure(
                        run, userId, languageProfileId, EvaluationRun.FailureReason.MODEL_CALL_FAILED);
            }
            case SUCCEEDED -> {
            }
        }
        switch (job.consumptionStatus()) {
            case CONSUMED -> throw new IllegalStateException(
                    "evaluation job is already consumed while its run is still pending");
            case PENDING_CONFIRMATION, EXPIRED, STALE, DISCARDED -> {
                return finalizeModelFailure(
                        run, userId, languageProfileId,
                        EvaluationRun.FailureReason.MODEL_RESULT_UNAVAILABLE);
            }
            case NOT_READY -> {
            }
        }

        if (run.workflowVersion() > currentWorkflowVersion) {
            throw new IllegalStateException(
                    "evaluation run workflow version is newer than the current application version");
        }
        if (run.workflowVersion() < currentWorkflowVersion) {
            Optional<ModelCallJob> staleJob = modelCallJobRepository.tryMarkSucceededResultStale(
                    job.id(), userId, currentWorkflowVersion, job.rowVersion());
            if (staleJob.isPresent()) {
                return finalizeModelFailure(
                        run, userId, languageProfileId,
                        EvaluationRun.FailureReason.MODEL_RESULT_UNAVAILABLE);
            }
            return classifyFailedConsumptionCas(job, run, userId, languageProfileId);
        }

        String generatedJson = modelCallJobRepository
                .findTextGenerationResultByJobIdAndUserId(job.id(), userId)
                .map(result -> result.text())
                .orElseThrow(() -> new IllegalStateException(
                        "succeeded evaluation job is missing its durable text generation result"));

        SemanticGroundingResult grounding = semanticGroundingValidator.validate(generatedJson, input);

        Optional<ModelCallJob> consumedJob = modelCallJobRepository.tryConsumeSucceededResult(
                job.id(), userId, run.workflowVersion(), job.rowVersion());
        if (consumedJob.isEmpty()) {
            return classifyFailedConsumptionCas(job, run, userId, languageProfileId);
        }

        if (grounding instanceof Validated validated) {
            ValidatedSemanticCandidate candidate = validated.candidate();
            evaluationRunRepository.insertValidatedCandidate(
                    run.id(), userId, languageProfileId, candidate);
            for (int claimIndex = 0; claimIndex < candidate.claims().size(); claimIndex++) {
                evaluationRunRepository.insertValidatedClaim(
                        run.id(), userId, languageProfileId, claimIndex,
                        candidate.claims().get(claimIndex));
            }
            EvaluationRun finalizedRun = evaluationRunRepository.tryFinalizeOwned(
                    run.id(), userId, languageProfileId, EvaluationRun.Status.SUCCEEDED,
                    Optional.empty(), Optional.empty(), run.rowVersion());
            return new ConsumptionResult.Consumed(
                    new DurableOutcome(finalizedRun, Optional.of(grounding)));
        }
        RejectionReason reason = ((SemanticGroundingResult.Rejected) grounding).reason();
        EvaluationRun finalizedRun = evaluationRunRepository.tryFinalizeOwned(
                run.id(), userId, languageProfileId, EvaluationRun.Status.FAILED,
                Optional.of(EvaluationRun.FailureReason.GROUNDING_REJECTED),
                Optional.of(reason), run.rowVersion());
        return new ConsumptionResult.Consumed(
                new DurableOutcome(finalizedRun, Optional.of(grounding)));
    }

    /** Ready 是内部 trusted snapshot，但不是本次 mutation 的 authorization proof，仍需绑定当前 Run identity。 */
    private static boolean isReadyBoundToRun(
            GroundedEvaluationInput input,
            EvaluationRun run,
            UUID userId,
            UUID languageProfileId) {
        return input.userId().equals(userId)
                && input.languageProfileId().equals(languageProfileId)
                && input.session().id().equals(run.sessionId())
                && input.session().status() == PracticeSession.Status.COMPLETED
                && input.session().taskId().equals(input.task().id())
                && input.task().status() == LearningTask.Status.COMPLETED
                && input.task().userId().equals(userId)
                && input.task().languageProfileId().equals(languageProfileId);
    }

    /**
     * consumption CAS 使用 PostgreSQL CURRENT_TIMESTAMP 裁决 expiry，不能用 JVM 时间提前猜测。
     * Run 行锁只串行化本 Workflow consumer，通用 Job consumer 仍可能并发改变 consumption status；
     * 因此 CAS 失败后重读 durable Job：保持原状态时尝试由数据库标记 EXPIRED，其他 depleted 状态
     * 直接结束 Model branch，
     * CONSUMED + PENDING Run 或 identity 漂移则是跨边界不变量损坏。
     */
    private ConsumptionResult classifyFailedConsumptionCas(
            ModelCallJob attemptedJob,
            EvaluationRun run,
            UUID userId,
            UUID languageProfileId) {
        ModelCallJob currentJob = modelCallJobRepository
                .findByIdAndUserId(attemptedJob.id(), userId)
                .orElseThrow(() -> new IllegalStateException(
                        "evaluation job disappeared after its consumption transition failed"));
        if (!isEvaluationWorkflowJob(currentJob, run, userId, languageProfileId)
                || currentJob.executionStatus() != ModelCallJob.ExecutionStatus.SUCCEEDED) {
            throw new IllegalStateException(
                    "evaluation job changed identity or execution outcome during consumption");
        }
        return switch (currentJob.consumptionStatus()) {
            case NOT_READY -> {
                if (currentJob.rowVersion() != attemptedJob.rowVersion()) {
                    throw new IllegalStateException(
                            "evaluation job row version changed without a consumption transition");
                }
                Optional<ModelCallJob> expiredJob = modelCallJobRepository.tryExpireSucceededResult(
                        currentJob.id(), userId, currentJob.rowVersion());
                if (expiredJob.isEmpty()) {
                    throw new IllegalStateException(
                            "evaluation result transition failed without expiry or a durable state change");
                }
                yield finalizeModelFailure(
                        run, userId, languageProfileId,
                        EvaluationRun.FailureReason.MODEL_RESULT_UNAVAILABLE);
            }
            case PENDING_CONFIRMATION, EXPIRED, STALE, DISCARDED ->
                    finalizeModelFailure(
                            run, userId, languageProfileId,
                            EvaluationRun.FailureReason.MODEL_RESULT_UNAVAILABLE);
            case CONSUMED -> throw new IllegalStateException(
                    "evaluation job was consumed without a matching evaluation outcome");
        };
    }

    private ConsumptionResult.Consumed finalizeModelFailure(
            EvaluationRun run,
            UUID userId,
            UUID languageProfileId,
            EvaluationRun.FailureReason failureReason) {
        EvaluationRun finalizedRun = evaluationRunRepository.tryFinalizeOwned(
                run.id(), userId, languageProfileId, EvaluationRun.Status.FAILED,
                Optional.of(failureReason), Optional.empty(), run.rowVersion());
        return new ConsumptionResult.Consumed(
                new DurableOutcome(finalizedRun, Optional.empty()));
    }

    /** terminal replay：只读取 durable outcome；header 缺失属于不变量损坏。 */
    private DurableOutcome readDurableOutcome(EvaluationRun run, UUID userId, UUID languageProfileId) {
        if (run.status() == EvaluationRun.Status.SUCCEEDED) {
            ValidatedSemanticCandidate candidate = evaluationRunRepository
                    .findOwnedCandidateByRunId(run.id(), userId, languageProfileId)
                    .orElseThrow(() -> new IllegalStateException(
                            "succeeded evaluation run is missing its validated semantic candidate"));
            return new DurableOutcome(
                    run, Optional.of(new SemanticGroundingResult.Validated(candidate)));
        }
        EvaluationRun.FailureReason failureReason = run.failureReason().orElseThrow(() ->
                new IllegalStateException("failed evaluation run is missing its failure reason"));
        if (failureReason != EvaluationRun.FailureReason.GROUNDING_REJECTED) {
            return new DurableOutcome(run, Optional.empty());
        }
        RejectionReason groundingReason = run.groundingRejectionReason().orElseThrow(() ->
                new IllegalStateException("grounding-rejected evaluation run is missing its rejection reason"));
        return new DurableOutcome(
                run, Optional.of(new SemanticGroundingResult.Rejected(groundingReason)));
    }

    /** 与 S8B 创建期一致的完整 identity 校验；任何偏差都是持久化不变量损坏。 */
    private static boolean isEvaluationWorkflowJob(
            ModelCallJob job, EvaluationRun run, UUID userId, UUID languageProfileId) {
        return job.id().equals(run.modelCallJobId())
                && job.workflowId().equals(run.id())
                && EvaluationRun.WORKFLOW_STEP_ID.equals(job.workflowStepId())
                && job.workflowVersion() == run.workflowVersion()
                && job.modelPurpose() == ModelPurpose.EVALUATION
                && job.modelOperation() == ModelOperation.TEXT_GENERATION
                && job.userId().equals(userId)
                && job.languageProfileId().equals(Optional.of(languageProfileId));
    }

    /** Model branch failure 没有 grounding result；其稳定原因保存在 EvaluationRun.failureReason。 */
    public record DurableOutcome(EvaluationRun run, Optional<SemanticGroundingResult> groundingResult) {

        public DurableOutcome {
            Objects.requireNonNull(run, "run must not be null");
            Objects.requireNonNull(groundingResult, "groundingResult must not be null");
            boolean matches = (run.status() == EvaluationRun.Status.SUCCEEDED
                    && groundingResult.orElse(null) instanceof SemanticGroundingResult.Validated)
                    || (run.status() == EvaluationRun.Status.FAILED
                    && run.failureReason().orElse(null) == EvaluationRun.FailureReason.GROUNDING_REJECTED
                    && groundingResult.orElse(null) instanceof SemanticGroundingResult.Rejected)
                    || (run.status() == EvaluationRun.Status.FAILED
                    && run.failureReason().filter(reason ->
                            reason != EvaluationRun.FailureReason.GROUNDING_REJECTED).isPresent()
                    && groundingResult.isEmpty());
            if (!matches) {
                throw new IllegalArgumentException("outcome must match terminal run status");
            }
        }
    }

    /** 互斥消费结果；只有 Consumed / Existing 携带 durable outcome，pending / defer 不用异常表达。 */
    public sealed interface ConsumptionResult {

        record Consumed(DurableOutcome outcome) implements ConsumptionResult {

            public Consumed {
                Objects.requireNonNull(outcome, "outcome must not be null");
            }
        }

        record Existing(DurableOutcome outcome) implements ConsumptionResult {

            public Existing {
                Objects.requireNonNull(outcome, "outcome must not be null");
            }
        }

        /** 绑定 Job 仍为 CREATED / RUNNING，尚无可消费的 durable result。 */
        record Pending() implements ConsumptionResult {
        }

        /** owner/profile 范围内不存在该 Session 的 Run。 */
        record NotFound() implements ConsumptionResult {
        }

        /** Ready 与 authenticated caller / Run / Session 不一致。 */
        record InconsistentInput() implements ConsumptionResult {
        }
    }
}
