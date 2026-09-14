package com.dailylanguage.planner.domain;

import java.util.Objects;

import com.dailylanguage.content.domain.MaterialIdentity;

/**
 * Enrichment output 校验的互斥结果：Valid 只暴露 Java 已确认属于本次 PlanningCandidateSet 的
 * exact identity（直接引用 matched candidate 的 MaterialIdentity，不从字符串重建）与 NFC + strip
 * 归一化后的 recommendationReason；Rejected 只携带 failure category，不携带 generated text。
 * 校验通过只表示 selection / reason 符合 output contract，不证明推荐质量，不得升级为 Evidence、
 * learner state 或 Content authority。
 */
public sealed interface PlannerEnrichmentResult permits
        PlannerEnrichmentResult.Valid,
        PlannerEnrichmentResult.Rejected {

    record Valid(MaterialIdentity selectedIdentity, String recommendationReason) implements PlannerEnrichmentResult {

        public Valid {
            Objects.requireNonNull(selectedIdentity, "selectedIdentity must not be null");
            Objects.requireNonNull(recommendationReason, "recommendationReason must not be null");
        }
    }

    record Rejected(RejectionReason reason) implements PlannerEnrichmentResult {

        public Rejected {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /** 安全失败分类；同类内不区分具体细节，避免泄露 generated text。 */
    enum RejectionReason {
        /** JSON 不可解析（malformed / fenced / blank / trailing content）。 */
        MALFORMED_JSON,
        /** 非 object root、字段 missing / null / extra / duplicate 或 token 类型错误。 */
        SHAPE_INVALID,
        /** (materialId, publishedVersion) 不属于本次 offered candidate set。 */
        CANDIDATE_UNKNOWN,
        /** reason 归一化后为空，或包含换行 / control character。 */
        REASON_INVALID,
        /** raw JSON 超过 8 KiB，或 reason 超过 240 Unicode code points。 */
        LIMIT_EXCEEDED
    }
}
