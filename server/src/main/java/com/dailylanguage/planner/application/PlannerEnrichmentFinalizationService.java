package com.dailylanguage.planner.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.infrastructure.ModelCallJobRepository;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.planner.domain.PlanningCandidateSet;
import com.dailylanguage.planner.domain.PlanningCandidateSetResult;
import com.dailylanguage.planner.domain.PlanningRequest;
import com.dailylanguage.planner.domain.PlanningResult;
import com.dailylanguage.planner.domain.PlanningRun;
import com.dailylanguage.planner.domain.PlannerEnrichmentResult;
import com.dailylanguage.planner.infrastructure.LearningTaskRepository;
import com.dailylanguage.planner.infrastructure.PlanningRunRepository;
import com.dailylanguage.security.domain.UserContext;

/**
 * 把 Run 绑定 Job 的 durable 结果在单一事务、owner-scoped PlanningRun 行锁下裁决为 exactly-one
 * LearningTask 与 terminal Run outcome：valid SUCCEEDED output 走 Job NOT_READY → CONSUMED、
 * MODEL_ENRICHED task 创建与 Run MODEL_APPLIED finalize 的同事务提交；Model failure、
 * depleted/expired result、rejected output 或 wait budget exhausted 落到 snapshot index 0 的
 * deterministic task 与 FALLBACK_APPLIED（closed FallbackReason）。等待与 Model 调用必须发生在
 * 本事务之外：本 Service 只消费调用方传入的 {@link PlannerEnrichmentJobAwaiter.AwaitResult} 信号，
 * 一切裁决以事务内重新读取的 durable Run / snapshot / Job 状态为准。terminal Run 直接 replay
 * durable bound task，不重新消费或创建。Job 缺失、workflow identity 漂移、raw result 缺失、
 * consumed Job 配 pending Run 或无法解释的 CAS 冲突属于持久化不变量损坏，以异常 fail closed
 * 并整体回滚，不创建替代 task、不重新调用 Provider。rejected JSON、Provider detail 与
 * Credential 不进入任何返回值或日志。
 */
@Service
public class PlannerEnrichmentFinalizationService {

    private final PlanningRunRepository planningRunRepository;
    private final ModelCallJobRepository modelCallJobRepository;
    private final LearningTaskRepository learningTaskRepository;
    private final EligibleLearningTaskCandidateReader candidateReader;
    private final PlannerEnrichmentOutputValidator outputValidator;

    @Autowired
    public PlannerEnrichmentFinalizationService(
            PlanningRunRepository planningRunRepository,
            ModelCallJobRepository modelCallJobRepository,
            LearningTaskRepository learningTaskRepository,
            EligibleLearningTaskCandidateReader candidateReader) {
        this(planningRunRepository, modelCallJobRepository, learningTaskRepository,
                candidateReader, new PlannerEnrichmentOutputValidator());
    }

    /** 供测试替换 validator 的 seam；Production 构造使用共享 strict output contract。 */
    PlannerEnrichmentFinalizationService(
            PlanningRunRepository planningRunRepository,
            ModelCallJobRepository modelCallJobRepository,
            LearningTaskRepository learningTaskRepository,
            EligibleLearningTaskCandidateReader candidateReader,
            PlannerEnrichmentOutputValidator outputValidator) {
        this.planningRunRepository =
                Objects.requireNonNull(planningRunRepository, "planningRunRepository must not be null");
        this.modelCallJobRepository =
                Objects.requireNonNull(modelCallJobRepository, "modelCallJobRepository must not be null");
        this.learningTaskRepository =
                Objects.requireNonNull(learningTaskRepository, "learningTaskRepository must not be null");
        this.candidateReader =
                Objects.requireNonNull(candidateReader, "candidateReader must not be null");
        this.outputValidator = Objects.requireNonNull(outputValidator, "outputValidator must not be null");
    }

    /**
     * userId 只信任 UserContext；planningRunId 定位 owner-scoped Run，awaitResult 只是事务外等待的
     * 信号（BudgetExhausted 唯一携带裁决语义：迟到成功也不得应用 Model）。request 必须是创建该 Run
     * 的同一 PlanningRequest——profile、support language、difficulty 与 available minutes 共同参与
     * snapshot re-resolution 与 task 构建。
     */
    @Transactional
    public FinalizationResult finalizeRun(
            UUID planningRunId,
            PlanningRequest request,
            PlannerEnrichmentJobAwaiter.AwaitResult awaitResult,
            UserContext userContext) {
        Objects.requireNonNull(planningRunId, "planningRunId must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(awaitResult, "awaitResult must not be null");
        Objects.requireNonNull(userContext, "userContext must not be null");
        if (!userContext.userId().equals(request.languageProfile().userId())) {
            throw new IllegalArgumentException(
                    "userContext must match the planning request language profile owner");
        }
        UUID userId = userContext.userId();
        UUID languageProfileId = request.languageProfile().id();

        Optional<PlanningRun> lockedRun =
                planningRunRepository.findOwnedForUpdate(planningRunId, userId, languageProfileId);
        if (lockedRun.isEmpty()) {
            return new FinalizationResult.NotFound();
        }
        PlanningRun run = lockedRun.orElseThrow();
        if (run.status() != PlanningRun.Status.PENDING) {
            return new FinalizationResult.Existing(readDurableBoundTask(run, userId, languageProfileId), run);
        }

        PlanningRun.Snapshot snapshot = planningRunRepository
                .findOwnedSnapshot(planningRunId, userId, languageProfileId)
                .orElseThrow(() -> new IllegalStateException(
                        "pending planning run is missing its durable candidate snapshot"));
        PlanningCandidateSetResult resolution = candidateReader.resolveSnapshot(request, snapshot);
        if (!(resolution instanceof PlanningCandidateSetResult.Available available)) {
            // Snapshot member 在当前 Content 下缺失或 metadata 漂移：整组 fail closed，
            // 不创建任何 task（含 deterministic fallback），Run 保持 PENDING。
            return new FinalizationResult.Unavailable(
                    ((PlanningCandidateSetResult.Unavailable) resolution).reason());
        }
        PlanningCandidateSet candidateSet = available.candidateSet();

        ModelCallJob job = modelCallJobRepository
                .findByIdAndUserId(run.modelCallJobId(), userId)
                .orElseThrow(() -> new IllegalStateException(
                        "planning run has a missing or inconsistent planning job"));
        if (!isPlannerWorkflowJob(job, run, userId, languageProfileId)) {
            throw new IllegalStateException("planning run has a missing or inconsistent planning job");
        }
        if (awaitResult instanceof PlannerEnrichmentJobAwaiter.AwaitResult.Terminal terminal
                && !terminal.job().id().equals(run.modelCallJobId())) {
            throw new IllegalStateException(
                    "awaited planning job does not match the run bound planning job");
        }

        // budget 到期后即使观察到成功结果也不得应用 Model：先完成 late SUCCEEDED Job 的
        // consumption 分类与 STALE 记账（CONSUMED + PENDING Run 在此 fail closed），
        // 分类通过后才原子提交 fallback。
        if (awaitResult instanceof PlannerEnrichmentJobAwaiter.AwaitResult.BudgetExhausted) {
            markLateSucceededStaleOrFailClosed(job, run, userId, languageProfileId);
            return finalizeWithDeterministicTask(
                    run, userId, languageProfileId, candidateSet,
                    PlanningRun.FallbackReason.WAIT_BUDGET_EXHAUSTED);
        }

        return switch (job.executionStatus()) {
            case CREATED, RUNNING -> throw new IllegalStateException(
                    "planning job has not reached a terminal execution status");
            case FAILED, TIMED_OUT, OUTCOME_UNKNOWN, SUBMISSION_REJECTED -> finalizeWithDeterministicTask(
                    run, userId, languageProfileId, candidateSet,
                    PlanningRun.FallbackReason.MODEL_CALL_FAILED);
            case SUCCEEDED -> consumeSucceededResult(run, userId, languageProfileId, candidateSet, job);
        };
    }

    private FinalizationResult consumeSucceededResult(
            PlanningRun run,
            UUID userId,
            UUID languageProfileId,
            PlanningCandidateSet candidateSet,
            ModelCallJob job) {
        switch (job.consumptionStatus()) {
            case CONSUMED -> throw new IllegalStateException(
                    "planning job is already consumed while its run is still pending");
            case PENDING_CONFIRMATION, EXPIRED, STALE, DISCARDED -> {
                return finalizeWithDeterministicTask(run, userId, languageProfileId, candidateSet,
                        PlanningRun.FallbackReason.MODEL_RESULT_UNAVAILABLE);
            }
            case NOT_READY -> {
            }
        }

        String generatedJson = modelCallJobRepository
                .findTextGenerationResultByJobIdAndUserId(job.id(), userId)
                .map(result -> result.text())
                .orElseThrow(() -> new IllegalStateException(
                        "succeeded planning job is missing its durable text generation result"));
        PlannerEnrichmentResult validated = outputValidator.validate(generatedJson, candidateSet);

        Optional<ModelCallJob> consumedJob = modelCallJobRepository.tryConsumeSucceededResult(
                job.id(), userId, job.workflowVersion(), job.rowVersion());
        if (consumedJob.isEmpty()) {
            return classifyFailedConsumptionCas(job, run, userId, languageProfileId, candidateSet);
        }

        if (validated instanceof PlannerEnrichmentResult.Valid valid) {
            LearningTask enrichedTask = learningTaskRepository
                    .createOwned(userId, toEnrichedPlan(findMatchedPlan(candidateSet, valid), valid))
                    .orElseThrow(() -> new IllegalStateException(
                            "model enriched learning task was not created"));
            PlanningRun finalizedRun = planningRunRepository.tryFinalizeOwned(
                    run.id(), userId, languageProfileId, PlanningRun.Status.MODEL_APPLIED,
                    Optional.empty(), enrichedTask.id(), run.rowVersion());
            return new FinalizationResult.Created(enrichedTask, finalizedRun);
        }
        // rejected output 同样消费 Job：已知不可用的 result 不允许后续再次进入裁决。
        return finalizeWithDeterministicTask(run, userId, languageProfileId, candidateSet,
                PlanningRun.FallbackReason.MODEL_OUTPUT_REJECTED);
    }

    /**
     * consumption CAS 使用 PostgreSQL CURRENT_TIMESTAMP 裁决 expiry，不能用 JVM 时间提前猜测。
     * Run 行锁只串行化本 Workflow consumer，通用 Job consumer 仍可能并发改变 consumption status；
     * 因此 CAS 失败后重读 durable Job：保持原状态时尝试由数据库标记 EXPIRED，其他 depleted 状态
     * 直接结束 Model branch，CONSUMED + PENDING Run 或 identity 漂移是跨边界不变量损坏。
     */
    private FinalizationResult classifyFailedConsumptionCas(
            ModelCallJob attemptedJob,
            PlanningRun run,
            UUID userId,
            UUID languageProfileId,
            PlanningCandidateSet candidateSet) {
        ModelCallJob currentJob = modelCallJobRepository
                .findByIdAndUserId(attemptedJob.id(), userId)
                .orElseThrow(() -> new IllegalStateException(
                        "planning job disappeared after its consumption transition failed"));
        if (!isPlannerWorkflowJob(currentJob, run, userId, languageProfileId)
                || currentJob.executionStatus() != ModelCallJob.ExecutionStatus.SUCCEEDED) {
            throw new IllegalStateException(
                    "planning job changed identity or execution outcome during consumption");
        }
        return switch (currentJob.consumptionStatus()) {
            case NOT_READY -> {
                if (currentJob.rowVersion() != attemptedJob.rowVersion()) {
                    throw new IllegalStateException(
                            "planning job row version changed without a consumption transition");
                }
                if (modelCallJobRepository
                        .tryExpireSucceededResult(currentJob.id(), userId, currentJob.rowVersion())
                        .isEmpty()) {
                    throw new IllegalStateException(
                            "planning result transition failed without expiry or a durable state change");
                }
                yield finalizeWithDeterministicTask(run, userId, languageProfileId, candidateSet,
                        PlanningRun.FallbackReason.MODEL_RESULT_UNAVAILABLE);
            }
            case PENDING_CONFIRMATION, EXPIRED, STALE, DISCARDED ->
                    finalizeWithDeterministicTask(run, userId, languageProfileId, candidateSet,
                            PlanningRun.FallbackReason.MODEL_RESULT_UNAVAILABLE);
            case CONSUMED -> throw new IllegalStateException(
                    "planning job was consumed without a matching planning outcome");
        };
    }

    /**
     * BudgetExhausted 下 late SUCCEEDED Job 的记账分类，必须先于 fallback 提交执行：
     * 已 depleted（PENDING_CONFIRMATION / EXPIRED / STALE / DISCARDED）时无可转换结果，直接放行；
     * NOT_READY 时以 FALLBACK_APPLIED 将推进到的 workflow version（run.workflowVersion + 1）标记
     * STALE。STALE CAS 失败后重读 durable Job——与 {@link #classifyFailedConsumptionCas} 同一
     * 分类原则：CONSUMED、identity / execution 漂移或未发生任何 durable transition 都属于
     * 持久化不变量损坏，以异常整体回滚，不提交 fallback。非 SUCCEEDED Job 不参与 consumption 记账。
     */
    private void markLateSucceededStaleOrFailClosed(
            ModelCallJob job, PlanningRun run, UUID userId, UUID languageProfileId) {
        if (job.executionStatus() != ModelCallJob.ExecutionStatus.SUCCEEDED) {
            return;
        }
        switch (job.consumptionStatus()) {
            case CONSUMED -> throw new IllegalStateException(
                    "planning job is already consumed while its run is still pending");
            case PENDING_CONFIRMATION, EXPIRED, STALE, DISCARDED -> {
                return;
            }
            case NOT_READY -> {
            }
        }
        Optional<ModelCallJob> staleJob = modelCallJobRepository.tryMarkSucceededResultStale(
                job.id(), userId, run.workflowVersion() + 1L, job.rowVersion());
        if (staleJob.isPresent()) {
            return;
        }
        ModelCallJob currentJob = modelCallJobRepository
                .findByIdAndUserId(job.id(), userId)
                .orElseThrow(() -> new IllegalStateException(
                        "planning job disappeared after its stale transition failed"));
        if (!isPlannerWorkflowJob(currentJob, run, userId, languageProfileId)
                || currentJob.executionStatus() != ModelCallJob.ExecutionStatus.SUCCEEDED) {
            throw new IllegalStateException(
                    "planning job changed identity or execution outcome during stale marking");
        }
        switch (currentJob.consumptionStatus()) {
            case CONSUMED -> throw new IllegalStateException(
                    "planning job was consumed without a matching planning outcome");
            case PENDING_CONFIRMATION, EXPIRED, STALE, DISCARDED -> {
                return;
            }
            case NOT_READY -> {
                if (currentJob.rowVersion() != job.rowVersion()) {
                    throw new IllegalStateException(
                            "planning job row version changed without a consumption transition");
                }
                if (modelCallJobRepository
                        .tryExpireSucceededResult(currentJob.id(), userId, currentJob.rowVersion())
                        .isEmpty()) {
                    throw new IllegalStateException(
                            "planning result transition failed without expiry or a durable state change");
                }
            }
        }
    }

    /** 创建 deterministic task 并 finalize FALLBACK_APPLIED；task 与 outcome 同事务原子提交。 */
    private FinalizationResult.Created finalizeWithDeterministicTask(
            PlanningRun run,
            UUID userId,
            UUID languageProfileId,
            PlanningCandidateSet candidateSet,
            PlanningRun.FallbackReason fallbackReason) {
        LearningTask fallbackTask = learningTaskRepository
                .createOwned(userId, candidateSet.candidates().getFirst())
                .orElseThrow(() -> new IllegalStateException(
                        "deterministic fallback learning task was not created"));
        PlanningRun finalizedRun = planningRunRepository.tryFinalizeOwned(
                run.id(), userId, languageProfileId, PlanningRun.Status.FALLBACK_APPLIED,
                Optional.of(fallbackReason), fallbackTask.id(), run.rowVersion());
        return new FinalizationResult.Created(fallbackTask, finalizedRun);
    }

    /** terminal replay：只读取 durable bound task；行缺失属于不变量损坏。 */
    private LearningTask readDurableBoundTask(PlanningRun run, UUID userId, UUID languageProfileId) {
        return learningTaskRepository
                .findOwned(run.learningTaskId().orElseThrow(), userId, languageProfileId)
                .orElseThrow(() -> new IllegalStateException(
                        "terminal planning run is missing its bound learning task"));
    }

    private static LearningTaskPlan toEnrichedPlan(
            LearningTaskPlan matched, PlannerEnrichmentResult.Valid valid) {
        return new LearningTaskPlan(
                matched.languageProfileId(),
                matched.materialIdentity(),
                matched.targetLanguage(),
                matched.supportLanguage(),
                matched.difficulty(),
                matched.estimatedDurationMinutes(),
                matched.scenario(),
                matched.primaryGoal(),
                matched.taskType(),
                LearningTaskPlan.PlanningReason.MODEL_ENRICHED,
                Optional.of(valid.recommendationReason()));
    }

    private static LearningTaskPlan findMatchedPlan(
            PlanningCandidateSet candidateSet, PlannerEnrichmentResult.Valid valid) {
        MaterialIdentity selectedIdentity = valid.selectedIdentity();
        return candidateSet.candidates().stream()
                .filter(plan -> plan.materialIdentity().equals(selectedIdentity))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "validated planner enrichment selection is not a re-resolved snapshot candidate"));
    }

    /** 与 S9C2 创建期一致的完整 identity 校验；任何偏差都是持久化不变量损坏。 */
    private static boolean isPlannerWorkflowJob(
            ModelCallJob job, PlanningRun run, UUID userId, UUID languageProfileId) {
        return job.id().equals(run.modelCallJobId())
                && job.workflowId().equals(run.id())
                && PlanningRun.WORKFLOW_STEP_ID.equals(job.workflowStepId())
                && job.workflowVersion() == run.workflowVersion()
                && job.modelPurpose() == ModelPurpose.PLANNING
                && job.modelOperation() == ModelOperation.TEXT_GENERATION
                && job.userId().equals(userId)
                && job.languageProfileId().equals(Optional.of(languageProfileId));
    }

    /** 互斥裁决结果；Created / Existing 携带 bound task 与 terminal run，不含任何 Model 细节。 */
    public sealed interface FinalizationResult {

        record Created(LearningTask task, PlanningRun run) implements FinalizationResult {

            public Created {
                requireTerminalPairing(task, run);
            }
        }

        record Existing(LearningTask task, PlanningRun run) implements FinalizationResult {

            public Existing {
                requireTerminalPairing(task, run);
            }
        }

        record NotFound() implements FinalizationResult {
        }

        /** snapshot re-resolution 失败：未创建任何 task，Run 保持 PENDING。 */
        record Unavailable(PlanningResult.UnavailableReason reason) implements FinalizationResult {

            public Unavailable {
                Objects.requireNonNull(reason, "reason must not be null");
            }
        }

        private static void requireTerminalPairing(LearningTask task, PlanningRun run) {
            Objects.requireNonNull(task, "task must not be null");
            Objects.requireNonNull(run, "run must not be null");
            if (run.status() == PlanningRun.Status.PENDING
                    || !run.learningTaskId().map(task.id()::equals).orElse(false)) {
                throw new IllegalArgumentException(
                        "finalization result must bind the task to a terminal planning run");
            }
        }
    }
}
