-- Generic safe failure 属于 Job lifecycle；operation-specific success artifact 使用独立表，避免 arbitrary JSON payload。
ALTER TABLE model_call_job
    ADD COLUMN failure_kind VARCHAR(32),
    ADD COLUMN failure_retry_after_seconds BIGINT,
    ADD COLUMN failure_retry_after_nanos INTEGER,
    ADD CONSTRAINT uq_model_call_job_id_operation UNIQUE (id, model_operation),
    ADD CONSTRAINT ck_model_call_job_failure_state
        CHECK (
            (
                execution_status IN ('CREATED', 'RUNNING', 'SUCCEEDED', 'OUTCOME_UNKNOWN')
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
        ),
    ADD CONSTRAINT ck_model_call_job_failure_retry_after
        CHECK (
            (
                failure_retry_after_seconds IS NULL
                AND failure_retry_after_nanos IS NULL
            )
            OR (
                failure_retry_after_seconds IS NOT NULL
                AND failure_retry_after_nanos IS NOT NULL
                AND failure_kind IS NOT NULL
                AND failure_kind IN ('RATE_LIMITED', 'TEMPORARY_UNAVAILABLE')
                AND provider_id IS NOT NULL
                AND model_id IS NOT NULL
                AND failure_retry_after_seconds >= 0
                AND failure_retry_after_nanos BETWEEN 0 AND 999999999
                AND (
                    failure_retry_after_seconds > 0
                    OR failure_retry_after_nanos > 0
                )
            )
        );

CREATE TABLE model_call_text_generation_result (
    job_id UUID PRIMARY KEY,
    model_operation VARCHAR(32) NOT NULL DEFAULT 'TEXT_GENERATION',
    generated_text TEXT NOT NULL,
    finish_reason VARCHAR(32) NOT NULL,
    input_tokens BIGINT,
    output_tokens BIGINT,
    CONSTRAINT fk_model_call_text_result_job_operation
        FOREIGN KEY (job_id, model_operation)
        REFERENCES model_call_job (id, model_operation) ON DELETE RESTRICT,
    CONSTRAINT ck_model_call_text_result_operation
        CHECK (model_operation = 'TEXT_GENERATION'),
    CONSTRAINT ck_model_call_text_result_finish_reason
        CHECK (finish_reason IN (
            'COMPLETED',
            'LENGTH_LIMIT',
            'CONTENT_FILTERED',
            'UNKNOWN'
        )),
    CONSTRAINT ck_model_call_text_result_usage
        CHECK (
            (input_tokens IS NULL AND output_tokens IS NULL)
            OR (
                input_tokens IS NOT NULL
                AND output_tokens IS NOT NULL
                AND input_tokens >= 0
                AND output_tokens >= 0
            )
        )
);

COMMENT ON TABLE model_call_job IS
    'Model 调用的 durable lifecycle metadata 与 safe failure；不保存 Credential、request 或 raw Provider response。';
COMMENT ON COLUMN model_call_job.failure_kind IS
    'Provider-neutral ModelFailureKind；只允许与 FAILED 或 TIMED_OUT execution status 对应的安全分类。';
COMMENT ON COLUMN model_call_job.failure_retry_after_seconds IS
    'Optional retryAfter 的完整秒部分；只是 Provider hint，不授权 automatic retry。';
COMMENT ON COLUMN model_call_job.failure_retry_after_nanos IS
    'Optional retryAfter 的纳秒调整部分，与 seconds 成对保存以无损恢复 Java Duration。';

COMMENT ON TABLE model_call_text_generation_result IS
    'TEXT_GENERATION Job 的 portable typed success artifact；不保存 Prompt、request 或 raw Provider response。';
COMMENT ON COLUMN model_call_text_generation_result.job_id IS
    '拥有该 result 的唯一 ModelCallJob identity；一个 Job 最多保存一个 Text Generation result。';
COMMENT ON COLUMN model_call_text_generation_result.model_operation IS
    '固定为 TEXT_GENERATION，并通过 composite FK 验证父 Job operation。';
COMMENT ON COLUMN model_call_text_generation_result.generated_text IS
    'Provider-neutral generated text；允许空字符串，不是完整 Provider response。';
COMMENT ON COLUMN model_call_text_generation_result.finish_reason IS
    'Portable TextGenerationResponse finish reason。';
COMMENT ON COLUMN model_call_text_generation_result.input_tokens IS
    'Provider 明确报告的 optional input token count；与 outputTokens 同时存在或缺失。';
COMMENT ON COLUMN model_call_text_generation_result.output_tokens IS
    'Provider 明确报告的 optional output token count；与 inputTokens 同时存在或缺失。';
