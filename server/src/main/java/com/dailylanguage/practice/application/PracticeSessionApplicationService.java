package com.dailylanguage.practice.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.content.domain.LearningMaterialCatalog;
import com.dailylanguage.content.domain.MaterialQueryResult;
import com.dailylanguage.content.domain.PublishedLearningMaterial;
import com.dailylanguage.content.domain.SupportScaffold;
import com.dailylanguage.content.domain.TargetPracticeCore;
import com.dailylanguage.content.domain.TextPracticeStep;
import com.dailylanguage.content.domain.TextReadingInfo;
import com.dailylanguage.content.domain.TextStepKind;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.infrastructure.LearningTaskRepository;
import com.dailylanguage.practice.domain.DeterministicAssessment;
import com.dailylanguage.practice.domain.DeterministicTextAssessmentPolicy;
import com.dailylanguage.practice.domain.PracticeSession;
import com.dailylanguage.practice.infrastructure.PracticeSessionRepository;
import com.dailylanguage.security.domain.UserContext;

/**
 * PracticeSession lifecycle 的 Application 编排。start 把 Task transition 与 Session insert
 * 放在同一个 Spring 事务内：Session insert/read 的 unexpected failure 以异常结束事务，
 * Task 随之回滚到 PLANNED，不会留下孤立的 STARTED Task。response 提交先锁定 Session 行再
 * 写入，(sessionId, stepId) 的首次接受由数据库 identity 裁决，exact payload 重放返回首次
 * submittedAt，不同 payload 冲突不覆盖。completion 与 response submission 使用相同的
 * Session-row-first 锁序：锁定 → 校验 → 计算 deterministic assessment → Session/Task 双
 * transition 与 assessment insert 原子提交；completed replay 只读 durable 结果，不重新依赖
 * catalog。userId 只信任 {@link UserContext}。
 */
@Service
public class PracticeSessionApplicationService {

    private final LearningTaskRepository learningTaskRepository;
    private final PracticeSessionRepository practiceSessionRepository;
    private final LearningMaterialCatalog materialCatalog;

    public PracticeSessionApplicationService(
            LearningTaskRepository learningTaskRepository,
            PracticeSessionRepository practiceSessionRepository,
            LearningMaterialCatalog materialCatalog) {
        this.learningTaskRepository =
                Objects.requireNonNull(learningTaskRepository, "learningTaskRepository must not be null");
        this.practiceSessionRepository =
                Objects.requireNonNull(practiceSessionRepository, "practiceSessionRepository must not be null");
        this.materialCatalog =
                Objects.requireNonNull(materialCatalog, "materialCatalog must not be null");
    }

    /**
     * find owned Task → resolve exact material → find existing owned Session → tryStart Task →
     * insert PracticeSession → read durable Session → commit，全部处于本方法的一个事务内。
     */
    @Transactional
    public StartResult start(UUID languageProfileId, UUID taskId, UserContext userContext) {
        Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(userContext, "userContext must not be null");

        Optional<LearningTask> ownedTask = learningTaskRepository.findOwned(
                taskId, userContext.userId(), languageProfileId);
        if (ownedTask.isEmpty()) {
            // unknown、wrong-owner 与 wrong-profile 对外不可区分。
            return new StartResult.TaskNotFound();
        }
        LearningTask task = ownedTask.orElseThrow();

        Optional<MaterialQueryResult.Available> resolvedMaterial = resolveExactMaterial(task);
        if (resolvedMaterial.isEmpty()) {
            return new StartResult.MaterialUnavailable();
        }
        PracticeMaterialView materialView = PracticeMaterialView.from(resolvedMaterial.orElseThrow());

        Optional<PracticeSession> existingSession = practiceSessionRepository.findOwnedByTask(
                taskId, userContext.userId(), languageProfileId);
        if (existingSession.isPresent()) {
            return new StartResult.Existing(existingSession.orElseThrow(), materialView);
        }
        if (task.status() != LearningTask.Status.PLANNED) {
            // STARTED-without-Session 或 COMPLETED：零 mutation，fail closed。
            return new StartResult.TaskNotStartable();
        }

        Optional<LearningTask> startedTask = learningTaskRepository.tryStart(
                taskId, userContext.userId(), languageProfileId);
        if (startedTask.isEmpty()) {
            // concurrent start 的失败方：tryStart 的行锁已与获胜方串行化，重新 owner-scoped read；
            // 获胜方已 commit 时必然能读到同一 Session，不得创建第二条。
            Optional<PracticeSession> concurrentSession = practiceSessionRepository.findOwnedByTask(
                    taskId, userContext.userId(), languageProfileId);
            if (concurrentSession.isPresent()) {
                return new StartResult.Existing(concurrentSession.orElseThrow(), materialView);
            }
            return new StartResult.TaskNotStartable();
        }

        PracticeSession session = practiceSessionRepository.insertForOwnedTask(
                taskId, userContext.userId(), languageProfileId);
        return new StartResult.Created(session, materialView);
    }

    /**
     * owner-scoped 查找 Session → resolve exact material → 验证 stepId → 锁定 Session 行 →
     * 再确认 IN_PROGRESS → INSERT … ON CONFLICT DO NOTHING → 冲突后以 exact payload 比较既有行。
     */
    @Transactional
    public SubmitResult submit(
            UUID languageProfileId,
            UUID sessionId,
            String stepId,
            UserContext userContext,
            String learnerText) {
        Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(stepId, "stepId must not be null");
        Objects.requireNonNull(userContext, "userContext must not be null");

        if (!PracticeSession.LearnerResponse.isAcceptableLearnerText(learnerText)) {
            return new SubmitResult.InvalidResponse();
        }

        Optional<PracticeSession> ownedSession = practiceSessionRepository.findOwned(
                sessionId, userContext.userId(), languageProfileId);
        if (ownedSession.isEmpty()) {
            return new SubmitResult.SessionNotFound();
        }

        Optional<LearningTask> ownedTask = learningTaskRepository.findOwned(
                ownedSession.orElseThrow().taskId(), userContext.userId(), languageProfileId);
        if (ownedTask.isEmpty()) {
            // FK 与 owner-scoped read 使其在正常运行中不可达；fail closed，不区分内部状态。
            return new SubmitResult.MaterialUnavailable();
        }
        Optional<MaterialQueryResult.Available> resolvedMaterial =
                resolveExactMaterial(ownedTask.orElseThrow());
        if (resolvedMaterial.isEmpty()) {
            return new SubmitResult.MaterialUnavailable();
        }
        if (!definesStep(resolvedMaterial.orElseThrow().material(), stepId)) {
            return new SubmitResult.StepNotFound();
        }

        Optional<PracticeSession> lockedSession = practiceSessionRepository.findOwnedForUpdate(
                sessionId, userContext.userId(), languageProfileId);
        if (lockedSession.isEmpty()
                || lockedSession.orElseThrow().status() != PracticeSession.Status.IN_PROGRESS) {
            return new SubmitResult.SessionNotAcceptingResponses();
        }

        Optional<OffsetDateTime> submittedAt = practiceSessionRepository.insertOwnedAcceptedResponse(
                sessionId, stepId, learnerText, userContext.userId(), languageProfileId);
        if (submittedAt.isPresent()) {
            return new SubmitResult.Accepted(new PracticeSession.LearnerResponse(
                    sessionId, stepId, learnerText, submittedAt.orElseThrow()));
        }
        // conflict 后同事务内读取既有 response；行由 (sessionId, stepId) 主键保证存在。
        PracticeSession.LearnerResponse stored = practiceSessionRepository
                .findOwnedResponse(sessionId, stepId, userContext.userId(), languageProfileId)
                .orElseThrow(() -> new IllegalStateException(
                        "practice response row is missing after insert conflict"));
        if (stored.learnerText().equals(learnerText)) {
            return new SubmitResult.Replayed(stored);
        }
        return new SubmitResult.ResponseConflict();
    }

    /**
     * completion 的单一事务编排：owner-scoped SELECT … FOR UPDATE（与 response submission 相同的
     * Session-row-first 锁序）→ 校验 Session/Task 状态 → resolve exact material → 读取全部已接受
     * response → 验证 material step 集合与 response 集合一致 → Java 内计算 deterministic step
     * results → conditional IN_PROGRESS → COMPLETED → insert assessment + step results →
     * conditional STARTED → COMPLETED Task → durable reread → commit。任一 mutation 失败以异常
     * 结束整个事务，不产生 completed Session + STARTED Task 或缺 assessment 的中间状态。
     * completed Session 走 durable replay：只读取已持久化的 assessment，不重新依赖 catalog。
     */
    @Transactional
    public CompletionResult complete(UUID languageProfileId, UUID sessionId, UserContext userContext) {
        Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(userContext, "userContext must not be null");

        Optional<PracticeSession> lockedSession = practiceSessionRepository.findOwnedForUpdate(
                sessionId, userContext.userId(), languageProfileId);
        if (lockedSession.isEmpty()) {
            // unknown、wrong-owner 与 wrong-profile 对外不可区分。
            return new CompletionResult.SessionNotFound();
        }
        PracticeSession session = lockedSession.orElseThrow();

        if (session.status() == PracticeSession.Status.COMPLETED) {
            return replayCompleted(session, userContext.userId(), languageProfileId);
        }
        if (session.status() == PracticeSession.Status.ABANDONED) {
            return new CompletionResult.NotCompletable();
        }

        Optional<LearningTask> ownedTask = learningTaskRepository.findOwned(
                session.taskId(), userContext.userId(), languageProfileId);
        if (ownedTask.isEmpty() || ownedTask.orElseThrow().status() != LearningTask.Status.STARTED) {
            // FK 与 owner-scoped lock read 使其在正常运行中不可达；durable invariant violation，
            // 以异常回滚并交给容器输出 sanitized 5xx，不自动修复。
            throw new IllegalStateException(
                    "practice session completion requires a started learning task");
        }

        Optional<MaterialQueryResult.Available> resolvedMaterial =
                resolveExactMaterial(ownedTask.orElseThrow());
        if (resolvedMaterial.isEmpty()) {
            return new CompletionResult.MaterialUnavailable();
        }
        List<TextPracticeStep> steps = resolvedMaterial.orElseThrow().material().targetCore().steps();
        if (steps == null || steps.isEmpty()) {
            throw new IllegalStateException(
                    "practice session completion requires a material with at least one step");
        }

        Map<String, PracticeSession.LearnerResponse> responseByStepId =
                acceptedResponsesByStepId(sessionId, userContext.userId(), languageProfileId);
        Set<String> materialStepIds = materialStepIds(steps);
        for (String responseStepId : responseByStepId.keySet()) {
            if (!materialStepIds.contains(responseStepId)) {
                // material 未定义的 response step 属于 durable invariant violation，不是可恢复 client state。
                throw new IllegalStateException(
                        "practice response step " + responseStepId
                                + " is not defined by the resolved material");
            }
        }
        if (!responseByStepId.keySet().containsAll(materialStepIds)) {
            // 缺少任一 material step 的 response 是可恢复的 client state。
            return new CompletionResult.Incomplete();
        }

        List<DeterministicAssessment.StepResult> stepResults = calculateStepResults(steps, responseByStepId);

        OffsetDateTime completedAt = practiceSessionRepository
                .completeOwned(sessionId, userContext.userId(), languageProfileId)
                .orElseThrow(() -> new IllegalStateException(
                        "practice session completion transition did not match an in-progress session"));
        long durationSeconds = Duration.between(session.startedAt(), completedAt).toSeconds();

        practiceSessionRepository
                .insertOwnedAssessment(
                        sessionId,
                        DeterministicTextAssessmentPolicy.ASSESSMENT_POLICY_VERSION,
                        durationSeconds,
                        userContext.userId(),
                        languageProfileId)
                .orElseThrow(() -> new IllegalStateException(
                        "deterministic assessment insert requires a completed owned practice session"));
        for (DeterministicAssessment.StepResult stepResult : stepResults) {
            if (!practiceSessionRepository.insertOwnedStepAssessment(
                    sessionId,
                    stepResult.stepId(),
                    stepResult.stepKind().name(),
                    stepResult.outcome().name(),
                    userContext.userId(),
                    languageProfileId)) {
                throw new IllegalStateException(
                        "deterministic step assessment insert requires the owned completed assessment");
            }
        }

        learningTaskRepository
                .tryComplete(session.taskId(), userContext.userId(), languageProfileId)
                .orElseThrow(() -> new IllegalStateException(
                        "practice session completion requires transitioning the started learning task"));

        return new CompletionResult.Created(
                requireOwnedSessionRead(sessionId, userContext.userId(), languageProfileId),
                requireOwnedTaskRead(session.taskId(), userContext.userId(), languageProfileId),
                requireOwnedAssessmentRead(sessionId, userContext.userId(), languageProfileId));
    }

    /** completed Session 的 idempotent replay：只读 durable Session/Task/assessment，不触碰 catalog。 */
    private CompletionResult replayCompleted(
            PracticeSession completedSession, UUID trustedUserId, UUID languageProfileId) {
        LearningTask task = requireOwnedTaskRead(
                completedSession.taskId(), trustedUserId, languageProfileId);
        if (task.status() != LearningTask.Status.COMPLETED) {
            // completed Session 搭配非 COMPLETED Task 属于 durable invariant violation。
            throw new IllegalStateException(
                    "completed practice session requires a completed learning task");
        }
        return new CompletionResult.Replayed(
                completedSession,
                task,
                requireOwnedAssessmentRead(completedSession.id(), trustedUserId, languageProfileId));
    }

    private Map<String, PracticeSession.LearnerResponse> acceptedResponsesByStepId(
            UUID sessionId, UUID trustedUserId, UUID languageProfileId) {
        Map<String, PracticeSession.LearnerResponse> responseByStepId = new HashMap<>();
        for (PracticeSession.LearnerResponse response : practiceSessionRepository
                .findOwnedResponses(sessionId, trustedUserId, languageProfileId)) {
            // (session_id, step_id) 主键保证不会出现重复 stepId。
            responseByStepId.put(response.stepId(), response);
        }
        return responseByStepId;
    }

    private static Set<String> materialStepIds(List<TextPracticeStep> steps) {
        Set<String> stepIds = new HashSet<>();
        for (TextPracticeStep step : steps) {
            if (!stepIds.add(step.stepId())) {
                // catalog 契约要求 material 内 stepId 唯一；重复属于 fail-closed 的内容异常。
                throw new IllegalStateException(
                        "practice material defines duplicate stepId " + step.stepId());
            }
        }
        return stepIds;
    }

    private static List<DeterministicAssessment.StepResult> calculateStepResults(
            List<TextPracticeStep> steps, Map<String, PracticeSession.LearnerResponse> responseByStepId) {
        List<DeterministicAssessment.StepResult> stepResults = new ArrayList<>(steps.size());
        for (TextPracticeStep step : steps) {
            DeterministicAssessment.StepKind stepKind = assessmentStepKind(step.kind());
            DeterministicAssessment.StepOutcome outcome = DeterministicTextAssessmentPolicy.outcomeFor(
                    stepKind,
                    responseByStepId.get(step.stepId()).learnerText(),
                    step.acceptedAnswers());
            stepResults.add(new DeterministicAssessment.StepResult(step.stepId(), stepKind, outcome));
        }
        return stepResults;
    }

    private static DeterministicAssessment.StepKind assessmentStepKind(TextStepKind stepKind) {
        return switch (stepKind) {
            case EXACT -> DeterministicAssessment.StepKind.EXACT;
            case SEMANTIC_ONLY -> DeterministicAssessment.StepKind.SEMANTIC_ONLY;
        };
    }

    private PracticeSession requireOwnedSessionRead(
            UUID sessionId, UUID trustedUserId, UUID languageProfileId) {
        return practiceSessionRepository
                .findOwned(sessionId, trustedUserId, languageProfileId)
                .orElseThrow(() -> new IllegalStateException(
                        "owned practice session row is missing after completion"));
    }

    private LearningTask requireOwnedTaskRead(UUID taskId, UUID trustedUserId, UUID languageProfileId) {
        return learningTaskRepository
                .findOwned(taskId, trustedUserId, languageProfileId)
                .orElseThrow(() -> new IllegalStateException(
                        "owned learning task row is missing after completion"));
    }

    private DeterministicAssessment requireOwnedAssessmentRead(
            UUID sessionId, UUID trustedUserId, UUID languageProfileId) {
        return practiceSessionRepository
                .findOwnedAssessment(sessionId, trustedUserId, languageProfileId)
                .orElseThrow(() -> new IllegalStateException(
                        "completed practice session is missing its deterministic assessment"));
    }

    /**
     * 只按 Task 锁定的 materialId + publishedVersion + supportLanguage 解析，并验证 resolved
     * material 与 Task snapshot 完全一致；任何缺失或不一致都 fail closed，不 fallback 到其他
     * version 或语言。
     */
    private Optional<MaterialQueryResult.Available> resolveExactMaterial(LearningTask task) {
        MaterialQueryResult queryResult =
                materialCatalog.findByIdentity(task.materialIdentity(), task.supportLanguage());
        if (!(queryResult instanceof MaterialQueryResult.Available available)) {
            return Optional.empty();
        }
        PublishedLearningMaterial material = available.material();
        SupportScaffold scaffold = available.selectedScaffold();
        if (material == null || material.identity() == null || material.targetCore() == null
                || scaffold == null) {
            return Optional.empty();
        }
        TargetPracticeCore targetCore = material.targetCore();
        boolean consistentWithTaskSnapshot = material.identity().equals(task.materialIdentity())
                && task.targetLanguage().equals(targetCore.targetLanguage())
                && task.supportLanguage().equals(scaffold.supportLanguage())
                && task.difficulty() == targetCore.difficulty()
                && task.scenario().equals(targetCore.scenario())
                && task.primaryGoal().equals(targetCore.communicationObjective());
        return consistentWithTaskSnapshot ? Optional.of(available) : Optional.empty();
    }

    private static boolean definesStep(PublishedLearningMaterial material, String stepId) {
        List<TextPracticeStep> steps = material.targetCore().steps();
        return steps != null && steps.stream().anyMatch(step -> stepId.equals(step.stepId()));
    }

    /**
     * start 的互斥 Application 结果。Created / Existing 都只携带数据库裁决后的 durable
     * {@link PracticeSession} 与安全 material projection；其余变体均零 mutation。
     */
    public sealed interface StartResult {

        record Created(PracticeSession session, PracticeMaterialView material)
                implements StartResult {

            public Created {
                Objects.requireNonNull(session, "session must not be null");
                Objects.requireNonNull(material, "material must not be null");
            }
        }

        /** 相同 Task 已存在 Session 的重放；返回同一 Session，不创建第二条。 */
        record Existing(PracticeSession session, PracticeMaterialView material)
                implements StartResult {

            public Existing {
                Objects.requireNonNull(session, "session must not be null");
                Objects.requireNonNull(material, "material must not be null");
            }
        }

        /** Task 不存在或不属于 caller；两者不可区分，避免资源枚举。 */
        record TaskNotFound() implements StartResult {
        }

        /** Task 不是 PLANNED（STARTED-without-Session 或 COMPLETED），不能开始新 Session。 */
        record TaskNotStartable() implements StartResult {
        }

        /** Task 锁定的 exact material 缺失或与 Task snapshot 不一致。 */
        record MaterialUnavailable() implements StartResult {
        }
    }

    /** response 提交的互斥 Application 结果；除 Accepted 外均为零 mutation。 */
    public sealed interface SubmitResult {

        record Accepted(PracticeSession.LearnerResponse response) implements SubmitResult {

            public Accepted {
                Objects.requireNonNull(response, "response must not be null");
            }
        }

        /** 相同 (sessionId, stepId, learnerText) 的重放；返回首次持久化产生的 submittedAt。 */
        record Replayed(PracticeSession.LearnerResponse response) implements SubmitResult {

            public Replayed {
                Objects.requireNonNull(response, "response must not be null");
            }
        }

        /** learnerText 为 null、blank 或超过 2,000 Unicode code points。 */
        record InvalidResponse() implements SubmitResult {
        }

        /** Session 不存在或不属于 caller；两者不可区分，避免资源枚举。 */
        record SessionNotFound() implements SubmitResult {
        }

        /** stepId 不存在于 Task 锁定的 resolved material。 */
        record StepNotFound() implements SubmitResult {
        }

        /** Session 不是 IN_PROGRESS（含并发下被锁行后确认的 terminal 状态）。 */
        record SessionNotAcceptingResponses() implements SubmitResult {
        }

        /** 同一 (sessionId, stepId) 已接受不同文本；首次 response 不被覆盖。 */
        record ResponseConflict() implements SubmitResult {
        }

        /** Task 锁定的 exact material 缺失或与 Task snapshot 不一致。 */
        record MaterialUnavailable() implements SubmitResult {
        }
    }

    /** completion 的互斥 Application 结果；Created / Replayed 携带同一事务内 durable reread 后的数据。 */
    public sealed interface CompletionResult {

        record Created(
                PracticeSession session,
                LearningTask task,
                DeterministicAssessment assessment) implements CompletionResult {

            public Created {
                Objects.requireNonNull(session, "session must not be null");
                Objects.requireNonNull(task, "task must not be null");
                Objects.requireNonNull(assessment, "assessment must not be null");
            }
        }

        /** completed Session 的重复 / 并发 completion：返回同一 durable assessment，零 mutation。 */
        record Replayed(
                PracticeSession session,
                LearningTask task,
                DeterministicAssessment assessment) implements CompletionResult {

            public Replayed {
                Objects.requireNonNull(session, "session must not be null");
                Objects.requireNonNull(task, "task must not be null");
                Objects.requireNonNull(assessment, "assessment must not be null");
            }
        }

        /** Session 不存在或不属于 caller；两者不可区分，避免资源枚举。 */
        record SessionNotFound() implements CompletionResult {
        }

        /** Session 为 ABANDONED，不能进入 COMPLETED。 */
        record NotCompletable() implements CompletionResult {
        }

        /** material 尚有 step 缺少已接受 response；可恢复的 client state，零 mutation。 */
        record Incomplete() implements CompletionResult {
        }

        /** Task 锁定的 exact material 缺失或与 Task snapshot 不一致。 */
        record MaterialUnavailable() implements CompletionResult {
        }
    }

    /**
     * 启动 Session 时下发的安全 material projection：只含 learner 练习所需字段，
     * 不含 acceptedAnswers、semanticRubricReference、Content lineage 或任何 ownership identity。
     */
    public record PracticeMaterialView(
            String materialId,
            String publishedVersion,
            String targetLanguage,
            String supportLanguage,
            String scenario,
            String communicationObjective,
            String targetLanguageText,
            TextReadingInfo readingInfo,
            String instruction,
            String explanation,
            String hint,
            String contrastiveNote,
            List<StepView> steps) {

        public PracticeMaterialView {
            steps = steps == null ? null : List.copyOf(steps);
        }

        static PracticeMaterialView from(MaterialQueryResult.Available available) {
            PublishedLearningMaterial material = available.material();
            SupportScaffold scaffold = available.selectedScaffold();
            TargetPracticeCore targetCore = material.targetCore();
            return new PracticeMaterialView(
                    material.identity().materialId(),
                    material.identity().publishedVersion(),
                    targetCore.targetLanguage(),
                    scaffold.supportLanguage(),
                    targetCore.scenario(),
                    targetCore.communicationObjective(),
                    targetCore.targetLanguageText(),
                    targetCore.readingInfo(),
                    scaffold.instruction(),
                    scaffold.explanation(),
                    scaffold.hint(),
                    scaffold.contrastiveNote(),
                    targetCore.steps() == null
                            ? List.of()
                            : targetCore.steps().stream()
                                    .map(step -> new StepView(
                                            step.stepId(), step.kind().name(), step.prompt()))
                                    .toList());
        }

        public record StepView(String stepId, String kind, String prompt) {
        }
    }
}
