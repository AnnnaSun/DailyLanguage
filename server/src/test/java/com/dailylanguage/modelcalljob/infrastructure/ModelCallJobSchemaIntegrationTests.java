package com.dailylanguage.modelcalljob.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.languageprofile.infrastructure.LanguageProfileRepository;
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
class ModelCallJobSchemaIntegrationTests {

    private static final String INSERT_JOB = """
            INSERT INTO model_call_job (
                user_id,
                language_profile_id,
                model_purpose,
                model_operation,
                provider_id,
                model_id,
                workflow_id,
                workflow_step_id,
                workflow_version,
                execution_status,
                consumption_status,
                row_version,
                created_at,
                completed_at,
                expires_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LanguageProfileRepository languageProfileRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsUuidV7JobsWithSeparateLifecycleDefaultsAndOptionalLanguageProfile() {
        UUID userId = userRepository.create();
        LanguageProfileIdentity languageProfile = languageProfileRepository.create(userId, "en")
                .orElseThrow();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusHours(1);

        UUID userLevelJobId = jdbcTemplate.queryForObject("""
                INSERT INTO model_call_job (
                    user_id,
                    model_purpose,
                    model_operation,
                    workflow_id,
                    workflow_step_id,
                    workflow_version,
                    expires_at
                )
                VALUES (?, 'CONVERSATION', 'TEXT_GENERATION', ?, 'GENERATE_REPLY', 0, ?)
                RETURNING id
                """, UUID.class, userId, UUID.randomUUID(), expiresAt);
        UUID profileJobId = insertJob(
                userId,
                languageProfile.id(),
                "deepseek",
                "deepseek-chat",
                "CREATED",
                "NOT_READY",
                1,
                0,
                OffsetDateTime.now(),
                null,
                expiresAt);

        Map<String, Object> userLevelJob = jdbcTemplate.queryForMap("""
                SELECT language_profile_id,
                       provider_id,
                       model_id,
                       execution_status,
                       consumption_status,
                       row_version
                FROM model_call_job
                WHERE id = ?
                """, userLevelJobId);

        assertThat(userLevelJobId.version()).isEqualTo(7);
        assertThat(profileJobId.version()).isEqualTo(7);
        assertThat(userLevelJob)
                .containsEntry("language_profile_id", null)
                .containsEntry("provider_id", null)
                .containsEntry("model_id", null)
                .containsEntry("execution_status", "CREATED")
                .containsEntry("consumption_status", "NOT_READY")
                .containsEntry("row_version", 0L);
    }

    @Test
    void rejectsLanguageProfileOwnedByAnotherUser() {
        UUID profileOwnerId = userRepository.create();
        UUID jobOwnerId = userRepository.create();
        LanguageProfileIdentity languageProfile = languageProfileRepository.create(profileOwnerId, "ja")
                .orElseThrow();
        OffsetDateTime createdAt = OffsetDateTime.now();

        assertThatThrownBy(() -> insertJob(
                        jobOwnerId,
                        languageProfile.id(),
                        null,
                        null,
                        "CREATED",
                        "NOT_READY",
                        0,
                        0,
                        createdAt,
                        null,
                        createdAt.plusHours(1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "deepseek, NULL",
            "NULL, deepseek-chat"
    }, nullValues = "NULL")
    void rejectsPartialRouteIdentity(String providerId, String modelId) {
        UUID userId = userRepository.create();
        OffsetDateTime createdAt = OffsetDateTime.now();

        assertThatThrownBy(() -> insertJob(
                        userId,
                        null,
                        providerId,
                        modelId,
                        "CREATED",
                        "NOT_READY",
                        0,
                        0,
                        createdAt,
                        null,
                        createdAt.plusHours(1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @MethodSource("routeIdentitiesWithBoundaryWhitespace")
    void rejectsRouteIdentityWithBoundaryWhitespace(String providerId, String modelId) {
        UUID userId = userRepository.create();
        OffsetDateTime createdAt = OffsetDateTime.now();

        assertThatThrownBy(() -> insertJob(
                        userId,
                        null,
                        providerId,
                        modelId,
                        "CREATED",
                        "NOT_READY",
                        0,
                        0,
                        createdAt,
                        null,
                        createdAt.plusHours(1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsWorkflowStepWithBoundaryWhitespace() {
        UUID userId = userRepository.create();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusHours(1);

        assertThatThrownBy(() -> jdbcTemplate.queryForObject("""
                        INSERT INTO model_call_job (
                            user_id,
                            model_purpose,
                            model_operation,
                            workflow_id,
                            workflow_step_id,
                            workflow_version,
                            expires_at
                        )
                        VALUES (?, 'PLANNING', 'TEXT_GENERATION', ?, ?, 0, ?)
                        RETURNING id
                        """, UUID.class, userId, UUID.randomUUID(), "\tGENERATE_TASK", expiresAt))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "UNSUPPORTED, NOT_READY",
            "CREATED, UNSUPPORTED"
    })
    void rejectsUnsupportedLifecycleVocabulary(String executionStatus, String consumptionStatus) {
        UUID userId = userRepository.create();
        OffsetDateTime createdAt = OffsetDateTime.now();

        assertThatThrownBy(() -> insertJob(
                        userId,
                        null,
                        null,
                        null,
                        executionStatus,
                        consumptionStatus,
                        0,
                        0,
                        createdAt,
                        null,
                        createdAt.plusHours(1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "-1, 0",
            "0, -1"
    })
    void rejectsNegativeWorkflowOrRowVersion(long workflowVersion, long rowVersion) {
        UUID userId = userRepository.create();
        OffsetDateTime createdAt = OffsetDateTime.now();

        assertThatThrownBy(() -> insertJob(
                        userId,
                        null,
                        null,
                        null,
                        "CREATED",
                        "NOT_READY",
                        workflowVersion,
                        rowVersion,
                        createdAt,
                        null,
                        createdAt.plusHours(1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsCompletionTimestampForNonTerminalExecutionStatus() {
        UUID userId = userRepository.create();
        OffsetDateTime createdAt = OffsetDateTime.now();

        assertThatThrownBy(() -> insertJob(
                        userId,
                        null,
                        null,
                        null,
                        "CREATED",
                        "NOT_READY",
                        0,
                        0,
                        createdAt,
                        createdAt,
                        createdAt.plusHours(1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsExpiryAtOrBeforeCreation() {
        UUID userId = userRepository.create();
        OffsetDateTime createdAt = OffsetDateTime.now();

        assertThatThrownBy(() -> insertJob(
                        userId,
                        null,
                        null,
                        null,
                        "CREATED",
                        "NOT_READY",
                        0,
                        0,
                        createdAt,
                        null,
                        createdAt.minusSeconds(1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void limitsDurableJobStateToApprovedMetadataAndSafeFailure() {
        List<String> columnNames = jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'model_call_job'
                ORDER BY ordinal_position
                """, String.class);

        assertThat(columnNames).containsExactly(
                "id",
                "user_id",
                "language_profile_id",
                "model_purpose",
                "model_operation",
                "provider_id",
                "model_id",
                "workflow_id",
                "workflow_step_id",
                "workflow_version",
                "execution_status",
                "consumption_status",
                "row_version",
                "created_at",
                "completed_at",
                "expires_at",
                "failure_kind",
                "failure_retry_after_seconds",
                "failure_retry_after_nanos");
    }

    private UUID insertJob(
            UUID userId,
            UUID languageProfileId,
            String providerId,
            String modelId,
            String executionStatus,
            String consumptionStatus,
            long workflowVersion,
            long rowVersion,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt,
            OffsetDateTime expiresAt) {
        return jdbcTemplate.queryForObject(
                INSERT_JOB,
                UUID.class,
                userId,
                languageProfileId,
                "PLANNING",
                "TEXT_GENERATION",
                providerId,
                modelId,
                UUID.randomUUID(),
                "GENERATE_TASK",
                workflowVersion,
                executionStatus,
                consumptionStatus,
                rowVersion,
                createdAt,
                completedAt,
                expiresAt);
    }

    private static Stream<Arguments> routeIdentitiesWithBoundaryWhitespace() {
        return Stream.of(
                Arguments.of("\t", "deepseek-chat"),
                Arguments.of(" deepseek", "deepseek-chat"),
                Arguments.of("deepseek", "deepseek-chat\t"));
    }
}
