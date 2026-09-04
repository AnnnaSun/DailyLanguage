package com.dailylanguage.planner.api;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.dailylanguage.planner.application.LearningTaskPlanningResult;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.Created;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.InvalidRequest;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.LanguageProfileNotFound;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.Unavailable;
import com.dailylanguage.planner.application.LearningTaskPlanningService;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.domain.PlanningResult;
import com.dailylanguage.security.domain.UserContext;

/**
 * Owner-scoped planning 的 HTTP 入口。资源归属只接受 Spring Security 建立的 UserContext；
 * request body、query parameter 或 header 中的 userId 不参与授权判断，成功响应也来自数据库
 * 创建后的 durable LearningTask，且不回传 userId 或任何 Content 本体。
 */
@RestController
public class LearningTaskPlanningController {

    private final LearningTaskPlanningService planningService;

    public LearningTaskPlanningController(LearningTaskPlanningService planningService) {
        this.planningService = planningService;
    }

    @PostMapping(path = "/api/language-profiles/{languageProfileId}/learning-tasks",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> createLearningTask(
            @PathVariable UUID languageProfileId,
            @RequestBody CreateLearningTaskRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true) UserContext userContext) {
        LearningTaskPlanningResult result = planningService.plan(
                languageProfileId,
                userContext,
                new LearningTaskPlanningService.PlanningCommand(
                        request.supportLanguage(),
                        request.requestedDifficulty(),
                        request.availableMinutes()));

        return switch (result) {
            case InvalidRequest ignored ->
                    planningFailure(HttpStatus.BAD_REQUEST, "INVALID_PLANNING_REQUEST");
            case LanguageProfileNotFound ignored ->
                    planningFailure(HttpStatus.NOT_FOUND, "LANGUAGE_PROFILE_NOT_FOUND");
            case Unavailable unavailable ->
                    planningFailure(unavailableHttpStatus(unavailable.reason()), unavailable.reason().name());
            case Created created -> createdResponse(created.task());
        };
    }

    // SELECTED_MATERIAL_UNAVAILABLE 表示 list/resolve 的 Content contract 不一致，属于服务端
    // 状态问题；其余两个是客户端可修正的时间与材料约束。
    private static HttpStatus unavailableHttpStatus(PlanningResult.UnavailableReason reason) {
        return switch (reason) {
            case AVAILABLE_TIME_TOO_SHORT, NO_ELIGIBLE_MATERIAL -> HttpStatus.UNPROCESSABLE_ENTITY;
            case SELECTED_MATERIAL_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    private static ResponseEntity<PlanningErrorResponse> planningFailure(HttpStatus status, String code) {
        // 对外只暴露稳定的业务错误码，不返回 validation、Content 或 persistence exception detail。
        return ResponseEntity.status(status).body(new PlanningErrorResponse(code));
    }

    private static ResponseEntity<LearningTaskResponse> createdResponse(LearningTask task) {
        return ResponseEntity
                .created(URI.create("/api/language-profiles/" + task.languageProfileId()
                        + "/learning-tasks/" + task.id()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(LearningTaskResponse.from(task));
    }

    record CreateLearningTaskRequest(String supportLanguage, String requestedDifficulty, Integer availableMinutes) {
    }

    record PlanningErrorResponse(String code) {
    }

    record LearningTaskResponse(
            UUID taskId,
            UUID languageProfileId,
            String materialId,
            String publishedVersion,
            String targetLanguage,
            String supportLanguage,
            String difficulty,
            int estimatedDurationMinutes,
            String scenario,
            String primaryGoal,
            String taskType,
            String planningReason,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt) {

        static LearningTaskResponse from(LearningTask task) {
            return new LearningTaskResponse(
                    task.id(),
                    task.languageProfileId(),
                    task.materialIdentity().materialId(),
                    task.materialIdentity().publishedVersion(),
                    task.targetLanguage(),
                    task.supportLanguage(),
                    task.difficulty().name(),
                    task.estimatedDurationMinutes(),
                    task.scenario(),
                    task.primaryGoal(),
                    task.taskType().name(),
                    task.planningReason().name(),
                    task.status().name(),
                    task.createdAt(),
                    task.startedAt().orElse(null),
                    task.completedAt().orElse(null));
        }
    }
}
