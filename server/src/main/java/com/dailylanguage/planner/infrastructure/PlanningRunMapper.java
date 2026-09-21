package com.dailylanguage.planner.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface PlanningRunMapper {

    UUID nextRunId();

    StoredPlanningRun insertOwnedAndReturn(NewPlanningRunRow insert);

    int insertCandidate(NewPlanningRunCandidateRow insert);

    Optional<StoredPlanningRun> findOwned(
            @Param("planningRunId") UUID planningRunId,
            @Param("trustedUserId") UUID trustedUserId,
            @Param("languageProfileId") UUID languageProfileId);

    StoredPlanningRun findOwnedForUpdate(
            @Param("planningRunId") UUID planningRunId,
            @Param("trustedUserId") UUID trustedUserId,
            @Param("languageProfileId") UUID languageProfileId);

    StoredPlanningRun tryFinalizeOwnedAndReturn(FinalizePlanningRunRow finalize);

    List<StoredPlanningRunCandidate> findOwnedCandidates(
            @Param("planningRunId") UUID planningRunId,
            @Param("trustedUserId") UUID trustedUserId,
            @Param("languageProfileId") UUID languageProfileId);
}
