package com.dailylanguage.evaluator.api;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.dailylanguage.evaluator.application.EvaluationResultConsumptionService.DurableOutcome;
import com.dailylanguage.evaluator.application.PracticeSessionEvaluationService;
import com.dailylanguage.evaluator.application.PracticeSessionEvaluationService.EvaluationResult;
import com.dailylanguage.evaluator.domain.EvaluationRun;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.GroundedClaim;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.ValidatedSemanticCandidate;
import com.dailylanguage.security.domain.UserContext;

/** Owner-scoped PracticeSession semantic evaluation 的 HTTP trigger 与显式 reconciliation 入口。 */
@RestController
public class EvaluationController {

    static final String PROVIDER_CREDENTIAL_HEADER = "X-Model-Provider-Credential";
    private static final String EVALUATION_PATH =
            "/api/language-profiles/{languageProfileId}/practice-sessions/{sessionId}/evaluation";
    private static final String RECONCILIATION_SUFFIX = "/reconciliation";

    private final PracticeSessionEvaluationService evaluationService;

    public EvaluationController(PracticeSessionEvaluationService evaluationService) {
        this.evaluationService = Objects.requireNonNull(
                evaluationService, "evaluationService must not be null");
    }

    @PutMapping(path = EVALUATION_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> startEvaluation(
            @PathVariable UUID languageProfileId,
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal(errorOnInvalidType = true) UserContext userContext,
            @RequestBody StartEvaluationRequest request,
            @RequestHeader(value = PROVIDER_CREDENTIAL_HEADER, required = false) String credentialSecret) {
        EvaluationResult result = evaluationService.start(
                languageProfileId,
                sessionId,
                userContext,
                request == null ? null : request.providerId(),
                credentialSecret);
        return response(result, languageProfileId, sessionId);
    }

    @PutMapping(path = EVALUATION_PATH + RECONCILIATION_SUFFIX)
    ResponseEntity<?> reconcileEvaluation(
            @PathVariable UUID languageProfileId,
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal(errorOnInvalidType = true) UserContext userContext) {
        return response(
                evaluationService.reconcile(languageProfileId, sessionId, userContext),
                languageProfileId,
                sessionId);
    }

    private static ResponseEntity<?> response(
            EvaluationResult result, UUID languageProfileId, UUID sessionId) {
        return switch (result) {
            case EvaluationResult.Pending pending -> ResponseEntity.accepted()
                    .location(reconciliationUri(languageProfileId, sessionId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(EvaluationResponse.pending(languageProfileId, pending.run()));
            case EvaluationResult.Terminal terminal -> ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(EvaluationResponse.terminal(languageProfileId, terminal.outcome()));
            case EvaluationResult.InvalidProviderId ignored ->
                    failure(HttpStatus.BAD_REQUEST, "INVALID_PROVIDER_ID");
            case EvaluationResult.InvalidProviderCredential ignored ->
                    failure(HttpStatus.BAD_REQUEST, "INVALID_PROVIDER_CREDENTIAL");
            case EvaluationResult.SessionNotFound ignored ->
                    failure(HttpStatus.NOT_FOUND, "PRACTICE_SESSION_NOT_FOUND");
            case EvaluationResult.EvaluationNotFound ignored ->
                    failure(HttpStatus.NOT_FOUND, "EVALUATION_NOT_FOUND");
            case EvaluationResult.SessionNotCompleted ignored ->
                    failure(HttpStatus.CONFLICT, "PRACTICE_SESSION_NOT_COMPLETED");
            case EvaluationResult.ProviderMismatch ignored ->
                    failure(HttpStatus.UNPROCESSABLE_ENTITY, "EVALUATION_PROVIDER_MISMATCH");
            case EvaluationResult.InputUnavailable ignored ->
                    failure(HttpStatus.SERVICE_UNAVAILABLE, "EVALUATION_INPUT_UNAVAILABLE");
            case EvaluationResult.ConfigurationUnavailable ignored ->
                    failure(HttpStatus.SERVICE_UNAVAILABLE, "EVALUATION_CONFIGURATION_UNAVAILABLE");
        };
    }

    private static ResponseEntity<EvaluationErrorResponse> failure(HttpStatus status, String code) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EvaluationErrorResponse(code));
    }

    private static URI reconciliationUri(UUID languageProfileId, UUID sessionId) {
        return URI.create("/api/language-profiles/" + languageProfileId
                + "/practice-sessions/" + sessionId + "/evaluation/reconciliation");
    }

    record StartEvaluationRequest(String providerId) {
    }

    record EvaluationErrorResponse(String code) {
    }

    /** 只投影 durable、安全的 Evaluation outcome；不返回 Job、Credential、Prompt 或 raw Model output。 */
    record EvaluationResponse(
            UUID evaluationRunId,
            UUID languageProfileId,
            UUID sessionId,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt,
            String failureReason,
            String groundingRejectionReason,
            SemanticResultResponse semanticResult) {

        static EvaluationResponse pending(UUID languageProfileId, EvaluationRun run) {
            return from(languageProfileId, run, null);
        }

        static EvaluationResponse terminal(UUID languageProfileId, DurableOutcome outcome) {
            SemanticResultResponse semanticResult = outcome.groundingResult()
                    .filter(SemanticGroundingResult.Validated.class::isInstance)
                    .map(SemanticGroundingResult.Validated.class::cast)
                    .map(validated -> SemanticResultResponse.from(validated.candidate()))
                    .orElse(null);
            return from(languageProfileId, outcome.run(), semanticResult);
        }

        private static EvaluationResponse from(
                UUID languageProfileId, EvaluationRun run, SemanticResultResponse semanticResult) {
            return new EvaluationResponse(
                    run.id(),
                    languageProfileId,
                    run.sessionId(),
                    run.status().name(),
                    run.createdAt(),
                    run.completedAt().orElse(null),
                    run.failureReason().map(Enum::name).orElse(null),
                    run.groundingRejectionReason().map(Enum::name).orElse(null),
                    semanticResult);
        }
    }

    record SemanticResultResponse(
            String materialId,
            String publishedVersion,
            String rubricReference,
            String targetLanguage,
            String groundingPolicyVersion,
            List<ClaimResponse> claims) {

        static SemanticResultResponse from(ValidatedSemanticCandidate candidate) {
            return new SemanticResultResponse(
                    candidate.materialIdentity().materialId(),
                    candidate.materialIdentity().publishedVersion(),
                    candidate.rubricReference(),
                    candidate.targetLanguage(),
                    candidate.groundingPolicyVersion(),
                    candidate.claims().stream().map(ClaimResponse::from).toList());
        }
    }

    record ClaimResponse(
            String sourceTurnId,
            String exactQuote,
            int occurrenceIndex,
            int startOffset,
            int endOffset,
            String issueType,
            String explanation,
            double confidence) {

        static ClaimResponse from(GroundedClaim claim) {
            return new ClaimResponse(
                    claim.sourceTurnId(),
                    claim.exactQuote(),
                    claim.occurrenceIndex(),
                    claim.startOffset(),
                    claim.endOffset(),
                    claim.issueType().name(),
                    claim.explanation(),
                    claim.confidence());
        }
    }
}
