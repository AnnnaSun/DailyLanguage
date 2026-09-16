package com.dailylanguage.planner.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.languageprofile.infrastructure.LanguageProfileRepository;
import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.result.ModelFailure;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.result.ModelResult;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationPort;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.Created;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.InvalidRequest;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.LanguageProfileNotFound;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.Unavailable;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.domain.PlanningResult;
import com.dailylanguage.security.domain.UserContext;
import com.dailylanguage.user.infrastructure.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用真实 PostgreSQL + 真实 Built-in catalog + 真实异步 Job Worker 验证 S9F：无 enrichment 参数时
 * 完全保持 deterministic planning；合法 providerId / Credential pair 时经 durable
 * Run / Job / snapshot / finalization 链路产出 exactly-one task。Provider 边界被 mock 为
 * fake worker（不访问真实 Provider），Credential 不进入响应或 durable 行。
 */
@SpringBootTest(properties = "app.registration-enabled=true")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class LearningTaskPlanningIntegrationTests {

    private static final String PROVIDER_CREDENTIAL_HEADER = "X-Model-Provider-Credential";
    private static final String CREDENTIAL_SECRET = "integration-planning-secret";
    /** real catalog stable order：index 0 = cafe v2（deterministic fallback），index 1 = greeting v1。 */
    private static final MaterialIdentity CAFE_V2 =
            new MaterialIdentity("en-builtin-cafe-request", "v2");
    private static final MaterialIdentity GREETING_V1 =
            new MaterialIdentity("en-builtin-greeting-intro", "v1");
    private static final String VALID_ENRICHMENT_JSON = """
            {"materialId":"en-builtin-greeting-intro","publishedVersion":"v1","recommendationReason":"今天先练习问候与自我介绍。"}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LanguageProfileRepository languageProfileRepository;

    @Autowired
    private LearningTaskPlanningService planningService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** fake worker：拦截 Provider 边界，异步 Job Worker 与 durable 链路全部真实执行。 */
    @MockitoBean
    private TextGenerationPort textGenerationPort;

    private final List<UUID> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanupCreatedUsers() {
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("""
                    DELETE FROM planning_run_candidate WHERE run_id IN (
                        SELECT id FROM planning_run WHERE user_id = ?)""", userId);
            jdbcTemplate.update("DELETE FROM planning_run WHERE user_id = ?", userId);
            jdbcTemplate.update("""
                    DELETE FROM model_call_text_generation_result WHERE job_id IN (
                        SELECT id FROM model_call_job WHERE user_id = ?)""", userId);
            jdbcTemplate.update("DELETE FROM model_call_job WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM learning_task WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM language_profile WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
        createdUserIds.clear();
    }

    @Test
    void createsDurableBuiltInTaskForOwnedProfileThroughTheRealCatalog() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();

        LearningTaskPlanningResult result = planningService.plan(
                profile.id(), new UserContext(ownerId), command(" ZH-CN ", "FOUNDATION", 10));

        assertThat(result).isInstanceOfSatisfying(Created.class, created -> {
            LearningTask task = created.task();
            assertThat(task.id()).isNotNull();
            assertThat(task.userId()).isEqualTo(ownerId);
            assertThat(task.languageProfileId()).isEqualTo(profile.id());
            // exact materialId + publishedVersion 来自 deterministic planner 的稳定选择，不被替换；
            // cafe v2 发布后新 Planner task 锁定 guided v2，v1 仅保留给既有 task 的 exact 重放。
            assertThat(task.materialIdentity())
                    .isEqualTo(new MaterialIdentity("en-builtin-cafe-request", "v2"));
            assertThat(task.targetLanguage()).isEqualTo("en");
            assertThat(task.supportLanguage()).isEqualTo("zh-cn");
            assertThat(task.status()).isEqualTo(LearningTask.Status.PLANNED);
            assertThat(task.startedAt()).isEmpty();
            assertThat(task.completedAt()).isEmpty();

            // durable reread 与 row inspection 证明响应语义来自数据库裁决，不是未持久化的 plan。
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM learning_task WHERE user_id = ?", Integer.class, ownerId))
                    .isEqualTo(1);
            var row = jdbcTemplate.queryForMap(
                    "SELECT user_id, material_id, published_version, status FROM learning_task WHERE id = ?",
                    task.id());
            assertThat(row.get("user_id")).isEqualTo(ownerId);
            assertThat(row.get("material_id")).isEqualTo("en-builtin-cafe-request");
            assertThat(row.get("published_version")).isEqualTo("v2");
            assertThat(row.get("status")).isEqualTo("PLANNED");
        });
        // 无 enrichment 参数：不创建 Run / Job，Provider 边界零调用。
        assertThat(countRows("planning_run", ownerId)).isZero();
        assertThat(countRows("model_call_job", ownerId)).isZero();
        verifyNoInteractions(textGenerationPort);
    }

    @Test
    void rejectsUnknownAndForeignProfilesWithoutAnyMutation() {
        UUID ownerId = newUser();
        UUID otherUserId = newUser();
        LanguageProfileIdentity ownerProfile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();

        assertThat(planningService.plan(
                UUID.randomUUID(), new UserContext(ownerId), command("zh-cn", "FOUNDATION", 10)))
                .isEqualTo(new LanguageProfileNotFound());
        // wrong-owner 与 unknown 返回同一结果，不区分两者。
        assertThat(planningService.plan(
                ownerProfile.id(), new UserContext(otherUserId), command("zh-cn", "FOUNDATION", 10)))
                .isEqualTo(new LanguageProfileNotFound());
        assertThat(countRows("learning_task", ownerId)).isZero();

        assertThat(planningService.plan(
                ownerProfile.id(), new UserContext(ownerId), command("zh-cn", "FOUNDATION", 10)))
                .isInstanceOf(Created.class);
        assertThat(countRows("learning_task", ownerId)).isEqualTo(1);
    }

    @Test
    void doesNotPlanAcrossLanguageIsolationBoundary() {
        UUID ownerId = newUser();
        LanguageProfileIdentity japaneseProfile = languageProfileRepository
                .create(ownerId, "ja")
                .orElseThrow();

        assertThat(planningService.plan(
                japaneseProfile.id(), new UserContext(ownerId), command("zh-cn", "FOUNDATION", 10)))
                .isEqualTo(new Unavailable(PlanningResult.UnavailableReason.NO_ELIGIBLE_MATERIAL));
        assertThat(countRows("learning_task", ownerId)).isZero();
    }

    @Test
    void unavailableTimeTooShortDoesNotPersistAnything() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();

        assertThat(planningService.plan(
                profile.id(), new UserContext(ownerId), command("zh-cn", "FOUNDATION", 3)))
                .isEqualTo(new Unavailable(PlanningResult.UnavailableReason.AVAILABLE_TIME_TOO_SHORT));
        assertThat(countRows("learning_task", ownerId)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a tag", " ", "z"})
    void invalidRequestIsRejectedBeforeAnyDatabaseMutation(String supportLanguage) {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();

        LearningTaskPlanningService.PlanningCommand invalidCommand =
                supportLanguage.equals("z")
                        ? command("z".repeat(36), "FOUNDATION", 10)
                        : command(supportLanguage, "FOUNDATION", 10);

        assertThat(planningService.plan(profile.id(), new UserContext(ownerId), invalidCommand))
                .isEqualTo(new InvalidRequest());
        assertThat(planningService.plan(
                profile.id(), new UserContext(ownerId), command("zh-cn", "FOUNDATION", 0)))
                .isEqualTo(new InvalidRequest());
        assertThat(countRows("learning_task", ownerId)).isZero();
    }

    @Test
    void httpPostWithoutEnrichmentParametersKeepsDeterministicContract() throws Exception {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "en").orElseThrow();

        mockMvc.perform(planningPost(profile.id(), ownerId, """
                        {"supportLanguage":"zh-CN","requestedDifficulty":"FOUNDATION","availableMinutes":10}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planningReason").value("DETERMINISTIC_BUILT_IN_FALLBACK"))
                .andExpect(jsonPath("$.materialId").value("en-builtin-cafe-request"))
                .andExpect(jsonPath("$.publishedVersion").value("v2"))
                .andExpect(jsonPath("$.recommendationReason").value(org.hamcrest.Matchers.nullValue()));

        // 兼容 contract：无参数时零 Run / Job / Provider 调用，仍恰好一个 durable task。
        assertThat(countRows("planning_run", ownerId)).isZero();
        assertThat(countRows("model_call_job", ownerId)).isZero();
        assertThat(countRows("learning_task", ownerId)).isEqualTo(1);
        verifyNoInteractions(textGenerationPort);
    }

    @Test
    void httpPostWithValidEnrichmentAppliesModelResultThroughDurableChain() throws Exception {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "en").orElseThrow();
        stubSuccessfulGeneration(VALID_ENRICHMENT_JSON);

        String body = mockMvc.perform(planningPost(profile.id(), ownerId, """
                        {"supportLanguage":"zh-CN","requestedDifficulty":"FOUNDATION","availableMinutes":10,"providerId":"deepseek"}
                        """)
                        .header(PROVIDER_CREDENTIAL_HEADER, CREDENTIAL_SECRET))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planningReason").value("MODEL_ENRICHED"))
                .andExpect(jsonPath("$.materialId").value("en-builtin-greeting-intro"))
                .andExpect(jsonPath("$.publishedVersion").value("v1"))
                .andExpect(jsonPath("$.recommendationReason").value("今天先练习问候与自我介绍。"))
                .andReturn().getResponse().getContentAsString();

        // durable 链路：Run MODEL_APPLIED + 绑定 task，Job CONSUMED，snapshot 保留 exact ordered identity。
        UUID runId = jdbcTemplate.queryForObject(
                "SELECT id FROM planning_run WHERE user_id = ?", UUID.class, ownerId);
        UUID taskId = jdbcTemplate.queryForObject(
                "SELECT learning_task_id FROM planning_run WHERE id = ?", UUID.class, runId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM planning_run WHERE id = ?", String.class, runId))
                .isEqualTo("MODEL_APPLIED");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT material_id, published_version, planning_reason, recommendation_reason"
                        + " FROM learning_task WHERE id = ?", taskId))
                .containsEntry("material_id", "en-builtin-greeting-intro")
                .containsEntry("published_version", "v1")
                .containsEntry("planning_reason", "MODEL_ENRICHED")
                .containsEntry("recommendation_reason", "今天先练习问候与自我介绍。");
        assertThat(jdbcTemplate.queryForList(
                "SELECT material_id, published_version FROM planning_run_candidate"
                        + " WHERE run_id = ? ORDER BY candidate_index", runId))
                .extracting(row -> java.util.Arrays.asList(
                        row.get("material_id"), row.get("published_version")))
                .containsExactly(
                        java.util.Arrays.asList("en-builtin-cafe-request", "v2"),
                        java.util.Arrays.asList("en-builtin-greeting-intro", "v1"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT consumption_status FROM model_call_job WHERE user_id = ?",
                String.class, ownerId))
                .isEqualTo("CONSUMED");
        // exactly-one task；Provider 恰被调用一次，route provider 为 deepseek。
        assertThat(countRows("learning_task", ownerId)).isEqualTo(1);
        verify(textGenerationPort, timeout(5_000).times(1))
                .generateText(any(TextGenerationRequest.class), any(TransientProviderCredential.class));
        // secret absence：响应与 durable result 都不包含 Credential。
        assertThat(body).doesNotContain(CREDENTIAL_SECRET);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT generated_text FROM model_call_text_generation_result WHERE job_id IN ("
                        + " SELECT id FROM model_call_job WHERE user_id = ?)",
                String.class, ownerId))
                .isEqualTo(VALID_ENRICHMENT_JSON);
    }

    @Test
    void httpPostWithModelFailureFallsBackToDeterministicTask() throws Exception {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "en").orElseThrow();
        when(textGenerationPort.generateText(
                any(TextGenerationRequest.class), any(TransientProviderCredential.class)))
                .thenReturn(ModelResult.failure(
                        ModelFailure.withoutRoute(ModelFailureKind.PROVIDER_FAILURE)));

        mockMvc.perform(planningPost(profile.id(), ownerId, """
                        {"supportLanguage":"zh-CN","requestedDifficulty":"FOUNDATION","availableMinutes":10,"providerId":"deepseek"}
                        """)
                        .header(PROVIDER_CREDENTIAL_HEADER, CREDENTIAL_SECRET))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planningReason").value("DETERMINISTIC_BUILT_IN_FALLBACK"))
                .andExpect(jsonPath("$.materialId").value("en-builtin-cafe-request"))
                .andExpect(jsonPath("$.publishedVersion").value("v2"))
                .andExpect(jsonPath("$.recommendationReason").value(org.hamcrest.Matchers.nullValue()));

        // Model failure 由 finalizer 裁决为 fallback：Run FALLBACK_APPLIED + MODEL_CALL_FAILED。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM planning_run WHERE user_id = ?", String.class, ownerId))
                .isEqualTo("FALLBACK_APPLIED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT fallback_reason FROM planning_run WHERE user_id = ?", String.class, ownerId))
                .isEqualTo("MODEL_CALL_FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT material_id FROM learning_task WHERE user_id = ?", String.class, ownerId))
                .isEqualTo("en-builtin-cafe-request");
        assertThat(countRows("learning_task", ownerId)).isEqualTo(1);
        verify(textGenerationPort, timeout(5_000).times(1))
                .generateText(any(TextGenerationRequest.class), any(TransientProviderCredential.class));
    }

    @Test
    void httpPostWithProviderMismatchFailsClosedWithoutProviderCallOrTask() throws Exception {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "en").orElseThrow();

        mockMvc.perform(planningPost(profile.id(), ownerId, """
                        {"supportLanguage":"zh-CN","requestedDifficulty":"FOUNDATION","availableMinutes":10,"providerId":"openai"}
                        """)
                        .header(PROVIDER_CREDENTIAL_HEADER, CREDENTIAL_SECRET))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PLANNING_PROVIDER_MISMATCH"));

        assertThat(countRows("planning_run", ownerId)).isZero();
        assertThat(countRows("model_call_job", ownerId)).isZero();
        assertThat(countRows("learning_task", ownerId)).isZero();
        verifyNoInteractions(textGenerationPort);
    }

    @Test
    void httpPostWithIncompleteProviderPairIsRejectedBeforeProfileUse() throws Exception {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "en").orElseThrow();

        // providerId 存在但 Credential 缺失。
        mockMvc.perform(planningPost(profile.id(), ownerId, """
                        {"supportLanguage":"zh-CN","requestedDifficulty":"FOUNDATION","availableMinutes":10,"providerId":"deepseek"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROVIDER_CREDENTIAL"));
        // Credential 存在但 providerId 缺失。
        mockMvc.perform(planningPost(profile.id(), ownerId, """
                        {"supportLanguage":"zh-CN","requestedDifficulty":"FOUNDATION","availableMinutes":10}
                        """)
                        .header(PROVIDER_CREDENTIAL_HEADER, CREDENTIAL_SECRET))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROVIDER_ID"));
        // blank 值视为"出现"而非"缺失"：both-blank 与 blank-single 都不得绕过 pair 校验。
        mockMvc.perform(planningPost(profile.id(), ownerId, """
                        {"supportLanguage":"zh-CN","requestedDifficulty":"FOUNDATION","availableMinutes":10,"providerId":""}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROVIDER_ID"));
        mockMvc.perform(planningPost(profile.id(), ownerId, """
                        {"supportLanguage":"zh-CN","requestedDifficulty":"FOUNDATION","availableMinutes":10,"providerId":"deepseek"}
                        """)
                        .header(PROVIDER_CREDENTIAL_HEADER, " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROVIDER_CREDENTIAL"));

        assertThat(countRows("planning_run", ownerId)).isZero();
        assertThat(countRows("model_call_job", ownerId)).isZero();
        assertThat(countRows("learning_task", ownerId)).isZero();
        verifyNoInteractions(textGenerationPort);
    }

    private void stubSuccessfulGeneration(String generatedJson) {
        when(textGenerationPort.generateText(
                any(TextGenerationRequest.class), any(TransientProviderCredential.class)))
                .thenReturn(ModelResult.success(new TextGenerationResponse(
                        new ProviderId("deepseek"),
                        new ModelId("deepseek-v4-flash"),
                        generatedJson,
                        TextGenerationResponse.FinishReason.COMPLETED,
                        Optional.empty())));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder planningPost(
            UUID profileId, UUID ownerId, String body) {
        return post("/api/language-profiles/" + profileId + "/learning-tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(authenticatedAs(ownerId))
                .with(csrf());
    }

    private static RequestPostProcessor authenticatedAs(UUID userId) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                new UserContext(userId), null, List.of()));
    }

    private UUID newUser() {
        UUID userId = userRepository.create();
        createdUserIds.add(userId);
        return userId;
    }

    private static LearningTaskPlanningService.PlanningCommand command(
            String supportLanguage,
            String requestedDifficulty,
            Integer availableMinutes) {
        return new LearningTaskPlanningService.PlanningCommand(
                supportLanguage, requestedDifficulty, availableMinutes);
    }

    private int countRows(String table, UUID ownerId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class, ownerId);
        return count == null ? 0 : count;
    }
}
