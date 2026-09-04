package com.dailylanguage.practice.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.practice.application.PracticeSessionApplicationService;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.CompletionResult;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.PracticeMaterialView;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.StartResult;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.SubmitResult;
import com.dailylanguage.practice.domain.DeterministicAssessment;
import com.dailylanguage.practice.domain.DeterministicAssessment.StepKind;
import com.dailylanguage.practice.domain.DeterministicAssessment.StepOutcome;
import com.dailylanguage.practice.domain.DeterministicAssessment.StepResult;
import com.dailylanguage.practice.domain.DeterministicTextAssessmentPolicy;
import com.dailylanguage.practice.domain.PracticeSession;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PracticeSessionController.class)
@Import({SecurityConfiguration.class, AuthenticationHttpResponseWriter.class})
class PracticeSessionControllerTests {

    private static final UUID PROFILE_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000051");
    private static final UUID AUTHENTICATED_USER_ID =
            UUID.fromString("019cc10c-a56a-7000-8000-000000000052");
    private static final UUID TASK_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000053");
    private static final UUID SESSION_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000054");
    private static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-09-04T10:15:30.123Z");
    private static final OffsetDateTime SUBMITTED_AT = OffsetDateTime.parse("2026-09-04T10:18:00.456Z");
    private static final OffsetDateTime COMPLETED_AT = OffsetDateTime.parse("2026-09-04T10:22:00.123Z");
    private static final String START_ENDPOINT =
            "/api/language-profiles/" + PROFILE_ID + "/learning-tasks/" + TASK_ID + "/practice-sessions";
    private static final String RESPONSE_ENDPOINT =
            "/api/language-profiles/" + PROFILE_ID + "/practice-sessions/" + SESSION_ID
                    + "/responses/order-drink";
    private static final String COMPLETION_ENDPOINT =
            "/api/language-profiles/" + PROFILE_ID + "/practice-sessions/" + SESSION_ID + "/completion";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PracticeSessionApplicationService practiceSessionApplicationService;

    @MockitoBean
    private RedisAuthenticationAttemptRateLimiter authenticationAttemptRateLimiter;

    @MockitoBean
    private PersistentSingleUser persistentSingleUser;

    @BeforeEach
    void useRegisteredUserAuthenticationMode() {
        when(persistentSingleUser.userContext()).thenReturn(Optional.empty());
    }

    @Test
    void rejectsUnauthenticatedStartAndResponseSubmission() throws Exception {
        mockMvc.perform(startPost().with(csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(responsePut().with(csrf())).andExpect(status().isUnauthorized());

        verifyNoInteractions(practiceSessionApplicationService);
    }

    @Test
    void missingCsrfStopsBeforeApplicationService() throws Exception {
        mockMvc.perform(startPost().with(authenticatedAs(AUTHENTICATED_USER_ID)))
                .andExpect(status().isForbidden());
        mockMvc.perform(responsePut().with(authenticatedAs(AUTHENTICATED_USER_ID)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(practiceSessionApplicationService);
    }

    @Test
    void malformedJsonReturnsFrameworkSafeBadRequest() throws Exception {
        mockMvc.perform(responsePut("{not-json")
                        .with(authenticatedAs(AUTHENTICATED_USER_ID)).with(csrf()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(responsePut("{\"learnerText\":{\"nested\":\"object\"}}")
                        .with(authenticatedAs(AUTHENTICATED_USER_ID)).with(csrf()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(practiceSessionApplicationService);
    }

    @Test
    void createdStartReturnsLocationAndSafeMaterialProjection() throws Exception {
        when(practiceSessionApplicationService.start(eq(PROFILE_ID), eq(TASK_ID), any(UserContext.class)))
                .thenReturn(new StartResult.Created(inProgressSession(), cafeMaterialView()));

        mockMvc.perform(authenticatedStartPost())
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location",
                        "/api/language-profiles/" + PROFILE_ID + "/practice-sessions/" + SESSION_ID))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.taskId").value(TASK_ID.toString()))
                .andExpect(jsonPath("$.languageProfileId").value(PROFILE_ID.toString()))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.startedAt").value("2026-09-04T10:15:30.123Z"))
                .andExpect(jsonPath("$.material.materialId").value("en-builtin-cafe-request"))
                .andExpect(jsonPath("$.material.publishedVersion").value("v1"))
                .andExpect(jsonPath("$.material.targetLanguage").value("en"))
                .andExpect(jsonPath("$.material.supportLanguage").value("zh-cn"))
                .andExpect(jsonPath("$.material.scenario").value("CAFE_SIMPLE_REQUEST"))
                .andExpect(jsonPath("$.material.instruction").value("完成点单的中文指令"))
                .andExpect(jsonPath("$.material.readingInfo").value(nullValue()))
                .andExpect(jsonPath("$.material.steps[0].stepId").value("order-drink"))
                .andExpect(jsonPath("$.material.steps[0].kind").value("EXACT"))
                .andExpect(jsonPath("$.material.steps[0].prompt").value("Order a medium coffee politely."))
                // 安全 projection：不下发 ownership identity、accepted answers 或 rubric 引用。
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.material.semanticRubricReference").doesNotExist())
                .andExpect(jsonPath("$.material.sourceLineage").doesNotExist())
                .andExpect(jsonPath("$.material.steps[0].acceptedAnswers").doesNotExist());
    }

    @Test
    void existingStartReplaysTheSameSessionWithoutLocation() throws Exception {
        when(practiceSessionApplicationService.start(eq(PROFILE_ID), eq(TASK_ID), any(UserContext.class)))
                .thenReturn(new StartResult.Existing(inProgressSession(), cafeMaterialView()));

        mockMvc.perform(authenticatedStartPost())
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void mapsStartFailuresToTheirStableCodes() throws Exception {
        arrangeStartFailure(new StartResult.TaskNotFound(), 404, "LEARNING_TASK_NOT_FOUND");
        arrangeStartFailure(new StartResult.TaskNotStartable(), 409, "LEARNING_TASK_NOT_STARTABLE");
        arrangeStartFailure(new StartResult.MaterialUnavailable(), 503, "PRACTICE_MATERIAL_UNAVAILABLE");
    }

    @Test
    void acceptedResponseReturnsCreatedWithSubmittedAt() throws Exception {
        when(practiceSessionApplicationService.submit(
                eq(PROFILE_ID), eq(SESSION_ID), eq("order-drink"), any(UserContext.class),
                eq("Could I have a medium coffee, please?")))
                .thenReturn(new SubmitResult.Accepted(new PracticeSession.LearnerResponse(
                        SESSION_ID, "order-drink", "Could I have a medium coffee, please?", SUBMITTED_AT)));

        mockMvc.perform(authenticatedResponsePut(
                "{\"learnerText\":\"Could I have a medium coffee, please?\"}"))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.stepId").value("order-drink"))
                .andExpect(jsonPath("$.submittedAt").value("2026-09-04T10:18:00.456Z"))
                .andExpect(jsonPath("$.userId").doesNotExist());
    }

    @Test
    void replayedResponseReturnsTheFirstSubmittedAt() throws Exception {
        when(practiceSessionApplicationService.submit(
                eq(PROFILE_ID), eq(SESSION_ID), eq("order-drink"), any(UserContext.class), any()))
                .thenReturn(new SubmitResult.Replayed(new PracticeSession.LearnerResponse(
                        SESSION_ID, "order-drink", "Could I have a medium coffee, please?", SUBMITTED_AT)));

        mockMvc.perform(authenticatedResponsePut(
                "{\"learnerText\":\"Could I have a medium coffee, please?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submittedAt").value("2026-09-04T10:18:00.456Z"));
    }

    @Test
    void mapsResponseFailuresToTheirStableCodes() throws Exception {
        arrangeSubmitFailure(new SubmitResult.InvalidResponse(), 400, "INVALID_PRACTICE_RESPONSE");
        arrangeSubmitFailure(new SubmitResult.SessionNotFound(), 404, "PRACTICE_SESSION_NOT_FOUND");
        arrangeSubmitFailure(new SubmitResult.StepNotFound(), 404, "PRACTICE_STEP_NOT_FOUND");
        arrangeSubmitFailure(
                new SubmitResult.SessionNotAcceptingResponses(), 409,
                "PRACTICE_SESSION_NOT_ACCEPTING_RESPONSES");
        arrangeSubmitFailure(new SubmitResult.ResponseConflict(), 409, "PRACTICE_RESPONSE_CONFLICT");
        arrangeSubmitFailure(new SubmitResult.MaterialUnavailable(), 503, "PRACTICE_MATERIAL_UNAVAILABLE");
    }

    @Test
    void ignoresClientSuppliedUserIdInFavorOfTheAuthenticatedContext() throws Exception {
        UUID requestedUserId = UUID.randomUUID();
        when(practiceSessionApplicationService.start(eq(PROFILE_ID), eq(TASK_ID), any(UserContext.class)))
                .thenReturn(new StartResult.Created(inProgressSession(), cafeMaterialView()));
        when(practiceSessionApplicationService.submit(
                eq(PROFILE_ID), eq(SESSION_ID), eq("order-drink"), any(UserContext.class), any()))
                .thenReturn(new SubmitResult.Replayed(new PracticeSession.LearnerResponse(
                        SESSION_ID, "order-drink", "text", SUBMITTED_AT)));

        mockMvc.perform(authenticatedStartPost()
                        .header("X-User-Id", requestedUserId)
                        .param("userId", requestedUserId.toString()))
                .andExpect(status().isCreated());
        mockMvc.perform(authenticatedResponsePut(
                        "{\"userId\":\"" + requestedUserId + "\",\"learnerText\":\"text\"}")
                        .header("X-User-Id", requestedUserId))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<UserContext> startContext =
                org.mockito.ArgumentCaptor.forClass(UserContext.class);
        verify(practiceSessionApplicationService).start(eq(PROFILE_ID), eq(TASK_ID), startContext.capture());
        org.mockito.ArgumentCaptor<UserContext> submitContext =
                org.mockito.ArgumentCaptor.forClass(UserContext.class);
        verify(practiceSessionApplicationService)
                .submit(eq(PROFILE_ID), eq(SESSION_ID), eq("order-drink"), submitContext.capture(), any());
        org.assertj.core.api.Assertions.assertThat(startContext.getValue().userId())
                .isEqualTo(AUTHENTICATED_USER_ID);
        org.assertj.core.api.Assertions.assertThat(submitContext.getValue().userId())
                .isEqualTo(AUTHENTICATED_USER_ID);
    }

    @Test
    void singleUserModeUsesTheSameOwnershipPath() throws Exception {
        UUID singleUserId = UUID.randomUUID();
        when(persistentSingleUser.userContext()).thenReturn(Optional.of(new UserContext(singleUserId)));
        when(practiceSessionApplicationService.start(eq(PROFILE_ID), eq(TASK_ID), any(UserContext.class)))
                .thenReturn(new StartResult.Created(inProgressSession(), cafeMaterialView()));

        mockMvc.perform(startPost().with(csrf()).param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated());

        org.mockito.ArgumentCaptor<UserContext> userContextCaptor =
                org.mockito.ArgumentCaptor.forClass(UserContext.class);
        verify(practiceSessionApplicationService).start(eq(PROFILE_ID), eq(TASK_ID), userContextCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(userContextCaptor.getValue().userId())
                .isEqualTo(singleUserId);
    }

    @Test
    void unexpectedInfrastructureFailureIsNotMappedToABusinessResponse() {
        when(practiceSessionApplicationService.start(eq(PROFILE_ID), eq(TASK_ID), any(UserContext.class)))
                .thenThrow(new IllegalStateException("internal database detail"));

        // 本 slice 不引入 ControllerAdvice；未处理异常交给容器输出 sanitized 5xx（include-message
        // 默认 never），不得被捕获并伪装成业务错误码。
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> mockMvc.perform(authenticatedStartPost()))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    // --- completion ---

    @Test
    void rejectsUnauthenticatedCompletion() throws Exception {
        mockMvc.perform(put(COMPLETION_ENDPOINT).with(csrf()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(practiceSessionApplicationService);
    }

    @Test
    void missingCsrfStopsCompletionBeforeApplicationService() throws Exception {
        mockMvc.perform(put(COMPLETION_ENDPOINT).with(authenticatedAs(AUTHENTICATED_USER_ID)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(practiceSessionApplicationService);
    }

    @Test
    void firstCompletionReturnsCreatedWithLocationAndDerivedStatistics() throws Exception {
        when(practiceSessionApplicationService.complete(eq(PROFILE_ID), eq(SESSION_ID), any(UserContext.class)))
                .thenReturn(new CompletionResult.Created(
                        completedSession(), completedTask(), completedAssessment()));

        mockMvc.perform(put(COMPLETION_ENDPOINT)
                        .with(authenticatedAs(AUTHENTICATED_USER_ID)).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", COMPLETION_ENDPOINT))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.assessmentPolicyVersion").value("M1_TEXT_EXACT_V1"))
                .andExpect(jsonPath("$.durationSeconds").value(390))
                .andExpect(jsonPath("$.createdAt").value("2026-09-04T10:22:00.123Z"))
                .andExpect(jsonPath("$.totalStepCount").value(2))
                .andExpect(jsonPath("$.answeredStepCount").value(2))
                .andExpect(jsonPath("$.exactMatchedCount").value(1))
                .andExpect(jsonPath("$.exactNotMatchedCount").value(0))
                .andExpect(jsonPath("$.semanticOnlyCount").value(1))
                .andExpect(jsonPath("$.sessionStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.taskStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.startedAt").value("2026-09-04T10:15:30.123Z"))
                .andExpect(jsonPath("$.completedAt").value("2026-09-04T10:22:00.123Z"))
                // 安全 projection：不下发 userId、learner text、accepted answers 或 rubric 引用。
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.learnerText").doesNotExist())
                .andExpect(jsonPath("$.acceptedAnswers").doesNotExist());
    }

    @Test
    void completedReplayReturnsTheSameDurableResultWithoutLocation() throws Exception {
        when(practiceSessionApplicationService.complete(eq(PROFILE_ID), eq(SESSION_ID), any(UserContext.class)))
                .thenReturn(new CompletionResult.Replayed(
                        completedSession(), completedTask(), completedAssessment()));

        mockMvc.perform(put(COMPLETION_ENDPOINT)
                        .with(authenticatedAs(AUTHENTICATED_USER_ID)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.assessmentPolicyVersion").value("M1_TEXT_EXACT_V1"))
                .andExpect(jsonPath("$.sessionStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.taskStatus").value("COMPLETED"));
    }

    @Test
    void mapsCompletionFailuresToTheirStableCodes() throws Exception {
        arrangeCompletionFailure(new CompletionResult.SessionNotFound(), 404, "PRACTICE_SESSION_NOT_FOUND");
        arrangeCompletionFailure(new CompletionResult.Incomplete(), 409, "PRACTICE_SESSION_INCOMPLETE");
        arrangeCompletionFailure(
                new CompletionResult.NotCompletable(), 409, "PRACTICE_SESSION_NOT_COMPLETABLE");
        arrangeCompletionFailure(
                new CompletionResult.MaterialUnavailable(), 503, "PRACTICE_MATERIAL_UNAVAILABLE");
    }

    @Test
    void ignoresClientSuppliedUserIdForCompletion() throws Exception {
        UUID requestedUserId = UUID.randomUUID();
        when(practiceSessionApplicationService.complete(eq(PROFILE_ID), eq(SESSION_ID), any(UserContext.class)))
                .thenReturn(new CompletionResult.Replayed(
                        completedSession(), completedTask(), completedAssessment()));

        mockMvc.perform(put(COMPLETION_ENDPOINT)
                        .with(authenticatedAs(AUTHENTICATED_USER_ID)).with(csrf())
                        .header("X-User-Id", requestedUserId.toString())
                        .queryParam("userId", requestedUserId.toString()))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<UserContext> completionContext =
                org.mockito.ArgumentCaptor.forClass(UserContext.class);
        verify(practiceSessionApplicationService)
                .complete(eq(PROFILE_ID), eq(SESSION_ID), completionContext.capture());
        org.assertj.core.api.Assertions.assertThat(completionContext.getValue().userId())
                .isEqualTo(AUTHENTICATED_USER_ID);
    }

    @Test
    void unexpectedCompletionFailureIsNotMappedToABusinessResponse() {
        when(practiceSessionApplicationService.complete(eq(PROFILE_ID), eq(SESSION_ID), any(UserContext.class)))
                .thenThrow(new IllegalStateException("internal database detail"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> mockMvc.perform(put(COMPLETION_ENDPOINT)
                                .with(authenticatedAs(AUTHENTICATED_USER_ID)).with(csrf())))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    private void arrangeCompletionFailure(CompletionResult result, int status, String code) throws Exception {
        when(practiceSessionApplicationService.complete(eq(PROFILE_ID), eq(SESSION_ID), any(UserContext.class)))
                .thenReturn(result);

        mockMvc.perform(put(COMPLETION_ENDPOINT)
                        .with(authenticatedAs(AUTHENTICATED_USER_ID)).with(csrf()))
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code));
    }

    private void arrangeStartFailure(StartResult result, int status, String code) throws Exception {
        when(practiceSessionApplicationService.start(eq(PROFILE_ID), eq(TASK_ID), any(UserContext.class)))
                .thenReturn(result);

        mockMvc.perform(authenticatedStartPost())
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code));
    }

    private void arrangeSubmitFailure(SubmitResult result, int status, String code) throws Exception {
        when(practiceSessionApplicationService.submit(
                eq(PROFILE_ID), eq(SESSION_ID), eq("order-drink"), any(UserContext.class), any()))
                .thenReturn(result);

        mockMvc.perform(authenticatedResponsePut("{\"learnerText\":\"text\"}"))
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code));
    }

    private MockHttpServletRequestBuilder startPost() {
        return post(START_ENDPOINT);
    }

    private MockHttpServletRequestBuilder responsePut() {
        return responsePut("{\"learnerText\":\"text\"}");
    }

    private MockHttpServletRequestBuilder responsePut(String body) {
        return put(RESPONSE_ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder authenticatedStartPost() {
        return startPost().with(authenticatedAs(AUTHENTICATED_USER_ID)).with(csrf());
    }

    private MockHttpServletRequestBuilder authenticatedResponsePut(String body) {
        return responsePut(body).with(authenticatedAs(AUTHENTICATED_USER_ID)).with(csrf());
    }

    private static RequestPostProcessor authenticatedAs(UUID userId) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                new UserContext(userId),
                null,
                List.of()
        ));
    }

    private static PracticeSession inProgressSession() {
        return new PracticeSession(
                SESSION_ID, TASK_ID, PracticeSession.Status.IN_PROGRESS, STARTED_AT,
                Optional.empty(), Optional.empty());
    }

    private static PracticeSession completedSession() {
        return new PracticeSession(
                SESSION_ID, TASK_ID, PracticeSession.Status.COMPLETED, STARTED_AT,
                Optional.of(COMPLETED_AT), Optional.empty());
    }

    private static LearningTask completedTask() {
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
                "Make a polite request, ask about price, and answer a follow-up question in a coffee shop.",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK,
                LearningTask.Status.COMPLETED,
                OffsetDateTime.parse("2026-09-04T10:10:00.000Z"),
                Optional.of(STARTED_AT),
                Optional.of(COMPLETED_AT));
    }

    private static DeterministicAssessment completedAssessment() {
        return new DeterministicAssessment(
                SESSION_ID,
                DeterministicTextAssessmentPolicy.ASSESSMENT_POLICY_VERSION,
                390L,
                COMPLETED_AT,
                List.of(
                        new StepResult("order-drink", StepKind.EXACT, StepOutcome.MATCHED),
                        new StepResult("answer-to-go", StepKind.SEMANTIC_ONLY, StepOutcome.NOT_APPLICABLE)));
    }

    private static PracticeMaterialView cafeMaterialView() {
        return new PracticeMaterialView(
                "en-builtin-cafe-request",
                "v1",
                "en",
                "zh-cn",
                "CAFE_SIMPLE_REQUEST",
                "Make a polite request, ask about price, and answer a follow-up question.",
                "You are at a coffee shop.",
                null,
                "完成点单的中文指令",
                "场景解释",
                "提示",
                "对比提示",
                List.of(new PracticeMaterialView.StepView(
                        "order-drink", "EXACT", "Order a medium coffee politely.")));
    }
}
