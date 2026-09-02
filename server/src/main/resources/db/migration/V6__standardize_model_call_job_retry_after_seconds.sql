-- Provider Retry-After 与现有 HTTP contract 都使用整数秒；旧 fractional value 向上取整，避免提前重试。
ALTER TABLE model_call_job
    DROP CONSTRAINT ck_model_call_job_failure_retry_after;

UPDATE model_call_job
SET failure_retry_after_seconds = failure_retry_after_seconds
        + CASE WHEN failure_retry_after_nanos > 0 THEN 1 ELSE 0 END
WHERE failure_retry_after_seconds IS NOT NULL;

ALTER TABLE model_call_job
    DROP COLUMN failure_retry_after_nanos,
    ADD CONSTRAINT ck_model_call_job_failure_retry_after
        CHECK (
            failure_retry_after_seconds IS NULL
            OR (
                failure_retry_after_seconds >= 1
                AND failure_kind IS NOT NULL
                AND failure_kind IN ('RATE_LIMITED', 'TEMPORARY_UNAVAILABLE')
                AND provider_id IS NOT NULL
                AND model_id IS NOT NULL
            )
        );

COMMENT ON COLUMN model_call_job.failure_retry_after_seconds IS
    'Optional positive whole-second Provider retry hint；fractional Duration 向上取整，不授权 automatic retry。';
