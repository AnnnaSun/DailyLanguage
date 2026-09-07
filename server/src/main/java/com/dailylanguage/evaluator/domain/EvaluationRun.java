package com.dailylanguage.evaluator.domain;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 已持久化 EvaluationRun 的 durable 快照。PostgreSQL 是 id、status 与 lifecycle timestamp 的
 * authority；本类型只还原数据库已裁决的行，不提供任何 transition 操作。ownership 不在此重复
 * 存储，经 session → learning_task 链路还原。S8B 生命周期封闭为 PENDING；完成状态由 S8C 引入。
 */
public record EvaluationRun(
        UUID id,
        UUID sessionId,
        UUID modelCallJobId,
        Status status,
        long workflowVersion,
        long rowVersion,
        OffsetDateTime createdAt,
        Optional<OffsetDateTime> completedAt) {

    /** S8 Evaluation workflow 第一版；绑定 Job 必须持有同一 version。 */
    public static final long CURRENT_WORKFLOW_VERSION = 0L;

    /** 创建该 Run 的 workflow step identity；Repository 的 insert gate 与绑定 Job 必须一致持有。 */
    public static final String WORKFLOW_STEP_ID = "SEMANTIC_EVALUATION";

    public EvaluationRun {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(modelCallJobId, "modelCallJobId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (workflowVersion < 0) {
            throw new IllegalArgumentException("workflowVersion must not be negative");
        }
        if (rowVersion < 0) {
            throw new IllegalArgumentException("rowVersion must not be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        if (status != Status.PENDING || completedAt.isPresent()) {
            throw new IllegalArgumentException(
                    "S8B only persists PENDING evaluation runs without completedAt");
        }
    }

    public enum Status {
        PENDING
    }
}
