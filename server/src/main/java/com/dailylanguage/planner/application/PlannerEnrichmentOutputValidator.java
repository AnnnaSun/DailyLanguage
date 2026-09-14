package com.dailylanguage.planner.application;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Objects;
import java.util.function.Predicate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.modelgateway.structuredoutput.StructuredOutputFailure;
import com.dailylanguage.modelgateway.structuredoutput.StructuredOutputValidation;
import com.dailylanguage.modelgateway.structuredoutput.StructuredOutputValidator;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.planner.domain.PlanningCandidateSet;
import com.dailylanguage.planner.domain.PlannerEnrichmentOutput;
import com.dailylanguage.planner.domain.PlannerEnrichmentResult;
import com.dailylanguage.planner.domain.PlannerEnrichmentResult.RejectionReason;

/**
 * 把 Planner enrichment 的不可信 Model output 转成 Java 已确认的 selection。处理顺序固定：
 * raw size → token 类型防线 → strict binding → exact candidate membership → reason NFC 归一化
 * 与边界；任一失败整条拒绝。零 DB / Model / Log 副作用，rejected text 不进入任何返回值。
 */
public final class PlannerEnrichmentOutputValidator {

    /** raw generated JSON 的 UTF-8 字节上限。 */
    public static final int MAXIMUM_RAW_JSON_BYTES = 8 * 1024;
    /** reason 归一化（NFC + strip）后的 Unicode code point 上限；下限为 1。 */
    public static final int MAXIMUM_REASON_CODE_POINTS = 240;

    /** 只读 JSON tree、不做任何 scalar coercion 的 mapper，供 token 类型防线使用。 */
    private static final JsonMapper TREE_INSPECTING_MAPPER = JsonMapper.builder().build();

    private final StructuredOutputValidator structuredOutputValidator;

    public PlannerEnrichmentOutputValidator() {
        this(new StructuredOutputValidator(JsonMapper.builder().build()));
    }

    PlannerEnrichmentOutputValidator(StructuredOutputValidator structuredOutputValidator) {
        this.structuredOutputValidator =
                Objects.requireNonNull(structuredOutputValidator, "structuredOutputValidator must not be null");
    }

    public PlannerEnrichmentResult validate(String generatedJson, PlanningCandidateSet candidateSet) {
        Objects.requireNonNull(generatedJson, "generatedJson must not be null");
        Objects.requireNonNull(candidateSet, "candidateSet must not be null");

        if (generatedJson.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_RAW_JSON_BYTES) {
            return new PlannerEnrichmentResult.Rejected(RejectionReason.LIMIT_EXCEEDED);
        }

        // 输入边界的 scalar 类型防线：record binding 后类型信息已丢失，共享
        // StructuredOutputValidator 默认不拒绝 coercion（123 → "123"），因此先按 JSON tree 的
        // 真实 token 类型拒绝非法 scalar，再做 strict binding。missing / extra / duplicate 字段
        // 仍交给 strict binding 的 FAIL_ON_* feature。
        RejectionReason tokenTypeFailure = checkOutputJsonTokenTypes(readGeneratedTree(generatedJson));
        if (tokenTypeFailure != null) {
            return new PlannerEnrichmentResult.Rejected(tokenTypeFailure);
        }
        StructuredOutputValidation<PlannerEnrichmentOutput> binding = structuredOutputValidator
                .validateJsonObject(generatedJson, PlannerEnrichmentOutput.class);
        if (!(binding instanceof StructuredOutputValidation.Valid<PlannerEnrichmentOutput> validBinding)) {
            StructuredOutputFailure failure =
                    ((StructuredOutputValidation.Invalid<PlannerEnrichmentOutput>) binding).failure();
            return new PlannerEnrichmentResult.Rejected(
                    failure == StructuredOutputFailure.MALFORMED_JSON
                            ? RejectionReason.MALFORMED_JSON
                            : RejectionReason.SHAPE_INVALID);
        }
        PlannerEnrichmentOutput output = validBinding.value();

        LearningTaskPlan matched = findMatchedCandidate(output, candidateSet);
        if (matched == null) {
            return new PlannerEnrichmentResult.Rejected(RejectionReason.CANDIDATE_UNKNOWN);
        }

        String normalizedReason =
                Normalizer.normalize(output.recommendationReason(), Normalizer.Form.NFC).strip();
        if (normalizedReason.isEmpty() || containsLineBreakOrControl(normalizedReason)) {
            return new PlannerEnrichmentResult.Rejected(RejectionReason.REASON_INVALID);
        }
        if (normalizedReason.codePointCount(0, normalizedReason.length()) > MAXIMUM_REASON_CODE_POINTS) {
            return new PlannerEnrichmentResult.Rejected(RejectionReason.LIMIT_EXCEEDED);
        }
        return new PlannerEnrichmentResult.Valid(matched.materialIdentity(), normalizedReason);
    }

    /** tree 读取不做任何 scalar coercion；malformed JSON 返回 null，与 binding 阶段同判 MALFORMED_JSON。 */
    private static JsonNode readGeneratedTree(String generatedJson) {
        try {
            return TREE_INSPECTING_MAPPER.readTree(generatedJson);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * 按真实 JSON token 类型校验 output schema：三个字段必须是 string token，root 必须 object。
     * 字段存在（含 null token）但不满足期望类型即拒绝；missing 字段交给 strict binding。
     */
    private static RejectionReason checkOutputJsonTokenTypes(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return RejectionReason.MALFORMED_JSON;
        }
        if (!root.isObject()) {
            return RejectionReason.SHAPE_INVALID;
        }
        if (hasWronglyTypedField(root, "materialId", JsonNode::isString)
                || hasWronglyTypedField(root, "publishedVersion", JsonNode::isString)
                || hasWronglyTypedField(root, "recommendationReason", JsonNode::isString)) {
            return RejectionReason.SHAPE_INVALID;
        }
        return null;
    }

    private static boolean hasWronglyTypedField(JsonNode object, String fieldName, Predicate<JsonNode> expectedType) {
        JsonNode field = object.get(fieldName);
        return field != null && !expectedType.test(field);
    }

    /** exact 双字段匹配；只接受本次 candidate set 真实 offered 的 identity。 */
    private static LearningTaskPlan findMatchedCandidate(
            PlannerEnrichmentOutput output, PlanningCandidateSet candidateSet) {
        for (LearningTaskPlan plan : candidateSet.candidates()) {
            if (plan.materialIdentity().materialId().equals(output.materialId())
                    && plan.materialIdentity().publishedVersion().equals(output.publishedVersion())) {
                return plan;
            }
        }
        return null;
    }

    /** 覆盖 ISO control（含 \n、\r、\t）与 Unicode line separator / paragraph separator。 */
    private static boolean containsLineBreakOrControl(String text) {
        return text.codePoints().anyMatch(codePoint ->
                Character.isISOControl(codePoint) || codePoint == '\u2028' || codePoint == '\u2029');
    }
}
