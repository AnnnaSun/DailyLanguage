package com.dailylanguage.practice.infrastructure;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.practice.domain.PracticeSession;

@Repository
public class PracticeSessionRepository {

    private final PracticeSessionMapper practiceSessionMapper;

    public PracticeSessionRepository(PracticeSessionMapper practiceSessionMapper) {
        this.practiceSessionMapper = practiceSessionMapper;
    }

    public Optional<PracticeSession> findOwnedByTask(
            UUID taskId, UUID trustedUserId, UUID languageProfileId) {
        validateOwnedArguments(taskId, trustedUserId, languageProfileId);
        return practiceSessionMapper
                .findOwnedByTaskId(taskId, trustedUserId, languageProfileId)
                .map(PracticeSessionRepository::toDomain);
    }

    public Optional<PracticeSession> findOwned(
            UUID sessionId, UUID trustedUserId, UUID languageProfileId) {
        validateOwnedArguments(sessionId, trustedUserId, languageProfileId);
        return practiceSessionMapper
                .findOwnedById(sessionId, trustedUserId, languageProfileId)
                .map(PracticeSessionRepository::toDomain);
    }

    /**
     * 在当前事务内锁定 Session 行后返回快照。response 写入与后续 completion 必须以此作为
     * 串行化点，防止“Session 已 terminal 但 response 随后写入”的 race。
     */
    public Optional<PracticeSession> findOwnedForUpdate(
            UUID sessionId, UUID trustedUserId, UUID languageProfileId) {
        validateOwnedArguments(sessionId, trustedUserId, languageProfileId);
        return practiceSessionMapper
                .findOwnedByIdForUpdate(sessionId, trustedUserId, languageProfileId)
                .map(PracticeSessionRepository::toDomain);
    }

    /**
     * 前置条件：调用方已在同一事务内赢得 Task 的 PLANNED → STARTED transition 并持有 task row lock。
     * insert gate 在数据库内重校验 owner/profile/status：Task 不是该 caller 在该 profile 下的
     * STARTED Task 时产生零行并以异常 fail closed。insert 失败（含 UNIQUE(task_id) 第二层 guard）
     * 以异常表达，由外层 Application 事务整体回滚，Task 恢复 PLANNED；不捕获异常伪装成业务结果。
     */
    @Transactional
    public PracticeSession insertForOwnedTask(
            UUID taskId, UUID trustedUserId, UUID languageProfileId) {
        validateOwnedArguments(taskId, trustedUserId, languageProfileId);
        UUID sessionId =
                practiceSessionMapper.insertOwnedAndReturnId(taskId, trustedUserId, languageProfileId);
        if (sessionId == null) {
            throw new IllegalStateException(
                    "practice session insert requires an owned started learning task");
        }
        return requireOwnedRead(sessionId, trustedUserId, languageProfileId);
    }

    /**
     * owner-scoped 首次接受：数据库内重校验 Session 归属同一 owner/profile 的 Task 且仍为
     * IN_PROGRESS。返回数据库裁决的 submittedAt；empty 表示未插入——在调用方前置条件（owner-scoped
     * Session 行锁 + IN_PROGRESS 确认）下即 (sessionId, stepId) 已有既有 response；gate 不匹配时
     * 同样为空，属 fail-closed，由调用方读取既有行并以 exact payload 比较裁决 replay 或 conflict。
     */
    @Transactional
    public Optional<OffsetDateTime> insertOwnedAcceptedResponse(
            UUID sessionId,
            String stepId,
            String learnerText,
            UUID trustedUserId,
            UUID languageProfileId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(stepId, "stepId must not be null");
        Objects.requireNonNull(learnerText, "learnerText must not be null");
        Objects.requireNonNull(trustedUserId, "trustedUserId must not be null");
        Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
        return Optional.ofNullable(practiceSessionMapper.insertOwnedResponseAndReturnSubmittedAt(
                sessionId, stepId, learnerText, trustedUserId, languageProfileId));
    }

    /** private learner text 的 owner-scoped 读取；ownership 通过 response → session → task 链路重校验。 */
    public Optional<PracticeSession.LearnerResponse> findOwnedResponse(
            UUID sessionId, String stepId, UUID trustedUserId, UUID languageProfileId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(stepId, "stepId must not be null");
        Objects.requireNonNull(trustedUserId, "trustedUserId must not be null");
        Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
        return practiceSessionMapper
                .findOwnedResponse(sessionId, stepId, trustedUserId, languageProfileId)
                .map(PracticeSessionRepository::toDomain);
    }

    private PracticeSession requireOwnedRead(
            UUID sessionId, UUID trustedUserId, UUID languageProfileId) {
        return practiceSessionMapper
                .findOwnedById(sessionId, trustedUserId, languageProfileId)
                .map(PracticeSessionRepository::toDomain)
                .orElseThrow(() -> new IllegalStateException(
                        "owned practice session row is missing after mutation"));
    }

    private static PracticeSession toDomain(StoredPracticeSession session) {
        return new PracticeSession(
                session.id(),
                session.taskId(),
                PracticeSession.Status.valueOf(session.status()),
                session.startedAt(),
                Optional.ofNullable(session.completedAt()),
                Optional.ofNullable(session.abandonedAt()));
    }

    private static PracticeSession.LearnerResponse toDomain(StoredLearnerResponse response) {
        return new PracticeSession.LearnerResponse(
                response.sessionId(),
                response.stepId(),
                response.learnerText(),
                response.submittedAt());
    }

    private static void validateOwnedArguments(
            UUID sessionIdOrTaskId, UUID trustedUserId, UUID languageProfileId) {
        Objects.requireNonNull(sessionIdOrTaskId, "sessionId must not be null");
        Objects.requireNonNull(trustedUserId, "trustedUserId must not be null");
        Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
    }
}

record StoredPracticeSession(
        UUID id,
        UUID taskId,
        String status,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime abandonedAt) {
}

record StoredLearnerResponse(
        UUID sessionId,
        String stepId,
        String learnerText,
        OffsetDateTime submittedAt) {
}
