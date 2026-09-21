-- S9E2A：PlanningRun 的 durable terminal lifecycle 与 task linkage。PENDING → MODEL_APPLIED
-- （绑定唯一 MODEL_ENRICHED task）或 FALLBACK_APPLIED（绑定唯一 deterministic task + closed
-- fallback reason + workflowVersion 原子 +1）。不修改 V16 文件本体；既有 PENDING 行的
-- 新列保持 NULL，升级路径（empty schema 与 V16→V17）下新约束均能通过既有行验证。

-- Composite FK 的被引用列组必须声明 UNIQUE：把 task identity 与 owner/profile 绑定为同一行事实。
ALTER TABLE learning_task
    ADD CONSTRAINT uq_learning_task_id_user_profile
        UNIQUE (id, user_id, language_profile_id);

ALTER TABLE planning_run
    ADD COLUMN learning_task_id UUID,
    ADD COLUMN fallback_reason VARCHAR(64),
    ADD CONSTRAINT fk_planning_run_learning_task_owner
        FOREIGN KEY (learning_task_id, user_id, language_profile_id)
        REFERENCES learning_task (id, user_id, language_profile_id) ON DELETE RESTRICT,
    -- 一个 task 最多属于一个 Run；terminal Run 绑定唯一 task。
    ADD CONSTRAINT uq_planning_run_learning_task UNIQUE (learning_task_id),
    DROP CONSTRAINT ck_planning_run_status,
    DROP CONSTRAINT ck_planning_run_completed_at;

-- status / outcome / reason 的封闭枚举与 pairing：terminal 状态必须绑定 task 与 completedAt；
-- MODEL_APPLIED 不携带 reason；FALLBACK_APPLIED 携带 closed reason 且 workflowVersion 已被推进。
ALTER TABLE planning_run
    ADD CONSTRAINT ck_planning_run_status
        CHECK (status IN ('PENDING', 'MODEL_APPLIED', 'FALLBACK_APPLIED')),
    ADD CONSTRAINT ck_planning_run_outcome
        CHECK (
            (status = 'PENDING'
                AND learning_task_id IS NULL
                AND fallback_reason IS NULL
                AND completed_at IS NULL)
            OR (status = 'MODEL_APPLIED'
                AND learning_task_id IS NOT NULL
                AND fallback_reason IS NULL
                AND completed_at IS NOT NULL)
            OR (status = 'FALLBACK_APPLIED'
                AND learning_task_id IS NOT NULL
                AND fallback_reason IS NOT NULL
                AND completed_at IS NOT NULL
                AND workflow_version >= 1)
        ),
    ADD CONSTRAINT ck_planning_run_fallback_reason
        CHECK (fallback_reason IS NULL OR fallback_reason IN (
            'WAIT_BUDGET_EXHAUSTED',
            'MODEL_CALL_FAILED',
            'MODEL_RESULT_UNAVAILABLE',
            'MODEL_OUTPUT_REJECTED'
        )),
    ADD CONSTRAINT ck_planning_run_completion
        CHECK (completed_at IS NULL OR completed_at >= created_at);

COMMENT ON COLUMN planning_run.learning_task_id IS
    'terminal Run 绑定的唯一 LearningTask；经 composite FK 与 owner/profile 绑定为同一行，UNIQUE 保证一个 task 最多属于一个 Run；PENDING 时必须为空。';
COMMENT ON COLUMN planning_run.fallback_reason IS
    'FALLBACK_APPLIED 的 closed safe reason（不包含 generated text 或 Provider detail）；MODEL_APPLIED / PENDING 时必须为空。';
COMMENT ON COLUMN planning_run.status IS
    '单向 lifecycle：PENDING → MODEL_APPLIED（唯一 MODEL_ENRICHED task）或 FALLBACK_APPLIED（唯一 deterministic task + reason + workflowVersion +1）；transition 由 status + rowVersion CAS 裁决。';
COMMENT ON COLUMN planning_run.workflow_version IS
    '创建该 Run 的 Planner enrichment workflow revision；FALLBACK_APPLIED 原子推进（+1）使迟到 Model success 在语义上不再适用，MODEL_APPLIED 保持创建时 version。';
