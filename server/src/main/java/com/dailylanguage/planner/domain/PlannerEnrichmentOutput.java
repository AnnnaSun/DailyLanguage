package com.dailylanguage.planner.domain;

import java.util.Objects;

/**
 * Planner enrichment 的不可信 Model output contract：字段集封闭，unknown / missing / null 字段由
 * StructuredOutputValidator 拒绝。Model 只能按 exact materialId + publishedVersion 声明选择本次
 * offered 的一个 candidate，并附一条 recommendationReason；不得提供 task 字段改写、step 内容、
 * owner、profile 或长期学习状态。字段语义边界由 PlannerEnrichmentOutputValidator 裁决。
 */
public record PlannerEnrichmentOutput(
        String materialId,
        String publishedVersion,
        String recommendationReason) {

    public PlannerEnrichmentOutput {
        Objects.requireNonNull(materialId, "materialId must not be null");
        Objects.requireNonNull(publishedVersion, "publishedVersion must not be null");
        Objects.requireNonNull(recommendationReason, "recommendationReason must not be null");
    }
}
