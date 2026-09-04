CREATE TABLE practice_session (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    task_id UUID NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS',
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    abandoned_at TIMESTAMPTZ,
    CONSTRAINT fk_practice_session_task
        FOREIGN KEY (task_id) REFERENCES learning_task (id) ON DELETE RESTRICT,
    -- M1 lifecycle vocabulary 的封闭枚举；ABANDONED 保留给已批准 lifecycle，当前 API 不提供 abandon transition。
    CONSTRAINT ck_practice_session_status
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'ABANDONED')),
    CONSTRAINT ck_practice_session_lifecycle_timestamps
        CHECK (
            (
                status = 'IN_PROGRESS'
                AND started_at IS NOT NULL
                AND completed_at IS NULL
                AND abandoned_at IS NULL
            )
            OR (
                status = 'COMPLETED'
                AND started_at IS NOT NULL
                AND completed_at IS NOT NULL
                AND completed_at >= started_at
                AND abandoned_at IS NULL
            )
            OR (
                status = 'ABANDONED'
                AND started_at IS NOT NULL
                AND abandoned_at IS NOT NULL
                AND abandoned_at >= started_at
                AND completed_at IS NULL
            )
        )
);

CREATE TABLE practice_response (
    session_id UUID NOT NULL,
    step_id VARCHAR(128) NOT NULL,
    learner_text TEXT NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, step_id),
    CONSTRAINT fk_practice_response_session
        FOREIGN KEY (session_id) REFERENCES practice_session (id) ON DELETE RESTRICT,
    -- 与 Application 层的 Unicode code point 上限一致；PostgreSQL char_length 按 code point 计数。
    CONSTRAINT ck_practice_response_learner_text
        CHECK (char_length(learner_text) BETWEEN 1 AND 2000),
    -- 默认 BTRIM 只处理普通空格；边界 whitespace 检查必须覆盖 tab、换行等空白字符。
    CONSTRAINT ck_practice_response_step_id
        CHECK (
            step_id <> ''
            AND step_id !~ '^[[:space:]]'
            AND step_id !~ '[[:space:]]$'
        )
);

COMMENT ON TABLE practice_session IS
    'Practice 产生的 durable Session：一个 LearningTask 最多一个 Session，status 与 lifecycle timestamp 由 PostgreSQL 裁决；不保存 learner response、assessment、Evidence 或 Prompt。';
COMMENT ON COLUMN practice_session.id IS
    'PostgreSQL 生成的稳定 UUIDv7 sessionId。';
COMMENT ON COLUMN practice_session.task_id IS
    '本 Session 练习的 LearningTask；UNIQUE 保证一个 Task 最多一个 Session，owner/profile/material identity 通过该 Task 还原，不在此重复存储。';
COMMENT ON COLUMN practice_session.status IS
    '生命周期：IN_PROGRESS → COMPLETED 或 IN_PROGRESS → ABANDONED。';
COMMENT ON COLUMN practice_session.started_at IS
    'Session 创建（Task 进入 STARTED）的时间；lifecycle 下界。';
COMMENT ON COLUMN practice_session.completed_at IS
    '进入 COMPLETED 的时间；IN_PROGRESS/ABANDONED 时必须为空。';
COMMENT ON COLUMN practice_session.abandoned_at IS
    '进入 ABANDONED 的时间；IN_PROGRESS/COMPLETED 时必须为空。';

COMMENT ON TABLE practice_response IS
    'Learner 对 material step 的已接受 response：identity 是 (session_id, step_id)，同一 step 只保存首次接受的 exact text；不保存 correctness、semantic result、Model 输出或 Evidence。';
COMMENT ON COLUMN practice_response.session_id IS
    '所属 PracticeSession；response 不单独持有 owner/profile，ownership 通过 Session → Task 链路裁决。';
COMMENT ON COLUMN practice_response.step_id IS
    'material 定义的 step identity；必须是 resolved material 中存在的 stepId。';
COMMENT ON COLUMN practice_response.learner_text IS
    'learner 提交的原始 target-language 文本；保存时不 trim、不改大小写、不做 Unicode normalization。';
COMMENT ON COLUMN practice_response.submitted_at IS
    'PostgreSQL 首次接受该 response 的时间；重放时返回同一时间。';
