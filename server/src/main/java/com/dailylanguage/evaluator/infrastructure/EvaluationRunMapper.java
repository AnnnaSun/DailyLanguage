package com.dailylanguage.evaluator.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface EvaluationRunMapper {

    /** Run id 必须先于 Job 创建取得：model_call_job.workflow_id 精确等于 EvaluationRun.id。 */
    UUID nextRunId();

    Optional<StoredEvaluationRun> findOwnedBySessionId(
            @Param("sessionId") UUID sessionId,
            @Param("trustedUserId") UUID trustedUserId,
            @Param("languageProfileId") UUID languageProfileId);

    /** 消费串行化点：锁定 owner-scoped Run 行。 */
    StoredEvaluationRun findOwnedBySessionIdForUpdate(
            @Param("sessionId") UUID sessionId,
            @Param("trustedUserId") UUID trustedUserId,
            @Param("languageProfileId") UUID languageProfileId);

    StoredEvaluationRun insertOwnedAndReturn(NewEvaluationRunRow run);

    /** PENDING → terminal 的 conditional transition；rowVersion guard 由数据库原子裁决。 */
    StoredEvaluationRun tryFinalizeOwnedAndReturn(FinalizeEvaluationRunRow finalize);

    StoredValidatedCandidate insertValidatedCandidateAndReturn(NewValidatedCandidateRow candidate);

    int insertValidatedClaim(NewValidatedClaimRow claim);

    Optional<StoredValidatedCandidate> findOwnedCandidateByRunId(
            @Param("evaluationRunId") UUID evaluationRunId,
            @Param("trustedUserId") UUID trustedUserId,
            @Param("languageProfileId") UUID languageProfileId);

    List<StoredValidatedClaim> findOwnedClaimsByRunId(
            @Param("evaluationRunId") UUID evaluationRunId,
            @Param("trustedUserId") UUID trustedUserId,
            @Param("languageProfileId") UUID languageProfileId);
}
