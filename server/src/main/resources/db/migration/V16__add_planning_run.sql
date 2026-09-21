-- S9C2：一次 optional Planner decision 的 durable workflow identity。PlanningRun 与唯一
-- route-unresolved PLANNING / TEXT_GENERATION ModelCallJob 在同一 REQUIRES_NEW 事务内原子
-- 创建；candidate snapshot 只保存 ordered exact material identity，不保存 Content、Prompt、
-- Credential、raw output 或 learner state。本 migration 不改变 V8 / V15 的 learning_task。

CREATE TABLE planning_run (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    language_profile_id UUID NOT NULL,
    model_call_job_id UUID NOT NULL,
    workflow_version BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_planning_run_user
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    -- Composite FK 引用 V4 建立的 (id, user_id) ownership key，把 Run 的 Profile 与 owner
    -- 绑定为同一行事实；planner 没有父 Session，ownership 直接落列而不经 session 链路还原。
    CONSTRAINT fk_planning_run_language_profile_owner
        FOREIGN KEY (language_profile_id, user_id)
        REFERENCES language_profile (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_planning_run_model_call_job
        FOREIGN KEY (model_call_job_id) REFERENCES model_call_job (id) ON DELETE RESTRICT,
    -- 一个 Job 只属于一个 Run；同一 Profile 允许多个独立 Run，本 slice 不引入 request
    -- idempotency 或 active-run uniqueness。
    CONSTRAINT uq_planning_run_model_call_job UNIQUE (model_call_job_id),
    -- S9C2 只声明已实现的生命周期；terminal outcome 由 S9E2 扩展。
    CONSTRAINT ck_planning_run_status
        CHECK (status = 'PENDING'),
    CONSTRAINT ck_planning_run_workflow_version
        CHECK (workflow_version >= 0),
    CONSTRAINT ck_planning_run_row_version
        CHECK (row_version >= 0),
    CONSTRAINT ck_planning_run_completed_at
        CHECK (completed_at IS NULL)
);

-- 创建期 ordered candidate snapshot：只保存 exact material identity 与顺序，index 0 是
-- deterministic fallback；不保存 Content 本体、task 字段或 Model output。
CREATE TABLE planning_run_candidate (
    run_id UUID NOT NULL,
    candidate_index SMALLINT NOT NULL,
    material_id TEXT NOT NULL,
    published_version TEXT NOT NULL,
    CONSTRAINT pk_planning_run_candidate
        PRIMARY KEY (run_id, candidate_index),
    CONSTRAINT fk_planning_run_candidate_run
        FOREIGN KEY (run_id) REFERENCES planning_run (id) ON DELETE RESTRICT,
    -- closed 0–7 与 PlanningCandidateSet 的 max 8 对齐；新增取值属于 planner contract 显式演进。
    CONSTRAINT ck_planning_run_candidate_index
        CHECK (candidate_index BETWEEN 0 AND 7),
    -- 同一 Run 内 exact material identity 不重复。
    CONSTRAINT uq_planning_run_candidate_identity
        UNIQUE (run_id, material_id, published_version),
    -- 与 V8 learning_task 的 material identity 文本边界一致。
    CONSTRAINT ck_planning_run_candidate_material_identity
        CHECK (
            material_id <> ''
            AND material_id !~ '^[[:space:]]'
            AND material_id !~ '[[:space:]]$'
            AND published_version <> ''
            AND published_version !~ '^[[:space:]]'
            AND published_version !~ '[[:space:]]$'
        )
);

COMMENT ON TABLE planning_run IS
    '一次 optional Planner decision 的 durable workflow identity：创建时与唯一 PLANNING ModelCallJob 在同一事务内原子提交，dispatch / 消费由后续 slice 负责；不保存 Prompt、Credential、raw Provider response 或 learner state。';
COMMENT ON COLUMN planning_run.id IS
    '稳定的 UUIDv7 Run identity；先于 Job 创建由 SELECT uuidv7() 取得，model_call_job.workflow_id 必须精确等于本值。';
COMMENT ON COLUMN planning_run.user_id IS
    '拥有并有权推进该 Run 的应用 User identity。';
COMMENT ON COLUMN planning_run.language_profile_id IS
    '本次 planning 所属的目标语言 workspace；通过 composite FK 与 user_id 绑定为同一 Profile 行。';
COMMENT ON COLUMN planning_run.model_call_job_id IS
    '本 Run 绑定的唯一 ModelCallJob；UNIQUE 保证一个 Job 只属于一个 Run。';
COMMENT ON COLUMN planning_run.workflow_version IS
    '创建该 Run 的 Planner enrichment workflow revision；S9 第一版固定为 0，与绑定 Job 的 workflow_version 一致。';
COMMENT ON COLUMN planning_run.status IS
    'S9C2 只允许 PENDING；terminal outcome 生命周期由 S9E2 引入。';
COMMENT ON COLUMN planning_run.row_version IS
    'Run row 的 optimistic-lock version；S9C2 初始为 0。';
COMMENT ON COLUMN planning_run.created_at IS
    'PostgreSQL 创建该 durable Run row 的时间。';
COMMENT ON COLUMN planning_run.completed_at IS
    'S9C2 必须为空；由 S9E2 的完成状态引入。';

COMMENT ON TABLE planning_run_candidate IS
    '创建期 candidate snapshot：按 deterministic fallback order 保存本次真正允许 Model 选择的 exact material identity；不保存 Content 本体或 Model output。';
COMMENT ON COLUMN planning_run_candidate.run_id IS
    '所属 PlanningRun；同一 Run 内 index 与 exact identity 均不允许重复。';
COMMENT ON COLUMN planning_run_candidate.candidate_index IS
    'deterministic fallback order 中的位置；index 0 是唯一 deterministic fallback，closed 0–7。';
COMMENT ON COLUMN planning_run_candidate.material_id IS
    '创建 Run 时锁定的 exact material identity；不自动跟随 catalog 的新 publishedVersion。';
COMMENT ON COLUMN planning_run_candidate.published_version IS
    '创建 Run 时锁定的 exact published version。';
