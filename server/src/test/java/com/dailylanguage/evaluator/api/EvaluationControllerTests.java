package com.dailylanguage.evaluator.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.evaluator.application.EvaluationResultConsumptionService.DurableOutcome;
import com.dailylanguage.evaluator.application.PracticeSessionEvaluationService;
import com.dailylanguage.evaluator.application.PracticeSessionEvaluationService.EvaluationResult;
import com.dailylanguage.evaluator.domain.EvaluationRun;
import com.dailylanguage.evaluator.domain.SemanticEvaluationOutput.IssueType;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.GroundedClaim;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.RejectionReason;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.ValidatedSemanticCandidate;
import com.dailylanguage.security.config.SecurityConfiguration;
import com.dailylanguage.security.domain.UserContext;
import com.dailylanguage.security.infrastructure.AuthenticationHttpResponseWriter;
import com.dailylanguage.security.infrastructure.PersistentSingleUser;
import com.dailylanguage.security.infrastructure.RedisAuthenticationAttemptRateLimiter;

@WebMvcTest(EvaluationController.class)
@Import({SecurityConfiguration.class, AuthenticationHttpResponseWriter.class})
class EvaluationControllerTests {

    private static final UUID PROFILE_ID = UUID.fromString("019d0220-1111-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("019d0220-1111-7000-8000-000000000002");
    private static final UUID SESSION_ID = UUID.fromString("019d0220-1111-7000-8000-000000000003");
    private static final UUID RUN_ID = UUID.fromString("019d0220-1111-7000-8000-000000000004");
    private static final UUID JOB_ID = UUID.fromString("019d0220-1111-7000-8000-000000000005");
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-08T08:00:00Z");
    private static final OffsetDateTime COMPLETED_AT = OffsetDateTime.parse("2026-09-08T08:00:03Z");
    private static final String CREDENTIAL = "browser-only-secret";
    private static final String EVALUATION_ENDPOINT = "/api/language-profiles/" + PROFILE_ID
            + "/practice-sessions/" + SESSION_ID + "/evaluation";
    private static final String RECONCILIATION_ENDPOINT = EVALUATION_ENDPOINT + "/reconciliation";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PracticeSessionEvaluationService evaluationService;

    @MockitoBean
    private RedisAuthenticationAttemptRateLimiter authenticationAttemptRateLimiter;

    @MockitoBean
    private PersistentSingleUser persistentSingleUser;

    @BeforeEach
    void useRegisteredUserAuthenticationMode() {
        when(persistentSingleUser.userContext()).thenReturn(Optional.empty());
    }

    @Test
    void authenticationAndCsrfProtectBothMutationEndpoints() throws Exception {
        mockMvc.perform(startPut().with(csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(reconcilePut().with(csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(startPut().with(authenticatedUser())).andExpect(status().isForbidden());
        mockMvc.perform(reconcilePut().with(authenticatedUser())).andExpect(status().isForbidden());

        verifyNoInteractions(evaluationService);
    }

    @Test
    void startPassesAuthenticatedIdentityAndTransientCredentialThenReturnsPending() throws Exception {
        when(evaluationService.start(
                eq(PROFILE_ID), eq(SESSION_ID), any(UserContext.class), eq("deepseek"), eq(CREDENTIAL)))
                .thenReturn(new EvaluationResult.Pending(pendingRun()));

        mockMvc.perform(authenticatedStartPut())
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", RECONCILIATION_ENDPOINT))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.evaluationRunId").value(RUN_ID.toString()))
                .andExpect(jsonPath("$.languageProfileId").value(PROFILE_ID.toString()))
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").value("2026-09-08T08:00:00Z"))
                .andExpect(jsonPath("$.completedAt").value((Object) null))
                .andExpect(jsonPath("$.failureReason").value((Object) null))
                .andExpect(jsonPath("$.semanticResult").value((Object) null))
                .andExpect(content().string(not(containsString(CREDENTIAL))))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.modelCallJobId").doesNotExist())
                .andExpect(jsonPath("$.workflowVersion").doesNotExist())
                .andExpect(jsonPath("$.rowVersion").doesNotExist());

        var context = org.mockito.ArgumentCaptor.forClass(UserContext.class);
        verify(evaluationService).start(
                eq(PROFILE_ID), eq(SESSION_ID), context.capture(), eq("deepseek"), eq(CREDENTIAL));
        org.assertj.core.api.Assertions.assertThat(context.getValue().userId()).isEqualTo(USER_ID);
    }

    @Test
    void reconciliationReturnsValidatedTerminalProjectionWithoutCredential() throws Exception {
        when(evaluationService.reconcile(eq(PROFILE_ID), eq(SESSION_ID), any(UserContext.class)))
                .thenReturn(new EvaluationResult.Terminal(validatedOutcome()));

        mockMvc.perform(authenticatedReconcilePut())
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.completedAt").value(COMPLETED_AT.toString()))
                .andExpect(jsonPath("$.failureReason").value((Object) null))
                .andExpect(jsonPath("$.semanticResult.materialId").value("en-cafe"))
                .andExpect(jsonPath("$.semanticResult.publishedVersion").value("v1"))
                .andExpect(jsonPath("$.semanticResult.rubricReference").value("rubric/v1"))
                .andExpect(jsonPath("$.semanticResult.targetLanguage").value("en"))
                .andExpect(jsonPath("$.semanticResult.groundingPolicyVersion").value("GROUNDING_V1"))
                .andExpect(jsonPath("$.semanticResult.claims[0].sourceTurnId").value("turn-1"))
                .andExpect(jsonPath("$.semanticResult.claims[0].exactQuote").value("I goed"))
                .andExpect(jsonPath("$.semanticResult.claims[0].occurrenceIndex").value(0))
                .andExpect(jsonPath("$.semanticResult.claims[0].startOffset").value(0))
                .andExpect(jsonPath("$.semanticResult.claims[0].endOffset").value(6))
                .andExpect(jsonPath("$.semanticResult.claims[0].issueType").value("GRAMMAR"))
                .andExpect(jsonPath("$.semanticResult.claims[0].explanation").value("Use went."))
                .andExpect(jsonPath("$.semanticResult.claims[0].confidence").value(0.9));

        verify(evaluationService).reconcile(eq(PROFILE_ID), eq(SESSION_ID), any(UserContext.class));
    }

    @Test
    void durableEvaluationFailureIsACompletedBusinessResponse() throws Exception {
        EvaluationRun failed = terminalRun(
                EvaluationRun.Status.FAILED,
                Optional.of(EvaluationRun.FailureReason.GROUNDING_REJECTED),
                Optional.of(RejectionReason.QUOTE_MISMATCH));
        DurableOutcome outcome = new DurableOutcome(
                failed, Optional.of(new SemanticGroundingResult.Rejected(RejectionReason.QUOTE_MISMATCH)));
        when(evaluationService.reconcile(eq(PROFILE_ID), eq(SESSION_ID), any(UserContext.class)))
                .thenReturn(new EvaluationResult.Terminal(outcome));

        mockMvc.perform(authenticatedReconcilePut())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("GROUNDING_REJECTED"))
                .andExpect(jsonPath("$.groundingRejectionReason").value("QUOTE_MISMATCH"))
                .andExpect(jsonPath("$.semanticResult").value((Object) null));
    }

    @ParameterizedTest
    @MethodSource("failureMappings")
    void mapsStableFailures(EvaluationResult result, int expectedStatus, String code) throws Exception {
        when(evaluationService.start(
                eq(PROFILE_ID), eq(SESSION_ID), any(UserContext.class), eq("deepseek"), eq(CREDENTIAL)))
                .thenReturn(result);

        mockMvc.perform(authenticatedStartPut())
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code));
    }

    @Test
    void malformedJsonStopsBeforeApplicationService() throws Exception {
        mockMvc.perform(put(EVALUATION_ENDPOINT)
                        .with(authenticatedUser()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(EvaluationController.PROVIDER_CREDENTIAL_HEADER, CREDENTIAL)
                        .content("{not-json"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(evaluationService);
    }

    @Test
    void unexpectedFailureIsNotConvertedIntoABusinessResponse() {
        when(evaluationService.reconcile(eq(PROFILE_ID), eq(SESSION_ID), any(UserContext.class)))
                .thenThrow(new IllegalStateException("internal persistence detail"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> mockMvc.perform(authenticatedReconcilePut()))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    private static Stream<Arguments> failureMappings() {
        return Stream.of(
                Arguments.of(new EvaluationResult.InvalidProviderId(), 400, "INVALID_PROVIDER_ID"),
                Arguments.of(new EvaluationResult.InvalidProviderCredential(), 400,
                        "INVALID_PROVIDER_CREDENTIAL"),
                Arguments.of(new EvaluationResult.SessionNotFound(), 404, "PRACTICE_SESSION_NOT_FOUND"),
                Arguments.of(new EvaluationResult.EvaluationNotFound(), 404, "EVALUATION_NOT_FOUND"),
                Arguments.of(new EvaluationResult.SessionNotCompleted(), 409,
                        "PRACTICE_SESSION_NOT_COMPLETED"),
                Arguments.of(new EvaluationResult.ProviderMismatch(), 422,
                        "EVALUATION_PROVIDER_MISMATCH"),
                Arguments.of(new EvaluationResult.InputUnavailable(), 503,
                        "EVALUATION_INPUT_UNAVAILABLE"),
                Arguments.of(new EvaluationResult.ConfigurationUnavailable(), 503,
                        "EVALUATION_CONFIGURATION_UNAVAILABLE"));
    }

    private MockHttpServletRequestBuilder startPut() {
        return put(EVALUATION_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .header(EvaluationController.PROVIDER_CREDENTIAL_HEADER, CREDENTIAL)
                .content("{\"providerId\":\"deepseek\"}");
    }

    private MockHttpServletRequestBuilder authenticatedStartPut() {
        return startPut().with(authenticatedUser()).with(csrf());
    }

    private MockHttpServletRequestBuilder reconcilePut() {
        return put(RECONCILIATION_ENDPOINT);
    }

    private MockHttpServletRequestBuilder authenticatedReconcilePut() {
        return reconcilePut().with(authenticatedUser()).with(csrf());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor authenticatedUser() {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                new UserContext(USER_ID), null, List.of()));
    }

    private static EvaluationRun pendingRun() {
        return new EvaluationRun(
                RUN_ID, SESSION_ID, JOB_ID, EvaluationRun.Status.PENDING,
                EvaluationRun.CURRENT_WORKFLOW_VERSION, 0L, CREATED_AT,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static DurableOutcome validatedOutcome() {
        ValidatedSemanticCandidate candidate = new ValidatedSemanticCandidate(
                PROFILE_ID,
                SESSION_ID,
                new MaterialIdentity("en-cafe", "v1"),
                "rubric/v1",
                "en",
                "GROUNDING_V1",
                List.of(new GroundedClaim(
                        "turn-1", "I goed", 0, 0, 6,
                        IssueType.GRAMMAR, "Use went.", 0.9)));
        return new DurableOutcome(
                terminalRun(EvaluationRun.Status.SUCCEEDED, Optional.empty(), Optional.empty()),
                Optional.of(new SemanticGroundingResult.Validated(candidate)));
    }

    private static EvaluationRun terminalRun(
            EvaluationRun.Status status,
            Optional<EvaluationRun.FailureReason> failureReason,
            Optional<RejectionReason> rejectionReason) {
        return new EvaluationRun(
                RUN_ID, SESSION_ID, JOB_ID, status,
                EvaluationRun.CURRENT_WORKFLOW_VERSION, 1L, CREATED_AT,
                Optional.of(COMPLETED_AT), failureReason, rejectionReason);
    }
}
