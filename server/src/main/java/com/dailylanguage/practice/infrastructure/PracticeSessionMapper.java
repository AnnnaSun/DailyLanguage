package com.dailylanguage.practice.infrastructure;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface PracticeSessionMapper {

    UUID insertOwnedAndReturnId(
            @Param("taskId") UUID taskId,
            @Param("trustedUserId") UUID trustedUserId,
            @Param("languageProfileId") UUID languageProfileId);

    Optional<StoredPracticeSession> findOwnedByTaskId(
            @Param("taskId") UUID taskId,
            @Param("trustedUserId") UUID trustedUserId,
            @Param("languageProfileId") UUID languageProfileId);

    Optional<StoredPracticeSession> findOwnedById(
            @Param("sessionId") UUID sessionId,
            @Param("trustedUserId") UUID trustedUserId,
            @Param("languageProfileId") UUID languageProfileId);

    Optional<StoredPracticeSession> findOwnedByIdForUpdate(
            @Param("sessionId") UUID sessionId,
            @Param("trustedUserId") UUID trustedUserId,
            @Param("languageProfileId") UUID languageProfileId);

    OffsetDateTime insertOwnedResponseAndReturnSubmittedAt(
            @Param("sessionId") UUID sessionId,
            @Param("stepId") String stepId,
            @Param("learnerText") String learnerText,
            @Param("trustedUserId") UUID trustedUserId,
            @Param("languageProfileId") UUID languageProfileId);

    Optional<StoredLearnerResponse> findOwnedResponse(
            @Param("sessionId") UUID sessionId,
            @Param("stepId") String stepId,
            @Param("trustedUserId") UUID trustedUserId,
            @Param("languageProfileId") UUID languageProfileId);

    List<StoredLearnerResponse> findOwnedResponses(
            @Param("sessionId") UUID sessionId,
            @Param("trustedUserId") UUID trustedUserId,
            @Param("languageProfileId") UUID languageProfileId);

    OffsetDateTime completeOwnedAndReturnCompletedAt(
            @Param("sessionId") UUID sessionId,
            @Param("trustedUserId") UUID trustedUserId,
            @Param("languageProfileId") UUID languageProfileId);

    OffsetDateTime insertOwnedAssessmentAndReturnCreatedAt(
            @Param("sessionId") UUID sessionId,
            @Param("assessmentPolicyVersion") String assessmentPolicyVersion,
            @Param("durationSeconds") long durationSeconds,
            @Param("trustedUserId") UUID trustedUserId,
            @Param("languageProfileId") UUID languageProfileId);

    UUID insertOwnedStepAssessmentAndReturnSessionId(
            @Param("sessionId") UUID sessionId,
            @Param("stepId") String stepId,
            @Param("stepKind") String stepKind,
            @Param("outcome") String outcome,
            @Param("trustedUserId") UUID trustedUserId,
            @Param("languageProfileId") UUID languageProfileId);

    Optional<StoredDeterministicAssessment> findOwnedAssessment(
            @Param("sessionId") UUID sessionId,
            @Param("trustedUserId") UUID trustedUserId,
            @Param("languageProfileId") UUID languageProfileId);

    List<StoredStepAssessment> findOwnedStepAssessments(
            @Param("sessionId") UUID sessionId,
            @Param("trustedUserId") UUID trustedUserId,
            @Param("languageProfileId") UUID languageProfileId);
}
