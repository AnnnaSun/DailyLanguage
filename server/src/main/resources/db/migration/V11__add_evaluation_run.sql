-- EvaluationRun 是一次 Session-level Evaluation 的 durable workflow identity：一个 completed
-- PracticeSession 最多一个 Run，一个 Run 恰好绑定一个 ModelCallJob；S8B 在任何 Model dispatch
-- 之前于同一事务内原子创建两者。ownership 不在此重复存储，经 session → learning_task 还原。
CREATE TABLE evaluation_run (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    model_call_job_id UUID NOT NULL,
    workflow_version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_evaluation_run_session
        FOREIGN KEY (session_id) REFERENCES practice_session (id) ON DELETE RESTRICT,
    CONSTRAINT fk_evaluation_run_model_call_job
        FOREIGN KEY (model_call_job_id) REFERENCES model_call_job (id) ON DELETE RESTRICT,
    -- S8B 并发 / 幂等最终约束：同一 Session 只有一个 Run；一个 Job 只属于一个 Run。
    -- 正常路径由 practice_session 行锁先行串行化，UNIQUE 是并发下的第二层 guard。
    CONSTRAINT uq_evaluation_run_session UNIQUE (session_id),
    CONSTRAINT uq_evaluation_run_model_call_job UNIQUE (model_call_job_id),
    -- S8B 只声明已实现的生命周期；S8C 实现完成状态时再扩展 status / completed_at 约束。
    CONSTRAINT ck_evaluation_run_status
        CHECK (status = 'PENDING'),
    CONSTRAINT ck_evaluation_run_workflow_version
        CHECK (workflow_version >= 0),
    CONSTRAINT ck_evaluation_run_row_version
        CHECK (row_version >= 0),
    CONSTRAINT ck_evaluation_run_completed_at
        CHECK (completed_at IS NULL)
);

COMMENT ON TABLE evaluation_run IS
    'Session-level Evaluation 的 durable workflow identity：创建时与唯一 ModelCallJob 在同一事务内原子提交，dispatch 由后续 slice 负责；不保存 Prompt、Rubric、Model request、Credential 或 evaluation 结果。';
COMMENT ON COLUMN evaluation_run.id IS
    '稳定的 UUIDv7 Run identity；先于 Job 创建由 SELECT uuidv7() 取得，model_call_job.workflow_id 必须精确等于本值。';
COMMENT ON COLUMN evaluation_run.session_id IS
    '本 Run 评估的 completed PracticeSession；UNIQUE 保证一个 Session 最多一次 Evaluation，ownership 经 session → learning_task 还原。';
COMMENT ON COLUMN evaluation_run.model_call_job_id IS
    '本 Run 绑定的唯一 ModelCallJob；UNIQUE 保证一个 Job 只属于一个 Run。';
COMMENT ON COLUMN evaluation_run.workflow_version IS
    '创建该 Run 的 Evaluation workflow revision；S8 第一版固定为 0，与绑定 Job 的 workflow_version 一致。';
COMMENT ON COLUMN evaluation_run.status IS
    'S8B 只允许 PENDING；完成生命周期由 S8C 引入。';
COMMENT ON COLUMN evaluation_run.row_version IS
    'Run row 的 optimistic-lock version；S8B 初始为 0。';
COMMENT ON COLUMN evaluation_run.created_at IS
    'PostgreSQL 创建该 durable Run row 的时间。';
COMMENT ON COLUMN evaluation_run.completed_at IS
    'S8B 必须为空；由 S8C 的完成状态引入。';
