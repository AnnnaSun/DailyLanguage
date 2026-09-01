package com.dailylanguage.modelcalljob.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.dailylanguage.user.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "app.registration-enabled=true")
@Transactional
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class ModelCallJobOutcomeSchemaIntegrationTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void storesTypedTextGenerationResultsWithOptionalUsage() {
        UUID userId = userRepository.create();
        UUID jobWithUsage = insertRunningJob(userId, "TEXT_GENERATION", "deepseek", "deepseek-chat");
        UUID jobWithoutUsage = insertRunningJob(userId, "TEXT_GENERATION", "deepseek", "deepseek-chat");

        insertTextResult(jobWithUsage, "Hello", "COMPLETED", 12L, 7L);
        insertTextResult(jobWithoutUsage, "", "UNKNOWN", null, null);

        Map<String, Object> stored = jdbcTemplate.queryForMap("""
                SELECT generated_text, finish_reason, input_tokens, output_tokens
                FROM model_call_text_generation_result
                WHERE job_id = ?
                """, jobWithUsage);
        assertThat(stored)
                .containsEntry("generated_text", "Hello")
                .containsEntry("finish_reason", "COMPLETED")
                .containsEntry("input_tokens", 12L)
                .containsEntry("output_tokens", 7L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT generated_text
                FROM model_call_text_generation_result
                WHERE job_id = ?
                """, String.class, jobWithoutUsage)).isEmpty();
    }

    @Test
    void rejectsTextResultForAnotherOperation() {
        UUID userId = userRepository.create();
        UUID jobId = insertRunningJob(userId, "VISION_UNDERSTANDING", "provider", "vision-model");

        assertThatThrownBy(() -> insertTextResult(jobId, "not vision result", "COMPLETED", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void storesAtMostOneTextResultPerJob() {
        UUID userId = userRepository.create();
        UUID jobId = insertRunningJob(userId, "TEXT_GENERATION", "deepseek", "deepseek-chat");
        insertTextResult(jobId, "first", "COMPLETED", null, null);

        assertThatThrownBy(() -> insertTextResult(jobId, "second", "COMPLETED", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidTextResults")
    void rejectsInvalidTextResultShape(
            String finishReason,
            Long inputTokens,
            Long outputTokens) {
        UUID userId = userRepository.create();
        UUID jobId = insertRunningJob(userId, "TEXT_GENERATION", "deepseek", "deepseek-chat");

        assertThatThrownBy(() -> insertTextResult(
                        jobId, "result", finishReason, inputTokens, outputTokens))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void storesSafeFailureWithExactRetryAfter() {
        UUID userId = userRepository.create();
        UUID jobId = insertRunningJob(userId, "TEXT_GENERATION", "deepseek", "deepseek-chat");

        int updated = updateFailure(jobId, "FAILED", "RATE_LIMITED", 12L, 345_678_901);

        assertThat(updated).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT execution_status,
                       failure_kind,
                       failure_retry_after_seconds,
                       failure_retry_after_nanos
                FROM model_call_job
                WHERE id = ?
                """, jobId))
                .containsEntry("execution_status", "FAILED")
                .containsEntry("failure_kind", "RATE_LIMITED")
                .containsEntry("failure_retry_after_seconds", 12L)
                .containsEntry("failure_retry_after_nanos", 345_678_901);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "SUCCEEDED, PROVIDER_FAILURE",
            "TIMED_OUT, PROVIDER_FAILURE",
            "FAILED, TIMEOUT",
            "FAILED, NULL"
    }, nullValues = "NULL")
    void rejectsFailureThatDoesNotMatchExecutionStatus(
            String executionStatus,
            String failureKind) {
        UUID userId = userRepository.create();
        UUID jobId = insertRunningJob(userId, "TEXT_GENERATION", "deepseek", "deepseek-chat");

        assertThatThrownBy(() -> updateFailure(jobId, executionStatus, failureKind, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidRetryAfterFailures")
    void rejectsInvalidFailureRetryAfter(
            String providerId,
            String modelId,
            String failureKind,
            Long retryAfterSeconds,
            Integer retryAfterNanos) {
        UUID userId = userRepository.create();
        UUID jobId = insertRunningJob(userId, "TEXT_GENERATION", providerId, modelId);

        assertThatThrownBy(() -> updateFailure(
                        jobId, "FAILED", failureKind, retryAfterSeconds, retryAfterNanos))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsRetryAfterWithoutFailureKind() {
        UUID userId = userRepository.create();
        UUID jobId = insertRunningJob(userId, "TEXT_GENERATION", "deepseek", "deepseek-chat");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        UPDATE model_call_job
                        SET failure_retry_after_seconds = 1,
                            failure_retry_after_nanos = 0
                        WHERE id = ?
                        """, jobId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void exposesOnlyControlledTextResultColumns() {
        List<String> columnNames = jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'model_call_text_generation_result'
                ORDER BY ordinal_position
                """, String.class);

        assertThat(columnNames).containsExactly(
                "job_id",
                "model_operation",
                "generated_text",
                "finish_reason",
                "input_tokens",
                "output_tokens");
    }

    private UUID insertRunningJob(
            UUID userId,
            String modelOperation,
            String providerId,
            String modelId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO model_call_job (
                    user_id,
                    model_purpose,
                    model_operation,
                    provider_id,
                    model_id,
                    workflow_id,
                    workflow_step_id,
                    workflow_version,
                    execution_status,
                    expires_at
                )
                VALUES (?, 'PLANNING', ?, ?, ?, ?, 'GENERATE_TASK', 0, 'RUNNING', ?)
                RETURNING id
                """,
                UUID.class,
                userId,
                modelOperation,
                providerId,
                modelId,
                UUID.randomUUID(),
                OffsetDateTime.now().plusHours(1));
    }

    private void insertTextResult(
            UUID jobId,
            String generatedText,
            String finishReason,
            Long inputTokens,
            Long outputTokens) {
        jdbcTemplate.update("""
                INSERT INTO model_call_text_generation_result (
                    job_id,
                    generated_text,
                    finish_reason,
                    input_tokens,
                    output_tokens
                )
                VALUES (?, ?, ?, ?, ?)
                """, jobId, generatedText, finishReason, inputTokens, outputTokens);
    }

    private int updateFailure(
            UUID jobId,
            String executionStatus,
            String failureKind,
            Long retryAfterSeconds,
            Integer retryAfterNanos) {
        return jdbcTemplate.update("""
                UPDATE model_call_job
                SET execution_status = ?,
                    failure_kind = ?,
                    failure_retry_after_seconds = ?,
                    failure_retry_after_nanos = ?,
                    completed_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                executionStatus,
                failureKind,
                retryAfterSeconds,
                retryAfterNanos,
                jobId);
    }

    private static Stream<Arguments> invalidTextResults() {
        return Stream.of(
                Arguments.of("UNSUPPORTED", null, null),
                Arguments.of("COMPLETED", -1L, 0L),
                Arguments.of("COMPLETED", 1L, null));
    }

    private static Stream<Arguments> invalidRetryAfterFailures() {
        return Stream.of(
                Arguments.of(null, null, "RATE_LIMITED", 1L, 0),
                Arguments.of("deepseek", "deepseek-chat", "PROVIDER_FAILURE", 1L, 0),
                Arguments.of("deepseek", "deepseek-chat", "RATE_LIMITED", 0L, 0),
                Arguments.of("deepseek", "deepseek-chat", "RATE_LIMITED", 1L, null));
    }
}
