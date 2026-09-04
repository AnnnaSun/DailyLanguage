package com.dailylanguage.planner.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.Created;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.InvalidRequest;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.LanguageProfileNotFound;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.Unavailable;
import com.dailylanguage.planner.application.LearningTaskPlanningService;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.planner.domain.PlanningResult;
import com.dailylanguage.security.config.SecurityConfiguration;
import com.dailylanguage.security.domain.UserContext;
import com.dailylanguage.security.infrastructure.AuthenticationHttpResponseWriter;
import com.dailylanguage.security.infrastructure.PersistentSingleUser;
import com.dailylanguage.security.infrastructure.RedisAuthenticationAttemptRateLimiter;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LearningTaskPlanningController.class)
@Import({SecurityConfiguration.class, AuthenticationHttpResponseWriter.class})
class LearningTaskPlanningControllerTests {

    private static final UUID PROFILE_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000021");
    private static final UUID AUTHENTICATED_USER_ID =
            UUID.fromString("019cc10c-a56a-7000-8000-000000000022");
    private static final UUID TASK_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000023");
    private static final String ENDPOINT =
            "/api/language-profiles/" + PROFILE_ID + "/learning-tasks";
    private static final String REQUEST_BODY = """
            {"supportLanguage":"zh-CN","requestedDifficulty":"FOUNDATION","availableMinutes":10}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LearningTaskPlanningService planningService;

    @MockitoBean
    private RedisAuthenticationAttemptRateLimiter authenticationAttemptRateLimiter;

    @MockitoBean
    private PersistentSingleUser persistentSingleUser;

    @BeforeEach
    void useRegisteredUserAuthenticationMode() {
        when(persistentSingleUser.userContext()).thenReturn(Optional.empty());
    }

    @Test
    void rejectsUnauthenticatedPlanning() throws Exception {
        mockMvc.perform(planningPost().with(csrf()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(planningService);
    }

    @Test
    void missingCsrfStopsBeforePlanning() throws Exception {
        mockMvc.perform(planningPost().with(authenticatedAs(AUTHENTICATED_USER_ID)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(planningService);
    }

    @Test
    void malformedJsonReturnsFrameworkSafeBadRequest() throws Exception {
        mockMvc.perform(planningPost("{not-json").with(authenticatedAs(AUTHENTICATED_USER_ID)).with(csrf()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(planningPost("""
                        {"supportLanguage":"zh-CN","requestedDifficulty":"FOUNDATION","availableMinutes":"ten"}
                        """)
                        .with(authenticatedAs(AUTHENTICATED_USER_ID))
                        .with(csrf()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(planningService);
    }

    @Test
    void invalidRequestReturnsStableErrorCode() throws Exception {
        when(planningService.plan(eq(PROFILE_ID), any(UserContext.class), any()))
                .thenReturn(new InvalidRequest());

        mockMvc.perform(authenticatedPlanningPost())
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_PLANNING_REQUEST"));
    }

    @Test
    void inaccessibleProfileReturnsNotFoundWithoutOwnershipDisclosure() throws Exception {
        when(planningService.plan(eq(PROFILE_ID), any(UserContext.class), any()))
                .thenReturn(new LanguageProfileNotFound());

        mockMvc.perform(authenticatedPlanningPost())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LANGUAGE_PROFILE_NOT_FOUND"));
    }

    @ParameterizedTest
    @EnumSource(PlanningResult.UnavailableReason.class)
    void mapsTypedUnavailableReasonsToTheirHttpStatus(PlanningResult.UnavailableReason reason) throws Exception {
        when(planningService.plan(eq(PROFILE_ID), any(UserContext.class), any()))
                .thenReturn(new Unavailable(reason));

        int expectedStatus = switch (reason) {
            case AVAILABLE_TIME_TOO_SHORT, NO_ELIGIBLE_MATERIAL -> 422;
            case SELECTED_MATERIAL_UNAVAILABLE -> 503;
        };

        mockMvc.perform(authenticatedPlanningPost())
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(reason.name()));
    }

    @Test
    void createsLearningTaskFromTheDurableSnapshot() throws Exception {
        when(planningService.plan(eq(PROFILE_ID), any(UserContext.class), any()))
                .thenReturn(new Created(durableTask()));

        mockMvc.perform(authenticatedPlanningPost())
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location",
                        "/api/language-profiles/" + PROFILE_ID + "/learning-tasks/" + TASK_ID))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.taskId").value(TASK_ID.toString()))
                .andExpect(jsonPath("$.languageProfileId").value(PROFILE_ID.toString()))
                .andExpect(jsonPath("$.materialId").value("en-builtin-cafe-request"))
                .andExpect(jsonPath("$.publishedVersion").value("v1"))
                .andExpect(jsonPath("$.targetLanguage").value("en"))
                .andExpect(jsonPath("$.supportLanguage").value("zh-cn"))
                .andExpect(jsonPath("$.difficulty").value("FOUNDATION"))
                .andExpect(jsonPath("$.estimatedDurationMinutes").value(10))
                .andExpect(jsonPath("$.scenario").value("CAFE_SIMPLE_REQUEST"))
                .andExpect(jsonPath("$.primaryGoal").value("Order a drink and ask a follow-up question"))
                .andExpect(jsonPath("$.taskType").value("TEXT_PRACTICE"))
                .andExpect(jsonPath("$.planningReason").value("DETERMINISTIC_BUILT_IN_FALLBACK"))
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.createdAt").value("2026-09-04T10:15:30.123Z"))
                .andExpect(jsonPath("$.startedAt").value(nullValue()))
                .andExpect(jsonPath("$.completedAt").value(nullValue()))
                // ownership identity 不回传给客户端。
                .andExpect(jsonPath("$.userId").doesNotExist());
    }

    @Test
    void passesRawRequestFieldsAndIgnoresClientSuppliedUserId() throws Exception {
        UUID requestedUserId = UUID.randomUUID();
        when(planningService.plan(eq(PROFILE_ID), any(UserContext.class), any()))
                .thenReturn(new Created(durableTask()));

        mockMvc.perform(authenticatedPlanningPost()
                        .header("X-User-Id", requestedUserId)
                        .param("userId", requestedUserId.toString())
                        .content("""
                                {"userId":"%s","supportLanguage":"zh-CN","requestedDifficulty":"FOUNDATION","availableMinutes":10}
                                """.formatted(requestedUserId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").doesNotExist());

        org.mockito.ArgumentCaptor<UserContext> userContextCaptor =
                org.mockito.ArgumentCaptor.forClass(UserContext.class);
        org.mockito.ArgumentCaptor<LearningTaskPlanningService.PlanningCommand> commandCaptor =
                org.mockito.ArgumentCaptor.forClass(LearningTaskPlanningService.PlanningCommand.class);
        verify(planningService).plan(eq(PROFILE_ID), userContextCaptor.capture(), commandCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(userContextCaptor.getValue().userId())
                .isEqualTo(AUTHENTICATED_USER_ID);
        org.assertj.core.api.Assertions.assertThat(commandCaptor.getValue().supportLanguage()).isEqualTo("zh-CN");
        org.assertj.core.api.Assertions.assertThat(commandCaptor.getValue().requestedDifficulty())
                .isEqualTo("FOUNDATION");
        org.assertj.core.api.Assertions.assertThat(commandCaptor.getValue().availableMinutes()).isEqualTo(10);
    }

    @Test
    void singleUserModePlansThroughTheSameOwnershipPath() throws Exception {
        UUID singleUserId = UUID.randomUUID();
        when(persistentSingleUser.userContext()).thenReturn(Optional.of(new UserContext(singleUserId)));
        when(planningService.plan(eq(PROFILE_ID), any(UserContext.class), any()))
                .thenReturn(new Created(durableTask()));

        mockMvc.perform(planningPost().with(csrf()).param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated());

        org.mockito.ArgumentCaptor<UserContext> userContextCaptor =
                org.mockito.ArgumentCaptor.forClass(UserContext.class);
        verify(planningService).plan(eq(PROFILE_ID), userContextCaptor.capture(), any());
        org.assertj.core.api.Assertions.assertThat(userContextCaptor.getValue().userId())
                .isEqualTo(singleUserId);
    }

    @Test
    void unexpectedInfrastructureFailureIsNotMappedToABusinessResponse() {
        when(planningService.plan(eq(PROFILE_ID), any(UserContext.class), any()))
                .thenThrow(new IllegalStateException("internal database detail"));

        // 本 slice 不引入 ControllerAdvice；未处理异常直接交给容器，由 Boot 默认 error rendering
        // 输出 sanitized 5xx（include-message 默认 never），不得被捕获并伪装成业务错误码。
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> mockMvc.perform(authenticatedPlanningPost()))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    private MockHttpServletRequestBuilder planningPost(String body) {
        return post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder planningPost() {
        return planningPost(REQUEST_BODY);
    }

    private MockHttpServletRequestBuilder authenticatedPlanningPost() {
        return planningPost().with(authenticatedAs(AUTHENTICATED_USER_ID)).with(csrf());
    }

    private static RequestPostProcessor authenticatedAs(UUID userId) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                new UserContext(userId),
                null,
                List.of()
        ));
    }

    private static LearningTask durableTask() {
        return new LearningTask(
                TASK_ID,
                AUTHENTICATED_USER_ID,
                PROFILE_ID,
                new MaterialIdentity("en-builtin-cafe-request", "v1"),
                "en",
                "zh-cn",
                MaterialDifficulty.FOUNDATION,
                10,
                "CAFE_SIMPLE_REQUEST",
                "Order a drink and ask a follow-up question",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK,
                LearningTask.Status.PLANNED,
                OffsetDateTime.parse("2026-09-04T10:15:30.123Z"),
                Optional.empty(),
                Optional.empty());
    }
}
