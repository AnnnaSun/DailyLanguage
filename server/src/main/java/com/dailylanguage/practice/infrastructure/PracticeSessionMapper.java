package com.dailylanguage.practice.infrastructure;

import java.time.OffsetDateTime;
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
}
