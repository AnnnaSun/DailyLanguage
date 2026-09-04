CREATE TABLE learning_task (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    user_id UUID NOT NULL,
    language_profile_id UUID NOT NULL,
    material_id TEXT NOT NULL,
    published_version TEXT NOT NULL,
    support_language VARCHAR(35) NOT NULL,
    difficulty VARCHAR(32) NOT NULL,
    estimated_duration_minutes SMALLINT NOT NULL,
    scenario TEXT NOT NULL,
    primary_goal TEXT NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    planning_reason VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PLANNED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_learning_task_user
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    -- Composite FK 引用 V4 建立的 (id, user_id) ownership key，保证 task 与 profile 永远属于同一 User。
    CONSTRAINT fk_learning_task_language_profile_owner
        FOREIGN KEY (language_profile_id, user_id)
        REFERENCES language_profile (id, user_id) ON DELETE RESTRICT,
    -- 默认 BTRIM 只处理普通空格；边界 whitespace 检查必须覆盖 tab、换行等空白字符。
    CONSTRAINT ck_learning_task_material_identity
        CHECK (
            material_id <> ''
            AND material_id !~ '^[[:space:]]'
            AND material_id !~ '[[:space:]]$'
            AND published_version <> ''
            AND published_version !~ '^[[:space:]]'
            AND published_version !~ '[[:space:]]$'
        ),
    CONSTRAINT ck_learning_task_support_language
        CHECK (
            support_language = LOWER(support_language)
            AND support_language <> ''
            AND support_language !~ '^[[:space:]]'
            AND support_language !~ '[[:space:]]$'
        ),
    CONSTRAINT ck_learning_task_scenario
        CHECK (
            scenario <> ''
            AND scenario !~ '^[[:space:]]'
            AND scenario !~ '[[:space:]]$'
        ),
    CONSTRAINT ck_learning_task_primary_goal
        CHECK (
            primary_goal <> ''
            AND primary_goal !~ '^[[:space:]]'
            AND primary_goal !~ '[[:space:]]$'
        ),
    -- M1 vocabulary 的封闭枚举；新增取值属于 difficulty framework / planner contract 的显式演进。
    CONSTRAINT ck_learning_task_difficulty
        CHECK (difficulty IN ('FOUNDATION')),
    CONSTRAINT ck_learning_task_task_type
        CHECK (task_type IN ('TEXT_PRACTICE')),
    CONSTRAINT ck_learning_task_planning_reason
        CHECK (planning_reason IN ('DETERMINISTIC_BUILT_IN_FALLBACK')),
    CONSTRAINT ck_learning_task_status
        CHECK (status IN ('PLANNED', 'STARTED', 'COMPLETED')),
    CONSTRAINT ck_learning_task_duration
        CHECK (estimated_duration_minutes BETWEEN 5 AND 10),
    CONSTRAINT ck_learning_task_lifecycle_timestamps
        CHECK (
            (status = 'PLANNED' AND started_at IS NULL AND completed_at IS NULL)
            OR (
                status = 'STARTED'
                AND started_at IS NOT NULL
                AND completed_at IS NULL
                AND started_at >= created_at
            )
            OR (
                status = 'COMPLETED'
                AND started_at IS NOT NULL
                AND completed_at IS NOT NULL
                AND completed_at >= started_at
                AND started_at >= created_at
            )
        )
);

COMMENT ON TABLE learning_task IS
    'Planner 输出的 durable LearningTask：identity、owner/profile ownership、exact material version 与单向 lifecycle；不保存 Content 本体、learner response、Prompt、Credential 或 Evidence。';
COMMENT ON COLUMN learning_task.id IS
    'PostgreSQL 生成的稳定 UUIDv7 taskId；不编码 material version 或 lifecycle 状态。';
COMMENT ON COLUMN learning_task.user_id IS
    '拥有并有权推进该 Task 的应用 User identity。';
COMMENT ON COLUMN learning_task.language_profile_id IS
    'Task 所属的目标语言 workspace；通过 composite FK 与 user_id 绑定为同一 Profile 行。';
COMMENT ON COLUMN learning_task.material_id IS
    '创建 Task 时 Planner 选定的 material identity；不自动跟随 catalog 的新 publishedVersion。';
COMMENT ON COLUMN learning_task.published_version IS
    '创建 Task 时锁定的 exact published version；material 内容变更必须以新 version 发布。';
COMMENT ON COLUMN learning_task.support_language IS
    '本 Task scaffold 使用的 support language（BCP 47 lowercase）。';
COMMENT ON COLUMN learning_task.difficulty IS
    'Task difficulty；M1 vocabulary 仅 FOUNDATION。';
COMMENT ON COLUMN learning_task.estimated_duration_minutes IS
    'Planner 估计的练习时长（分钟）；M1 允许 5–10。';
COMMENT ON COLUMN learning_task.scenario IS
    '练习场景的自然语言描述。';
COMMENT ON COLUMN learning_task.primary_goal IS
    '本 Task 要达成的沟通目标。';
COMMENT ON COLUMN learning_task.task_type IS
    'Practice 类型；M1 仅 TEXT_PRACTICE。';
COMMENT ON COLUMN learning_task.planning_reason IS
    '产生该 Task 的 planner 决策记录；M1 仅 DETERMINISTIC_BUILT_IN_FALLBACK。';
COMMENT ON COLUMN learning_task.status IS
    '单向 lifecycle：PLANNED → STARTED → COMPLETED。';
COMMENT ON COLUMN learning_task.created_at IS
    'PostgreSQL 创建该 durable Task row 的时间。';
COMMENT ON COLUMN learning_task.started_at IS
    '进入 STARTED 的时间；PLANNED 时必须为空。';
COMMENT ON COLUMN learning_task.completed_at IS
    '进入 COMPLETED 的时间；PLANNED/STARTED 时必须为空。';
