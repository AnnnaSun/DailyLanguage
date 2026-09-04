CREATE TABLE deterministic_assessment (
    session_id UUID PRIMARY KEY,
    assessment_policy_version VARCHAR(64) NOT NULL,
    duration_seconds BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_deterministic_assessment_session
        FOREIGN KEY (session_id) REFERENCES practice_session (id) ON DELETE RESTRICT,
    -- M1 唯一的 deterministic assessment policy；演进必须以新 policy version 与显式 migration 进入。
    CONSTRAINT ck_deterministic_assessment_policy_version
        CHECK (assessment_policy_version = 'M1_TEXT_EXACT_V1'),
    CONSTRAINT ck_deterministic_assessment_duration_seconds
        CHECK (duration_seconds >= 0)
);

CREATE TABLE deterministic_step_assessment (
    session_id UUID NOT NULL,
    step_id VARCHAR(128) NOT NULL,
    step_kind VARCHAR(32) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    PRIMARY KEY (session_id, step_id),
    CONSTRAINT fk_deterministic_step_assessment_assessment
        FOREIGN KEY (session_id) REFERENCES deterministic_assessment (session_id) ON DELETE RESTRICT,
    CONSTRAINT ck_deterministic_step_assessment_step_kind
        CHECK (step_kind IN ('EXACT', 'SEMANTIC_ONLY')),
    -- outcome 由 stepKind 决定：EXACT 只能 MATCHED / NOT_MATCHED，SEMANTIC_ONLY 只能 NOT_APPLICABLE。
    CONSTRAINT ck_deterministic_step_assessment_outcome
        CHECK (
            (step_kind = 'EXACT' AND outcome IN ('MATCHED', 'NOT_MATCHED'))
            OR (step_kind = 'SEMANTIC_ONLY' AND outcome = 'NOT_APPLICABLE')
        ),
    -- 与 practice_response / learning_task 相同的 identifier whitespace 约束。
    CONSTRAINT ck_deterministic_step_assessment_step_id
        CHECK (
            step_id <> ''
            AND step_id !~ '^[[:space:]]'
            AND step_id !~ '[[:space:]]$'
        )
);

COMMENT ON TABLE deterministic_assessment IS
    'Java 在 completion transaction 内计算并持久化的 deterministic assessment：一个 PracticeSession 最多一个，policy version 封闭为 M1_TEXT_EXACT_V1；不保存 semantic correctness、weakness、Evidence 或任何长期学习状态。';
COMMENT ON COLUMN deterministic_assessment.session_id IS
    '所属 PracticeSession；同时是主键，保证一个 Session 只有一个 deterministic assessment。';
COMMENT ON COLUMN deterministic_assessment.assessment_policy_version IS
    '计算该结果的 deterministic policy 版本；当前唯一取值 M1_TEXT_EXACT_V1（strip + NFC + case-sensitive exact 比较）。';
COMMENT ON COLUMN deterministic_assessment.duration_seconds IS
    '由 durable started_at → completed_at 计算的练习时长（秒）；PostgreSQL lifecycle timestamp 是 authority。';
COMMENT ON COLUMN deterministic_assessment.created_at IS
    'PostgreSQL 持久化该 assessment 的时间；与 Session COMPLETED transition 处于同一 completion transaction。';

COMMENT ON TABLE deterministic_step_assessment IS
    'deterministic assessment 的 step 级结果：EXACT step 记录 exact 比较 outcome，SEMANTIC_ONLY step 只记录 NOT_APPLICABLE；不保存 learner text、accepted answers 或 semantic 判定。';
COMMENT ON COLUMN deterministic_step_assessment.session_id IS
    '所属 deterministic assessment；通过 assessment → session → task 链路还原 owner/profile。';
COMMENT ON COLUMN deterministic_step_assessment.step_id IS
    'material 定义的 step identity；与该 Session 已接受的 practice_response step 集合一致。';
COMMENT ON COLUMN deterministic_step_assessment.step_kind IS
    '持久化时的 material step kind：EXACT 或 SEMANTIC_ONLY。';
COMMENT ON COLUMN deterministic_step_assessment.outcome IS
    'EXACT 只能 MATCHED / NOT_MATCHED；SEMANTIC_ONLY 只能 NOT_APPLICABLE，不伪造 correctness 或 task success。';
