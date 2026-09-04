package com.dailylanguage.practice.api;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.dailylanguage.practice.application.PracticeSessionApplicationService;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.CompletionResult;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.PracticeMaterialView;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.StartResult;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.SubmitResult;
import com.dailylanguage.practice.domain.DeterministicAssessment;
import com.dailylanguage.practice.domain.PracticeSession;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.security.domain.UserContext;

/**
 * PracticeSession lifecycle 的 HTTP 入口。资源归属只接受 Spring Security 建立的 UserContext；
 * request body、query parameter 或 header 中的 userId 不参与授权判断。成功响应来自数据库裁决后的
 * durable Session / response / assessment，material 只下发安全 projection，completion 只下发
 * deterministic 统计，不回传 userId、learnerText、acceptedAnswers 或 rubric 引用。
 */
@RestController
public class PracticeSessionController {

    private final PracticeSessionApplicationService practiceSessionApplicationService;

    public PracticeSessionController(
            PracticeSessionApplicationService practiceSessionApplicationService) {
        this.practiceSessionApplicationService = practiceSessionApplicationService;
    }

    @PostMapping(path = "/api/language-profiles/{languageProfileId}/learning-tasks/{taskId}/practice-sessions")
    ResponseEntity<?> startPracticeSession(
            @PathVariable UUID languageProfileId,
            @PathVariable UUID taskId,
            @AuthenticationPrincipal(errorOnInvalidType = true) UserContext userContext) {
        StartResult result = practiceSessionApplicationService.start(
                languageProfileId, taskId, userContext);

        return switch (result) {
            case StartResult.TaskNotFound ignored ->
                    practiceFailure(HttpStatus.NOT_FOUND, "LEARNING_TASK_NOT_FOUND");
            case StartResult.TaskNotStartable ignored ->
                    practiceFailure(HttpStatus.CONFLICT, "LEARNING_TASK_NOT_STARTABLE");
            case StartResult.MaterialUnavailable ignored ->
                    practiceFailure(HttpStatus.SERVICE_UNAVAILABLE, "PRACTICE_MATERIAL_UNAVAILABLE");
            case StartResult.Existing existing ->
                    ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(StartSessionResponse.from(existing.session(), languageProfileId, existing.material()));
            case StartResult.Created created ->
                    ResponseEntity
                            .created(URI.create("/api/language-profiles/" + languageProfileId
                                    + "/practice-sessions/" + created.session().id()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(StartSessionResponse.from(created.session(), languageProfileId, created.material()));
        };
    }

    @PutMapping(
            path = "/api/language-profiles/{languageProfileId}/practice-sessions/{sessionId}/responses/{stepId}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> submitLearnerResponse(
            @PathVariable UUID languageProfileId,
            @PathVariable UUID sessionId,
            @PathVariable String stepId,
            @RequestBody SubmitLearnerResponseRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true) UserContext userContext) {
        SubmitResult result = practiceSessionApplicationService.submit(
                languageProfileId, sessionId, stepId, userContext, request.learnerText());

        return switch (result) {
            case SubmitResult.InvalidResponse ignored ->
                    practiceFailure(HttpStatus.BAD_REQUEST, "INVALID_PRACTICE_RESPONSE");
            case SubmitResult.SessionNotFound ignored ->
                    practiceFailure(HttpStatus.NOT_FOUND, "PRACTICE_SESSION_NOT_FOUND");
            case SubmitResult.StepNotFound ignored ->
                    practiceFailure(HttpStatus.NOT_FOUND, "PRACTICE_STEP_NOT_FOUND");
            case SubmitResult.SessionNotAcceptingResponses ignored ->
                    practiceFailure(HttpStatus.CONFLICT, "PRACTICE_SESSION_NOT_ACCEPTING_RESPONSES");
            case SubmitResult.ResponseConflict ignored ->
                    practiceFailure(HttpStatus.CONFLICT, "PRACTICE_RESPONSE_CONFLICT");
            case SubmitResult.MaterialUnavailable ignored ->
                    practiceFailure(HttpStatus.SERVICE_UNAVAILABLE, "PRACTICE_MATERIAL_UNAVAILABLE");
            case SubmitResult.Replayed replayed ->
                    ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(SubmitResponse.from(replayed.response()));
            case SubmitResult.Accepted accepted ->
                    ResponseEntity.status(HttpStatus.CREATED)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(SubmitResponse.from(accepted.response()));
        };
    }

    // 对外只暴露稳定的业务错误码，不返回 validation、Content 或 persistence exception detail。
    private static ResponseEntity<PracticeErrorResponse> practiceFailure(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new PracticeErrorResponse(code));
    }

    @PutMapping(
            path = "/api/language-profiles/{languageProfileId}/practice-sessions/{sessionId}/completion")
    ResponseEntity<?> completePracticeSession(
            @PathVariable UUID languageProfileId,
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal(errorOnInvalidType = true) UserContext userContext) {
        CompletionResult result = practiceSessionApplicationService.complete(
                languageProfileId, sessionId, userContext);

        return switch (result) {
            case CompletionResult.SessionNotFound ignored ->
                    practiceFailure(HttpStatus.NOT_FOUND, "PRACTICE_SESSION_NOT_FOUND");
            case CompletionResult.Incomplete ignored ->
                    practiceFailure(HttpStatus.CONFLICT, "PRACTICE_SESSION_INCOMPLETE");
            case CompletionResult.NotCompletable ignored ->
                    practiceFailure(HttpStatus.CONFLICT, "PRACTICE_SESSION_NOT_COMPLETABLE");
            case CompletionResult.MaterialUnavailable ignored ->
                    practiceFailure(HttpStatus.SERVICE_UNAVAILABLE, "PRACTICE_MATERIAL_UNAVAILABLE");
            case CompletionResult.Replayed replayed ->
                    ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(CompletionResponse.from(
                                    replayed.session(), replayed.task(), replayed.assessment()));
            case CompletionResult.Created created ->
                    ResponseEntity
                            .created(URI.create("/api/language-profiles/" + languageProfileId
                                    + "/practice-sessions/" + sessionId + "/completion"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(CompletionResponse.from(
                                    created.session(), created.task(), created.assessment()));
        };
    }

    record SubmitLearnerResponseRequest(String learnerText) {
    }

    record PracticeErrorResponse(String code) {
    }

    /**
     * completion 响应只携带 durable deterministic 结果与派生统计：不含 learner text、accepted
     * answers、normalized text、userId 或任何 semantic / 长期学习状态；taskCompletion 由同一
     * transaction 内的 Session/Task COMPLETED 状态表达，不保存冗余 boolean。
     */
    record CompletionResponse(
            UUID sessionId,
            String assessmentPolicyVersion,
            long durationSeconds,
            OffsetDateTime createdAt,
            int totalStepCount,
            int answeredStepCount,
            int exactMatchedCount,
            int exactNotMatchedCount,
            int semanticOnlyCount,
            String sessionStatus,
            String taskStatus,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt) {

        static CompletionResponse from(
                PracticeSession session, LearningTask task, DeterministicAssessment assessment) {
            List<DeterministicAssessment.StepResult> stepResults = assessment.stepResults();
            int exactMatched = 0;
            int exactNotMatched = 0;
            int semanticOnly = 0;
            for (DeterministicAssessment.StepResult stepResult : stepResults) {
                switch (stepResult.stepKind()) {
                    case EXACT -> {
                        if (stepResult.outcome() == DeterministicAssessment.StepOutcome.MATCHED) {
                            exactMatched++;
                        } else {
                            exactNotMatched++;
                        }
                    }
                    case SEMANTIC_ONLY -> semanticOnly++;
                }
            }
            // completion precondition 保证每个 material step 都有已接受 response。
            return new CompletionResponse(
                    session.id(),
                    assessment.assessmentPolicyVersion(),
                    assessment.durationSeconds(),
                    assessment.createdAt(),
                    stepResults.size(),
                    stepResults.size(),
                    exactMatched,
                    exactNotMatched,
                    semanticOnly,
                    session.status().name(),
                    task.status().name(),
                    session.startedAt(),
                    session.completedAt().orElseThrow());
        }
    }

    record StartSessionResponse(
            UUID sessionId,
            UUID taskId,
            UUID languageProfileId,
            String status,
            OffsetDateTime startedAt,
            PracticeMaterialView material) {

        static StartSessionResponse from(
                PracticeSession session, UUID languageProfileId, PracticeMaterialView material) {
            return new StartSessionResponse(
                    session.id(),
                    session.taskId(),
                    languageProfileId,
                    session.status().name(),
                    session.startedAt(),
                    material);
        }
    }

    record SubmitResponse(UUID sessionId, String stepId, OffsetDateTime submittedAt) {

        static SubmitResponse from(PracticeSession.LearnerResponse response) {
            return new SubmitResponse(
                    response.sessionId(), response.stepId(), response.submittedAt());
        }
    }
}
