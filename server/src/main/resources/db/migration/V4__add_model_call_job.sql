-- Composite FK 必须引用声明为 UNIQUE 的完整列组；它把 Profile identity 与 owner identity 绑定为同一行事实。
ALTER TABLE language_profile
    ADD CONSTRAINT uq_language_profile_id_user UNIQUE (id, user_id);

CREATE TABLE model_call_job (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    user_id UUID NOT NULL,
    language_profile_id UUID,
    model_purpose VARCHAR(32) NOT NULL,
    model_operation VARCHAR(32) NOT NULL,
    provider_id TEXT,
    model_id TEXT,
    workflow_id UUID NOT NULL,
    workflow_step_id TEXT NOT NULL,
    workflow_version BIGINT NOT NULL,
    execution_status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
    consumption_status VARCHAR(32) NOT NULL DEFAULT 'NOT_READY',
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_model_call_job_user
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_model_call_job_language_profile_owner
        FOREIGN KEY (language_profile_id, user_id)
        REFERENCES language_profile (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_model_call_job_purpose
        CHECK (model_purpose IN (
            'CONNECTION_VERIFICATION',
            'PLANNING',
            'CONVERSATION',
            'EVALUATION',
            'CONTENT_DESIGN',
            'CONTENT_REVIEW'
        )),
    CONSTRAINT ck_model_call_job_operation
        CHECK (model_operation IN (
            'TEXT_GENERATION',
            'VISION_UNDERSTANDING',
            'SPEECH_TRANSCRIPTION',
            'SPEECH_SYNTHESIS',
            'IMAGE_GENERATION',
            'EMBEDDING'
        )),
    -- 默认 BTRIM 只处理普通空格；边界 whitespace 检查还必须覆盖 tab、换行等空白字符。
    CONSTRAINT ck_model_call_job_route_identity
        CHECK (
            (provider_id IS NULL AND model_id IS NULL)
            OR (
                provider_id IS NOT NULL
                AND model_id IS NOT NULL
                AND provider_id <> ''
                AND provider_id !~ '^[[:space:]]'
                AND provider_id !~ '[[:space:]]$'
                AND model_id <> ''
                AND model_id !~ '^[[:space:]]'
                AND model_id !~ '[[:space:]]$'
            )
        ),
    CONSTRAINT ck_model_call_job_workflow_step
        CHECK (
            workflow_step_id <> ''
            AND workflow_step_id !~ '^[[:space:]]'
            AND workflow_step_id !~ '[[:space:]]$'
        ),
    CONSTRAINT ck_model_call_job_workflow_version
        CHECK (workflow_version >= 0),
    CONSTRAINT ck_model_call_job_execution_status
        CHECK (execution_status IN (
            'CREATED',
            'RUNNING',
            'SUCCEEDED',
            'FAILED',
            'TIMED_OUT',
            'OUTCOME_UNKNOWN'
        )),
    CONSTRAINT ck_model_call_job_consumption_status
        CHECK (consumption_status IN (
            'NOT_READY',
            'PENDING_CONFIRMATION',
            'CONSUMED',
            'DISCARDED',
            'EXPIRED',
            'STALE'
        )),
    CONSTRAINT ck_model_call_job_row_version
        CHECK (row_version >= 0),
    CONSTRAINT ck_model_call_job_completion
        CHECK (
            (execution_status IN ('CREATED', 'RUNNING') AND completed_at IS NULL)
            OR (
                execution_status IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'OUTCOME_UNKNOWN')
                AND completed_at IS NOT NULL
            )
        ),
    CONSTRAINT ck_model_call_job_timestamps
        CHECK (
            expires_at > created_at
            AND (completed_at IS NULL OR completed_at >= created_at)
        )
);

COMMENT ON TABLE model_call_job IS
    'Model 调用的 durable identity 与 lifecycle metadata；不保存 Credential、request 或 result payload。';
COMMENT ON COLUMN model_call_job.id IS
    '稳定的逻辑 Job UUIDv7 identity；workflowVersion 和 rowVersion 不编码进 ID。';
COMMENT ON COLUMN model_call_job.user_id IS
    '拥有并有权查询该 Job 的应用 User identity。';
COMMENT ON COLUMN model_call_job.language_profile_id IS
    '可选的目标语言 workspace；存在时必须通过 composite FK 属于同一 userId。';
COMMENT ON COLUMN model_call_job.model_purpose IS
    '业务为什么发起 Model 调用，例如 PLANNING 或 CONVERSATION。';
COMMENT ON COLUMN model_call_job.model_operation IS
    '本 Job 执行的 typed Model Operation，例如 TEXT_GENERATION。';
COMMENT ON COLUMN model_call_job.provider_id IS
    'route 选定后的 Provider identity；选定前与 modelId 同时为空。';
COMMENT ON COLUMN model_call_job.model_id IS
    'route 选定后的 Model identity；必须与 providerId 同时存在或同时缺失。';
COMMENT ON COLUMN model_call_job.workflow_id IS
    '拥有该 Job 的 Application Workflow instance identity。';
COMMENT ON COLUMN model_call_job.workflow_step_id IS
    '创建该 Job 的 Workflow step identity，用于定位迟到结果所属步骤。';
COMMENT ON COLUMN model_call_job.workflow_version IS
    '创建 Job 时的 Workflow revision；后续用于判断迟到结果是否仍适用于当前 Workflow。';
COMMENT ON COLUMN model_call_job.execution_status IS
    'Model execution lifecycle，与结果是否已经被业务消费分离。';
COMMENT ON COLUMN model_call_job.consumption_status IS
    'result consumption lifecycle，与 Model execution 是否完成分离。';
COMMENT ON COLUMN model_call_job.row_version IS
    'Job row 的 optimistic-lock version；后续 conditional update 递增后才能实现 consume-once。';
COMMENT ON COLUMN model_call_job.created_at IS
    'PostgreSQL 创建该 durable Job row 的时间。';
COMMENT ON COLUMN model_call_job.completed_at IS
    'Model execution 进入 terminal status 的时间；CREATED/RUNNING 时必须为空。';
COMMENT ON COLUMN model_call_job.expires_at IS
    'Job result 允许保留或处理到的截止时间，不是 Model execution timeout。';
