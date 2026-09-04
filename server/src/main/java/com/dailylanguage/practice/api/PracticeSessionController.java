package com.dailylanguage.practice.api;

import java.net.URI;
import java.time.OffsetDateTime;
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
import com.dailylanguage.practice.application.PracticeSessionApplicationService.PracticeMaterialView;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.StartResult;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.SubmitResult;
import com.dailylanguage.practice.domain.PracticeSession;
import com.dailylanguage.security.domain.UserContext;

/**
 * PracticeSession lifecycle 的 HTTP 入口。资源归属只接受 Spring Security 建立的 UserContext；
 * request body、query parameter 或 header 中的 userId 不参与授权判断。成功响应来自数据库裁决后的
 * durable Session / response，material 只下发安全 projection，不回传 userId、acceptedAnswers
 * 或 rubric 引用。
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

    record SubmitLearnerResponseRequest(String learnerText) {
    }

    record PracticeErrorResponse(String code) {
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
