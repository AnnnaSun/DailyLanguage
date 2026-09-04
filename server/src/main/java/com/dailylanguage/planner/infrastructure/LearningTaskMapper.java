package com.dailylanguage.planner.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface LearningTaskMapper {

    UUID insertOwnedAndReturnId(NewLearningTaskRow insert);

    Optional<StoredLearningTask> findOwned(
            @Param("taskId") UUID taskId,
            @Param("trustedUserId") UUID trustedUserId,
            @Param("languageProfileId") UUID languageProfileId);

    UUID tryStartAndReturnId(
            @Param("taskId") UUID taskId,
            @Param("trustedUserId") UUID trustedUserId,
            @Param("languageProfileId") UUID languageProfileId);

    UUID tryCompleteAndReturnId(
            @Param("taskId") UUID taskId,
            @Param("trustedUserId") UUID trustedUserId,
            @Param("languageProfileId") UUID languageProfileId);
}
