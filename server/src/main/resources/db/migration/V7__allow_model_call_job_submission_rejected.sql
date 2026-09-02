-- Submission rejection 发生在 Provider 调用前，必须与 Model failure 分离并保留可查询的 terminal Job 事实。
ALTER TABLE model_call_job
    DROP CONSTRAINT ck_model_call_job_execution_status,
    DROP CONSTRAINT ck_model_call_job_completion,
    DROP CONSTRAINT ck_model_call_job_failure_state,
    ADD CONSTRAINT ck_model_call_job_execution_status
        CHECK (execution_status IN (
            'CREATED',
            'RUNNING',
            'SUCCEEDED',
            'FAILED',
            'TIMED_OUT',
            'OUTCOME_UNKNOWN',
            'SUBMISSION_REJECTED'
        )),
    ADD CONSTRAINT ck_model_call_job_completion
        CHECK (
            (execution_status IN ('CREATED', 'RUNNING') AND completed_at IS NULL)
            OR (
                execution_status IN (
                    'SUCCEEDED',
                    'FAILED',
                    'TIMED_OUT',
                    'OUTCOME_UNKNOWN',
                    'SUBMISSION_REJECTED'
                )
                AND completed_at IS NOT NULL
            )
        ),
    ADD CONSTRAINT ck_model_call_job_failure_state
        CHECK (
            (
                execution_status IN (
                    'CREATED',
                    'RUNNING',
                    'SUCCEEDED',
                    'OUTCOME_UNKNOWN',
                    'SUBMISSION_REJECTED'
                )
                AND failure_kind IS NULL
            )
            OR (
                execution_status = 'TIMED_OUT'
                AND failure_kind IS NOT NULL
                AND failure_kind = 'TIMEOUT'
            )
            OR (
                execution_status = 'FAILED'
                AND failure_kind IS NOT NULL
                AND failure_kind IN (
                    'CAPABILITY_UNAVAILABLE',
                    'REQUEST_REJECTED',
                    'AUTHENTICATION_FAILED',
                    'CREDENTIAL_UNAVAILABLE',
                    'RATE_LIMITED',
                    'TEMPORARY_UNAVAILABLE',
                    'PROVIDER_FAILURE'
                )
            )
        );

COMMENT ON COLUMN model_call_job.execution_status IS
    'Model execution lifecycle；SUBMISSION_REJECTED 表示 execution boundary 未接纳任务且 Provider 未调用。';
