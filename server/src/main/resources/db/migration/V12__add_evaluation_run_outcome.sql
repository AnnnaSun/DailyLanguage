-- S8C：EvaluationRun 获得终端 business outcome——SUCCEEDED（存在唯一 validated candidate header）
-- 或 FAILED（grounding 被 durable 拒绝，只保存安全的 RejectionReason）。validated candidate / claim
-- 以 normalized 行保存，使 claim → source response 的引用关系由 PostgreSQL FK 约束，而非 JSONB 文档。

ALTER TABLE evaluation_run
    ADD COLUMN grounding_rejection_reason VARCHAR(32);

ALTER TABLE evaluation_run
    DROP CONSTRAINT ck_evaluation_run_status,
    DROP CONSTRAINT ck_evaluation_run_completed_at;

ALTER TABLE evaluation_run
    ADD CONSTRAINT ck_evaluation_run_status
        CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    ADD CONSTRAINT ck_evaluation_run_grounding_rejection_reason
        CHECK (grounding_rejection_reason IS NULL OR grounding_rejection_reason IN (
            'INVALID_STRUCTURE', 'INVALID_INPUT', 'RUBRIC_UNAVAILABLE', 'UNKNOWN_TURN',
            'QUOTE_MISMATCH', 'AMBIGUOUS_OCCURRENCE', 'INVALID_OCCURRENCE', 'UNSUPPORTED_ISSUE',
            'INVALID_CONFIDENCE', 'LIMIT_EXCEEDED')),
    -- outcome pairing：PENDING 无 terminal 事实；SUCCEEDED 有 completed_at 无 reason；
    -- FAILED 有 completed_at 且有 reason（不复制 raw output）。
    ADD CONSTRAINT ck_evaluation_run_outcome
        CHECK (
            (status = 'PENDING' AND completed_at IS NULL AND grounding_rejection_reason IS NULL)
            OR (status = 'SUCCEEDED' AND completed_at IS NOT NULL AND completed_at >= created_at
                AND grounding_rejection_reason IS NULL)
            OR (status = 'FAILED' AND completed_at IS NOT NULL AND completed_at >= created_at
                AND grounding_rejection_reason IS NOT NULL)
        ),
    -- claim 通过 composite key 引用 run 的 session，保证 candidate 的 session 就是 Run 的 session。
    ADD CONSTRAINT uq_evaluation_run_id_session UNIQUE (id, session_id);

-- 与 SemanticGroundingResult.RejectionReason 封闭词汇一致；FAILED 只保存安全 category。
COMMENT ON COLUMN evaluation_run.grounding_rejection_reason IS
    'FAILED 时的 grounding 拒绝原因（安全 category，不含 learner text / raw output）；SUCCEEDED / PENDING 时必须为空。';
COMMENT ON COLUMN evaluation_run.status IS
    'PENDING：尚无 durable semantic outcome；SUCCEEDED：存在唯一 validated candidate header；FAILED：Model success 已消费但 grounding 被拒绝。';

CREATE TABLE validated_semantic_candidate (
    evaluation_run_id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    material_id TEXT NOT NULL,
    material_published_version TEXT NOT NULL,
    rubric_reference TEXT NOT NULL,
    target_language VARCHAR(35) NOT NULL,
    grounding_policy_version VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- evaluation_run_id + session_id 必须与同一 Run 行绑定，不能跨 Run / Session 拼装。
    CONSTRAINT fk_validated_semantic_candidate_run
        FOREIGN KEY (evaluation_run_id, session_id)
        REFERENCES evaluation_run (id, session_id) ON DELETE RESTRICT,
    -- 一个 Session 最多一份 candidate header（与 evaluation_run UNIQUE(session_id) 双层一致）。
    CONSTRAINT uq_validated_semantic_candidate_session UNIQUE (session_id),
    -- claim 的 composite FK 必须引用同一 candidate/run/session pair。
    CONSTRAINT uq_validated_semantic_candidate_run_session UNIQUE (evaluation_run_id, session_id),
    CONSTRAINT ck_validated_semantic_candidate_material_id
        CHECK (material_id <> '' AND material_id !~ '^[[:space:]]' AND material_id !~ '[[:space:]]$'),
    CONSTRAINT ck_validated_semantic_candidate_material_version
        CHECK (material_published_version <> '' AND material_published_version !~ '^[[:space:]]'
            AND material_published_version !~ '[[:space:]]$'),
    CONSTRAINT ck_validated_semantic_candidate_rubric_reference
        CHECK (rubric_reference <> '' AND rubric_reference !~ '^[[:space:]]'
            AND rubric_reference !~ '[[:space:]]$'),
    CONSTRAINT ck_validated_semantic_candidate_target_language
        CHECK (target_language <> '' AND target_language !~ '^[[:space:]]'
            AND target_language !~ '[[:space:]]$'),
    CONSTRAINT ck_validated_semantic_candidate_policy_version
        CHECK (grounding_policy_version = 'M1_GROUNDED_QUOTE_V1')
);

COMMENT ON TABLE validated_semantic_candidate IS
    '一次成功 grounding 的 durable candidate header；即使零 claim 也保存 header，以区分“已评估且零 claim”与“尚未评估”。claim 明细在 validated_semantic_claim；provider / model / raw output 不在此复制，provenance 经 Run → Job 还原。';
COMMENT ON COLUMN validated_semantic_candidate.evaluation_run_id IS
    '所属 EvaluationRun；一个 Run 最多一份 candidate（PRIMARY KEY）。';
COMMENT ON COLUMN validated_semantic_candidate.session_id IS
    '必须等于所属 Run 的 session_id（composite FK）；UNIQUE 保证一个 Session 最多一份 candidate。';
COMMENT ON COLUMN validated_semantic_candidate.material_id IS
    'grounding 时使用的 exact material identity（materialId + publishedVersion），不读取最新版本。';
COMMENT ON COLUMN validated_semantic_candidate.grounding_policy_version IS
    '产生该 candidate 的 grounding policy 版本（如 M1_GROUNDED_QUOTE_V1）。';

CREATE TABLE validated_semantic_claim (
    evaluation_run_id UUID NOT NULL,
    session_id UUID NOT NULL,
    claim_index INT NOT NULL,
    source_turn_id VARCHAR(128) NOT NULL,
    exact_quote TEXT NOT NULL,
    occurrence_index INT NOT NULL,
    start_offset INT NOT NULL,
    end_offset INT NOT NULL,
    issue_type VARCHAR(32) NOT NULL,
    explanation TEXT NOT NULL,
    confidence DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (evaluation_run_id, claim_index),
    CONSTRAINT fk_validated_semantic_claim_candidate
        FOREIGN KEY (evaluation_run_id, session_id)
        REFERENCES validated_semantic_candidate (evaluation_run_id, session_id) ON DELETE RESTRICT,
    -- claim 的 source turn 必须是该 Session 已接受的 practice response（learner 原文）。
    CONSTRAINT fk_validated_semantic_claim_response
        FOREIGN KEY (session_id, source_turn_id)
        REFERENCES practice_response (session_id, step_id) ON DELETE RESTRICT,
    CONSTRAINT ck_validated_semantic_claim_source_turn
        CHECK (source_turn_id <> '' AND source_turn_id !~ '^[[:space:]]'
            AND source_turn_id !~ '[[:space:]]$'),
    CONSTRAINT ck_validated_semantic_claim_index
        CHECK (claim_index BETWEEN 0 AND 19),
    -- offsets 是 Java 计算的 UTF-16 code unit 区间 [start, end)；PostgreSQL 只做结构性边界检查，
    -- 不用 code-point substring 重算 grounding。
    CONSTRAINT ck_validated_semantic_claim_offsets
        CHECK (start_offset >= 0 AND end_offset > start_offset),
    CONSTRAINT ck_validated_semantic_claim_occurrence
        CHECK (occurrence_index >= 0),
    CONSTRAINT ck_validated_semantic_claim_issue_type
        CHECK (issue_type IN ('GRAMMAR', 'NATURALNESS', 'TASK_RESPONSE')),
    -- exact_quote 是 learner 原文的精确子串（受 response 上限约束）；explanation 与 Validator 上限一致。
    CONSTRAINT ck_validated_semantic_claim_quote
        CHECK (char_length(exact_quote) BETWEEN 1 AND 2000),
    CONSTRAINT ck_validated_semantic_claim_explanation
        CHECK (char_length(explanation) BETWEEN 1 AND 1000),
    CONSTRAINT ck_validated_semantic_claim_confidence
        CHECK (confidence >= 0 AND confidence <= 1)
);

COMMENT ON TABLE validated_semantic_claim IS
    'validated candidate 的 claim 明细；claim_index 保存 Validator 输出顺序，offsets / occurrence 由 Java grounding 计算并已通过 S7 校验。';
COMMENT ON COLUMN validated_semantic_claim.claim_index IS
    'Validator 输出顺序（0-based），与 (evaluation_run_id) 构成主键。';
COMMENT ON COLUMN validated_semantic_claim.source_turn_id IS
    '引用的 material stepId；经 composite FK 保证是该 Session 已接受的 response。';
