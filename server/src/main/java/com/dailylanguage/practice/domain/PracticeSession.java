package com.dailylanguage.practice.domain;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 一次 Practice 的 durable Session 快照。PostgreSQL 是 id、status 与 lifecycle timestamp 的
 * authority；本类型只还原数据库已裁决的行，不提供任何 transition 操作。Session 不重复保存
 * owner、profile 或 material identity，这些事实通过 task 的 owner-scoped join 还原。
 */
public record PracticeSession(
        UUID id,
        UUID taskId,
        Status status,
        OffsetDateTime startedAt,
        Optional<OffsetDateTime> completedAt,
        Optional<OffsetDateTime> abandonedAt) {

    public PracticeSession {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        Objects.requireNonNull(abandonedAt, "abandonedAt must not be null");
        validateLifecycle(status, startedAt, completedAt, abandonedAt);
    }

    private static void validateLifecycle(
            Status status,
            OffsetDateTime startedAt,
            Optional<OffsetDateTime> completedAt,
            Optional<OffsetDateTime> abandonedAt) {
        switch (status) {
            case IN_PROGRESS -> {
                requireAbsent(completedAt, "completedAt", status);
                requireAbsent(abandonedAt, "abandonedAt", status);
            }
            case COMPLETED -> {
                requirePresent(completedAt, "completedAt", status);
                requireAbsent(abandonedAt, "abandonedAt", status);
                requireNotBefore(completedAt.orElseThrow(), startedAt, "completedAt", "startedAt");
            }
            case ABANDONED -> {
                requireAbsent(completedAt, "completedAt", status);
                requirePresent(abandonedAt, "abandonedAt", status);
                requireNotBefore(abandonedAt.orElseThrow(), startedAt, "abandonedAt", "startedAt");
            }
        }
    }

    private static void requirePresent(Optional<OffsetDateTime> value, String fieldName, Status status) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must be present while status is " + status);
        }
    }

    private static void requireAbsent(Optional<OffsetDateTime> value, String fieldName, Status status) {
        if (value.isPresent()) {
            throw new IllegalArgumentException(fieldName + " must be empty while status is " + status);
        }
    }

    private static void requireNotBefore(
            OffsetDateTime value, OffsetDateTime floor, String fieldName, String floorName) {
        if (value.isBefore(floor)) {
            throw new IllegalArgumentException(fieldName + " must not be before " + floorName);
        }
    }

    public enum Status {
        IN_PROGRESS, COMPLETED, ABANDONED
    }

    /**
     * Learner 对一个 material step 的已接受 response；identity 是 (sessionId, stepId)，同一 step
     * 只保存首次接受的 exact text。不保存 correctness、semantic result 或任何 Model 输出，
     * 这些属于后续 assessment。learnerText 保存原始文本：不 trim、不改大小写、不做 Unicode
     * normalization，保证后续 Grounded Evaluator 能引用 learner 实际提交的内容。
     */
    public record LearnerResponse(
            UUID sessionId,
            String stepId,
            String learnerText,
            OffsetDateTime submittedAt) {

        /** durable contract 上限，与 practice_response 的 char_length CHECK 一致。 */
        public static final int MAXIMUM_STEP_ID_LENGTH = 128;
        public static final int MAXIMUM_LEARNER_TEXT_CODE_POINTS = 2000;

        public LearnerResponse {
            Objects.requireNonNull(sessionId, "sessionId must not be null");
            requireIdentifier(stepId, "stepId");
            if (learnerText == null || learnerText.isBlank()
                    || learnerText.codePointCount(0, learnerText.length()) > MAXIMUM_LEARNER_TEXT_CODE_POINTS) {
                throw new IllegalArgumentException(
                        "learnerText must not be null, blank, or longer than "
                                + MAXIMUM_LEARNER_TEXT_CODE_POINTS + " Unicode code points");
            }
            Objects.requireNonNull(submittedAt, "submittedAt must not be null");
        }

        /** Application 边界的入口校验；与构造器约束一致，但不以异常表达业务失败。 */
        public static boolean isAcceptableLearnerText(String learnerText) {
            return learnerText != null
                    && !learnerText.isBlank()
                    && learnerText.codePointCount(0, learnerText.length()) <= MAXIMUM_LEARNER_TEXT_CODE_POINTS;
        }

        private static void requireIdentifier(String value, String fieldName) {
            if (value == null || value.isBlank()
                    || !value.equals(value.strip())
                    || value.length() > MAXIMUM_STEP_ID_LENGTH) {
                throw new IllegalArgumentException(
                        fieldName + " must not be blank, contain surrounding whitespace, "
                                + "or exceed " + MAXIMUM_STEP_ID_LENGTH + " characters");
            }
        }
    }
}
