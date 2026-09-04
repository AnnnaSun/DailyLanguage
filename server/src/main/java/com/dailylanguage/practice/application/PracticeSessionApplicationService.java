package com.dailylanguage.practice.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.infrastructure.LearningTaskRepository;
import com.dailylanguage.practice.domain.PracticeSession;
import com.dailylanguage.practice.infrastructure.PracticeSessionRepository;
import com.dailylanguage.security.domain.UserContext;

/**
 * PracticeSession lifecycle 的 Application 编排。start 把 Task transition 与 Session insert
 * 放在同一个 Spring 事务内：Session insert/read 的 unexpected failure 以异常结束事务，
 * Task 随之回滚到 PLANNED，不会留下孤立的 STARTED Task。response 提交先锁定 Session 行再
 * 写入，(sessionId, stepId) 的首次接受由数据库 identity 裁决，exact payload 重放返回首次
 * submittedAt，不同 payload 冲突不覆盖。userId 只信任 {@link UserContext}。
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
