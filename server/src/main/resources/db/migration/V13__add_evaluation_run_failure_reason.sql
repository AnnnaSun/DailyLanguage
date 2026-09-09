-- S8E：区分 Java grounding rejection 与 ModelCallJob execution/result failure。
-- Job 保存具体失败状态；EvaluationRun 只保存稳定的 workflow-level failure category。

ALTER TABLE evaluation_run
    ADD COLUMN failure_reason VARCHAR(32);

UPDATE evaluation_run
SET failure_reason = 'GROUNDING_REJECTED'
WHERE status = 'FAILED';

ALTER TABLE evaluation_run
    DROP CONSTRAINT ck_evaluation_run_outcome,
    ADD CONSTRAINT ck_evaluation_run_failure_reason
        CHECK (failure_reason IS NULL OR failure_reason IN (
            'GROUNDING_REJECTED', 'MODEL_CALL_FAILED', 'MODEL_RESULT_UNAVAILABLE')),
    ADD CONSTRAINT ck_evaluation_run_outcome
        CHECK (
            (status = 'PENDING' AND completed_at IS NULL
                AND failure_reason IS NULL AND grounding_rejection_reason IS NULL)
            OR (status = 'SUCCEEDED' AND completed_at IS NOT NULL AND completed_at >= created_at
                AND failure_reason IS NULL AND grounding_rejection_reason IS NULL)
            OR (status = 'FAILED' AND completed_at IS NOT NULL AND completed_at >= created_at
                AND failure_reason = 'GROUNDING_REJECTED' AND grounding_rejection_reason IS NOT NULL)
            OR (status = 'FAILED' AND completed_at IS NOT NULL AND completed_at >= created_at
                AND failure_reason IN ('MODEL_CALL_FAILED', 'MODEL_RESULT_UNAVAILABLE')
                AND grounding_rejection_reason IS NULL)
        );

COMMENT ON COLUMN evaluation_run.failure_reason IS
    'FAILED Run 的 workflow-level category；具体 Model failure/status 仍由绑定 ModelCallJob 保存。';
COMMENT ON COLUMN evaluation_run.grounding_rejection_reason IS
    'failure_reason=GROUNDING_REJECTED 时的安全 category；其他 Run outcome 必须为空。';
COMMENT ON COLUMN evaluation_run.status IS
    'PENDING：semantic branch 未完成；SUCCEEDED：存在 validated candidate；FAILED：grounding 拒绝或 Model branch 已安全终止。';

CREATE INDEX ix_evaluation_run_pending_created
    ON evaluation_run (created_at, id)
    WHERE status = 'PENDING';
