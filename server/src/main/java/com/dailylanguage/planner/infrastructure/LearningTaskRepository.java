package com.dailylanguage.planner.infrastructure;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.domain.LearningTaskPlan;

@Repository
public class LearningTaskRepository {

    private final LearningTaskMapper learningTaskMapper;

    public LearningTaskRepository(LearningTaskMapper learningTaskMapper) {
        this.learningTaskMapper = learningTaskMapper;
    }

    /**
     * trustedUserId 只能来自 authenticated UserContext；LearningTaskPlan 本身不是 authorization proof。
     * owner、profile 与 target language 的一致性由 INSERT … SELECT 在数据库内原子裁决，
     * 三者任一不匹配时不产生任何 row，并用 empty 表达，不区分“不存在”与“不属于该 caller”。
     */
    @Transactional
    public Optional<LearningTask> createOwned(UUID trustedUserId, LearningTaskPlan plan) {
        Objects.requireNonNull(trustedUserId, "trustedUserId must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        NewLearningTaskRow insert = new NewLearningTaskRow(
                trustedUserId,
                plan.languageProfileId(),
                plan.targetLanguage(),
                plan.materialIdentity().materialId(),
                plan.materialIdentity().publishedVersion(),
                plan.supportLanguage(),
                plan.difficulty().name(),
                plan.estimatedDurationMinutes(),
                plan.scenario(),
                plan.primaryGoal(),
                plan.taskType().name(),
                plan.reason().name());

        UUID taskId = learningTaskMapper.insertOwnedAndReturnId(insert);
        if (taskId == null) {
            return Optional.empty();
        }
        // transition 与 read 在同一事务内，返回快照必然反映本次 mutation 后、commit 前的行状态。
        return Optional.of(requireOwnedRead(taskId, trustedUserId, plan.languageProfileId()));
    }

    public Optional<LearningTask> findOwned(UUID taskId, UUID trustedUserId, UUID languageProfileId) {
        validateOwnedArguments(taskId, trustedUserId, languageProfileId);
        return learningTaskMapper.findOwned(taskId, trustedUserId, languageProfileId)
                .map(LearningTaskRepository::toDomain);
    }

    /**
     * PLANNED → STARTED 的 conditional transition；guard 由 WHERE status = 'PLANNED' 原子裁决，
     * 重复、逆向、跳级、wrong-owner 或 wrong-profile 请求返回 empty 且不改变原状态。
     */
    @Transactional
    public Optional<LearningTask> tryStart(UUID taskId, UUID trustedUserId, UUID languageProfileId) {
        validateOwnedArguments(taskId, trustedUserId, languageProfileId);
        UUID startedTaskId = learningTaskMapper.tryStartAndReturnId(taskId, trustedUserId, languageProfileId);
        return readAfterTransition(startedTaskId, trustedUserId, languageProfileId);
    }

    /** STARTED → COMPLETED 的 conditional transition；语义与 {@link #tryStart} 相同。 */
    @Transactional
    public Optional<LearningTask> tryComplete(UUID taskId, UUID trustedUserId, UUID languageProfileId) {
        validateOwnedArguments(taskId, trustedUserId, languageProfileId);
        UUID completedTaskId =
                learningTaskMapper.tryCompleteAndReturnId(taskId, trustedUserId, languageProfileId);
        return readAfterTransition(completedTaskId, trustedUserId, languageProfileId);
    }

    private Optional<LearningTask> readAfterTransition(
            UUID transitionedTaskId, UUID trustedUserId, UUID languageProfileId) {
        if (transitionedTaskId == null) {
            return Optional.empty();
        }
        return Optional.of(requireOwnedRead(transitionedTaskId, trustedUserId, languageProfileId));
    }

    private LearningTask requireOwnedRead(UUID taskId, UUID trustedUserId, UUID languageProfileId) {
        return learningTaskMapper.findOwned(taskId, trustedUserId, languageProfileId)
                .map(LearningTaskRepository::toDomain)
                .orElseThrow(() -> new IllegalStateException(
                        "owned learning task row is missing after mutation"));
    }

    private static LearningTask toDomain(StoredLearningTask task) {
        return new LearningTask(
                task.id(),
                task.userId(),
                task.languageProfileId(),
                new MaterialIdentity(task.materialId(), task.publishedVersion()),
                task.targetLanguage(),
                task.supportLanguage(),
                MaterialDifficulty.valueOf(task.difficulty()),
                task.estimatedDurationMinutes(),
                task.scenario(),
                task.primaryGoal(),
                LearningTaskPlan.TaskType.valueOf(task.taskType()),
                LearningTaskPlan.PlanningReason.valueOf(task.planningReason()),
                LearningTask.Status.valueOf(task.status()),
                task.createdAt(),
                Optional.ofNullable(task.startedAt()),
                Optional.ofNullable(task.completedAt()));
    }

    private static void validateOwnedArguments(UUID taskId, UUID trustedUserId, UUID languageProfileId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(trustedUserId, "trustedUserId must not be null");
        Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
    }
}

record NewLearningTaskRow(
        UUID trustedUserId,
        UUID languageProfileId,
        String targetLanguage,
        String materialId,
        String publishedVersion,
        String supportLanguage,
        String difficulty,
        int estimatedDurationMinutes,
        String scenario,
        String primaryGoal,
        String taskType,
        String planningReason) {
}

record StoredLearningTask(
        UUID id,
        UUID userId,
        UUID languageProfileId,
        String materialId,
        String publishedVersion,
        String targetLanguage,
        String supportLanguage,
        String difficulty,
        int estimatedDurationMinutes,
        String scenario,
        String primaryGoal,
        String taskType,
        String planningReason,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt) {
}
