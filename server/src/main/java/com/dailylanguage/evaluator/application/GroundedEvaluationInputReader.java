package com.dailylanguage.evaluator.application;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.content.domain.LearningMaterialCatalog;
import com.dailylanguage.content.domain.MaterialQueryResult;
import com.dailylanguage.content.domain.PublishedLearningMaterial;
import com.dailylanguage.content.domain.TextPracticeStep;
import com.dailylanguage.evaluator.domain.GroundedEvaluationInput;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.infrastructure.LearningTaskRepository;
import com.dailylanguage.practice.domain.DeterministicAssessment;
import com.dailylanguage.practice.domain.PracticeSession;
import com.dailylanguage.practice.infrastructure.PracticeSessionRepository;
import com.dailylanguage.security.domain.UserContext;

/**
 * Evaluator 的唯一 trusted input 读取入口：把 S7 grounding 的“trusted input 只能来自
 * owner-scoped 读取”组装前提落实为生产边界，供后续 ModelCallJob submission 与 consumption
 * 共用。读取顺序固定：owned Session → completion gate → owned Task → owned assessment 与
 * responses → exact identity material 解析 → snapshot 一致性检查。ownership 失败（NotFound）
 * 在读取任何 private learner text 之前裁决；material 只按 Task 保存的完整 identity 解析
 * （HISTORICAL_ONLY 版本同样可读），绝不通过 listAvailable 重选或读取最新版本。全程零写入、
 * 零 Model 调用；基础设施异常原样上抛，不吞成业务 failure。
 */
@Service
public class GroundedEvaluationInputReader {

    private final PracticeSessionRepository practiceSessionRepository;
    private final LearningTaskRepository learningTaskRepository;
    private final LearningMaterialCatalog materialCatalog;

    public GroundedEvaluationInputReader(
            PracticeSessionRepository practiceSessionRepository,
            LearningTaskRepository learningTaskRepository,
            LearningMaterialCatalog materialCatalog) {
        this.practiceSessionRepository =
                Objects.requireNonNull(practiceSessionRepository, "practiceSessionRepository must not be null");
        this.learningTaskRepository =
                Objects.requireNonNull(learningTaskRepository, "learningTaskRepository must not be null");
        this.materialCatalog =
                Objects.requireNonNull(materialCatalog, "materialCatalog must not be null");
    }

    /**
     * 短 readOnly transaction：completed Session 已禁止 response 修改，因此复用现有 owner-scoped
     * 读取即可保证读取一致性；readOnly 本身不提供 snapshot isolation，也不加 FOR UPDATE 写锁。
     * 必须通过 Spring bean 调用，self-invocation 不会开启事务。
     */
    @Transactional(readOnly = true)
    public GroundedEvaluationInputResult readOwned(
            UUID languageProfileId, UUID sessionId, UserContext userContext) {
        Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(userContext, "userContext must not be null");
        UUID userId = userContext.userId();

        Optional<PracticeSession> ownedSession =
                practiceSessionRepository.findOwned(sessionId, userId, languageProfileId);
        if (ownedSession.isEmpty()) {
            return new GroundedEvaluationInputResult.NotFound();
        }
        PracticeSession session = ownedSession.orElseThrow();
        if (session.status() != PracticeSession.Status.COMPLETED) {
            return new GroundedEvaluationInputResult.NotCompleted();
        }

        Optional<LearningTask> ownedTask =
                learningTaskRepository.findOwned(session.taskId(), userId, languageProfileId);
        if (ownedTask.isEmpty()) {
            // owned completed Session 的 Task 行必须同样 owner-scoped 可读，否则是 durable 结构异常。
            return new GroundedEvaluationInputResult.InconsistentSnapshot();
        }
        LearningTask task = ownedTask.orElseThrow();

        Optional<DeterministicAssessment> ownedAssessment =
                practiceSessionRepository.findOwnedAssessment(sessionId, userId, languageProfileId);
        if (ownedAssessment.isEmpty()) {
            // assessment 与 completion 处于同一事务，completed Session 缺 assessment 是结构异常，
            // 不能误判为尚未完成。
            return new GroundedEvaluationInputResult.InconsistentSnapshot();
        }
        DeterministicAssessment assessment = ownedAssessment.orElseThrow();

        List<PracticeSession.LearnerResponse> responses =
                practiceSessionRepository.findOwnedResponses(sessionId, userId, languageProfileId);

        MaterialQueryResult materialQueryResult =
                materialCatalog.findByIdentity(task.materialIdentity(), task.supportLanguage());
        if (!(materialQueryResult instanceof MaterialQueryResult.Available available)) {
            return new GroundedEvaluationInputResult.MaterialUnavailable();
        }
        PublishedLearningMaterial material = available.material();

        if (!isConsistentSnapshot(userId, languageProfileId, task, session, assessment, responses, material)) {
            return new GroundedEvaluationInputResult.InconsistentSnapshot();
        }
        return new GroundedEvaluationInputResult.Ready(new GroundedEvaluationInput(
                userId, languageProfileId, task, session, assessment, responses, material));
    }

    /**
     * 模型调用前的读取资格检查（与 S7 validator 的独立检查互不替代）：caller/task/profile
     * 一致、Task 与 Session 均 COMPLETED、assessment 属于当前 Session、material identity 与
     * target language 与 Task 一致、material steps 非空且不重复、responses 属于当前 Session 且
     * step 集合与 material steps 完整相等。learner text 只原样透传，不 strip、不 normalize。
     */
    private static boolean isConsistentSnapshot(
            UUID userId,
            UUID languageProfileId,
            LearningTask task,
            PracticeSession session,
            DeterministicAssessment assessment,
            List<PracticeSession.LearnerResponse> responses,
            PublishedLearningMaterial material) {
        if (!userId.equals(task.userId()) || !languageProfileId.equals(task.languageProfileId())) {
            return false;
        }
        if (!task.id().equals(session.taskId())
                || task.status() != LearningTask.Status.COMPLETED
                || !session.id().equals(assessment.sessionId())) {
            return false;
        }
        List<TextPracticeStep> steps = material.targetCore().steps();
        if (!task.materialIdentity().equals(material.identity())
                || !task.targetLanguage().equals(material.targetCore().targetLanguage())
                || steps == null || steps.isEmpty()) {
            return false;
        }
        Set<String> materialStepIds = new HashSet<>();
        for (TextPracticeStep step : steps) {
            if (!materialStepIds.add(step.stepId())) {
                return false;
            }
        }
        Set<String> responseStepIds = new HashSet<>();
        for (PracticeSession.LearnerResponse response : responses) {
            if (!session.id().equals(response.sessionId())
                    || !materialStepIds.contains(response.stepId())
                    || !responseStepIds.add(response.stepId())) {
                return false;
            }
        }
        return responseStepIds.equals(materialStepIds);
    }
}
