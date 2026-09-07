package com.dailylanguage.evaluator.infrastructure;

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

    StoredEvaluationRun insertOwnedAndReturn(NewEvaluationRunRow run);
}
