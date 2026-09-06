package com.dailylanguage.evaluator.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.dailylanguage.content.domain.PublishedLearningMaterial;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.practice.domain.DeterministicAssessment;
import com.dailylanguage.practice.domain.PracticeSession;

/**
 * Grounding 的 trusted input：只能由可信 Java 调用方组装（owner-scoped repository read 与
 * exact material 解析），绝不能来自 HTTP body 或 Model output。构造器只做 null / immutable
 * 防御；owner / profile / status / material / responses 一致性由 validator 以 INVALID_INPUT
 * fail-closed 校验——这些检查不是数据库授权，授权由 S8 的 owner-scoped 读取链路保证。
 */
public record GroundedEvaluationInput(
        UUID userId,
        UUID languageProfileId,
        LearningTask task,
        PracticeSession session,
        DeterministicAssessment assessment,
        List<PracticeSession.LearnerResponse> responses,
        PublishedLearningMaterial material) {

    public GroundedEvaluationInput {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(assessment, "assessment must not be null");
        Objects.requireNonNull(responses, "responses must not be null");
        Objects.requireNonNull(material, "material must not be null");
        responses = List.copyOf(responses);
    }
}
