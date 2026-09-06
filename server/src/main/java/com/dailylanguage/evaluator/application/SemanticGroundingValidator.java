package com.dailylanguage.evaluator.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.dailylanguage.content.domain.PublishedLearningMaterial;
import com.dailylanguage.content.domain.TextPracticeStep;
import com.dailylanguage.evaluator.domain.GroundedEvaluationInput;
import com.dailylanguage.evaluator.domain.SemanticEvaluationOutput;
import com.dailylanguage.evaluator.domain.SemanticEvaluationRubric;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.GroundedClaim;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.RejectionReason;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.ValidatedSemanticCandidate;
import com.dailylanguage.modelgateway.structuredoutput.StructuredOutputValidation;
import com.dailylanguage.modelgateway.structuredoutput.StructuredOutputValidator;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.practice.domain.PracticeSession;

/**
 * 把 Evaluator 的不可信 semantic output 转成有真实 learner text 引用、经 Java 校验的
 * Session-level candidate。处理顺序固定：strict binding → trusted input 一致性与 rubric
 * 解析 → 逐 claim output contract → turn 解析 → 原文 literal 定位 → occurrence / offsets →
 * rubric issue 校验；任一 claim 失败整批拒绝。零 DB / cache / event / model 副作用。
 */
public final class SemanticGroundingValidator {

    /** literal 大小写敏感匹配、0-based 含重叠 occurrence、UTF-16 [start,end) offsets。 */
    public static final String GROUNDING_POLICY_VERSION = "M1_GROUNDED_QUOTE_V1";
    public static final int MAXIMUM_CLAIMS = 20;
    public static final int MAXIMUM_EXPLANATION_CODE_POINTS = 1000;

    /** 只读 JSON tree、不做任何 scalar coercion 的 mapper，供 token 类型防线使用。 */
    private static final JsonMapper TREE_INSPECTING_MAPPER = JsonMapper.builder().build();

    private final StructuredOutputValidator structuredOutputValidator;
    private final RubricSource rubricSource;

    public SemanticGroundingValidator(
            StructuredOutputValidator structuredOutputValidator, RubricSource rubricSource) {
        this.structuredOutputValidator =
                Objects.requireNonNull(structuredOutputValidator, "structuredOutputValidator must not be null");
        this.rubricSource = Objects.requireNonNull(rubricSource, "rubricSource must not be null");
    }

    public SemanticGroundingResult validate(String generatedJson, GroundedEvaluationInput trustedInput) {
        Objects.requireNonNull(generatedJson, "generatedJson must not be null");
        Objects.requireNonNull(trustedInput, "trustedInput must not be null");

        // Evaluator 输入边界的 scalar 类型防线：record binding 后类型信息已丢失，共享
        // StructuredOutputValidator 默认不拒绝 coercion（1.9 → 1、"1" → 1、0 → GRAMMAR），
        // 因此先按 JSON tree 的真实 token 类型拒绝非法 scalar，再做 strict binding。
        RejectionReason tokenTypeFailure = checkOutputJsonTokenTypes(readGeneratedTree(generatedJson));
        if (tokenTypeFailure != null) {
            return new SemanticGroundingResult.Rejected(tokenTypeFailure);
        }
        StructuredOutputValidation<SemanticEvaluationOutput> binding = structuredOutputValidator
                .validateJsonObject(generatedJson, SemanticEvaluationOutput.class);
        if (!(binding instanceof StructuredOutputValidation.Valid<SemanticEvaluationOutput> validBinding)) {
            return new SemanticGroundingResult.Rejected(RejectionReason.INVALID_STRUCTURE);
        }
        SemanticEvaluationOutput output = validBinding.value();

        RejectionReason inputFailure = checkTrustedInputConsistency(trustedInput);
        if (inputFailure != null) {
            return new SemanticGroundingResult.Rejected(inputFailure);
        }
        PublishedLearningMaterial material = trustedInput.material();
        SemanticEvaluationRubric rubric = rubricSource
                .resolve(material.targetCore().semanticRubricReference(), trustedInput.task().targetLanguage())
                .orElse(null);
        if (rubric == null) {
            return new SemanticGroundingResult.Rejected(RejectionReason.RUBRIC_UNAVAILABLE);
        }
        if (output.claims().size() > MAXIMUM_CLAIMS) {
            return new SemanticGroundingResult.Rejected(RejectionReason.LIMIT_EXCEEDED);
        }

        Map<String, PracticeSession.LearnerResponse> responseByStepId = new HashMap<>();
        for (PracticeSession.LearnerResponse response : trustedInput.responses()) {
            responseByStepId.put(response.stepId(), response);
        }

        List<GroundedClaim> groundedClaims = new ArrayList<>(output.claims().size());
        for (SemanticEvaluationOutput.Claim claim : output.claims()) {
            RejectionReason claimFailure = checkClaimOutputContract(claim);
            if (claimFailure == null) {
                claimFailure = groundClaim(claim, responseByStepId, rubric, groundedClaims);
            }
            if (claimFailure != null) {
                return new SemanticGroundingResult.Rejected(claimFailure);
            }
        }

        return new SemanticGroundingResult.Validated(new ValidatedSemanticCandidate(
                trustedInput.languageProfileId(),
                trustedInput.session().id(),
                material.identity(),
                material.targetCore().semanticRubricReference(),
                material.targetCore().targetLanguage(),
                GROUNDING_POLICY_VERSION,
                groundedClaims));
    }

    /** tree 读取不做任何 scalar coercion；malformed JSON 返回 null，与 binding 阶段同判 INVALID_STRUCTURE。 */
    private static JsonNode readGeneratedTree(String generatedJson) {
        try {
            return TREE_INSPECTING_MAPPER.readTree(generatedJson);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * 按真实 JSON token 类型校验 output schema：字符串字段必须是 string token，occurrenceIndex
     * 必须是可表示为 int 的整型 token（拒绝 1.9 / "1" / null / 超界整数），confidence 必须是
     * number token（有限性与区间仍由 semantic 检查裁决）。missing 字段交给 strict binding。
     */
    private static RejectionReason checkOutputJsonTokenTypes(JsonNode root) {
        if (root == null || root.isMissingNode() || !root.isObject()) {
            return RejectionReason.INVALID_STRUCTURE;
        }
        JsonNode claims = root.get("claims");
        if (claims == null) {
            return null;
        }
        if (!claims.isArray()) {
            return RejectionReason.INVALID_STRUCTURE;
        }
        for (JsonNode claim : claims) {
            if (!claim.isObject()
                    || hasWronglyTypedField(claim, "sourceTurnId", JsonNode::isString)
                    || hasWronglyTypedField(claim, "exactQuote", JsonNode::isString)
                    || hasWronglyTypedField(claim, "explanation", JsonNode::isString)
                    || hasWronglyTypedField(claim, "issueType", JsonNode::isString)
                    || hasWronglyTypedField(claim, "occurrenceIndex",
                            node -> node.isIntegralNumber() && node.canConvertToInt())
                    || hasWronglyTypedField(claim, "confidence", JsonNode::isNumber)) {
                return RejectionReason.INVALID_STRUCTURE;
            }
        }
        return null;
    }

    /** 字段存在（含 null token）且不满足期望 token 类型即拒绝；missing 交给 strict binding。 */
    private static boolean hasWronglyTypedField(JsonNode claim, String fieldName, Predicate<JsonNode> expectedType) {
        JsonNode field = claim.get(fieldName);
        return field != null && !expectedType.test(field);
    }

    /** trusted input 的确定性一致性检查；不通过即 INVALID_INPUT，绝不静默采纳拼装输入。 */
    private static RejectionReason checkTrustedInputConsistency(GroundedEvaluationInput input) {
        LearningTask task = input.task();
        PracticeSession session = input.session();
        if (!input.userId().equals(task.userId())
                || !input.languageProfileId().equals(task.languageProfileId())) {
            return RejectionReason.INVALID_INPUT;
        }
        if (!task.id().equals(session.taskId())
                || session.status() != PracticeSession.Status.COMPLETED
                || task.status() != LearningTask.Status.COMPLETED) {
            return RejectionReason.INVALID_INPUT;
        }
        if (!session.id().equals(input.assessment().sessionId())) {
            return RejectionReason.INVALID_INPUT;
        }
        PublishedLearningMaterial material = input.material();
        List<TextPracticeStep> steps = material.targetCore().steps();
        if (!task.materialIdentity().equals(material.identity())
                || !task.targetLanguage().equals(material.targetCore().targetLanguage())
                || steps == null || steps.isEmpty()) {
            return RejectionReason.INVALID_INPUT;
        }
        Set<String> materialStepIds = new HashSet<>();
        for (TextPracticeStep step : steps) {
            materialStepIds.add(step.stepId());
        }
        Set<String> responseStepIds = new HashSet<>();
        for (PracticeSession.LearnerResponse response : input.responses()) {
            if (!session.id().equals(response.sessionId())
                    || !materialStepIds.contains(response.stepId())
                    || !responseStepIds.add(response.stepId())) {
                return RejectionReason.INVALID_INPUT;
            }
        }
        if (!responseStepIds.equals(materialStepIds)) {
            return RejectionReason.INVALID_INPUT;
        }
        return null;
    }

    /** claim 自身 output contract（不依赖 trusted input）；null 表示通过。 */
    private static RejectionReason checkClaimOutputContract(SemanticEvaluationOutput.Claim claim) {
        double confidence = claim.confidence();
        if (!Double.isFinite(confidence) || confidence < 0d || confidence > 1d) {
            return RejectionReason.INVALID_CONFIDENCE;
        }
        if (claim.explanation().isBlank()) {
            return RejectionReason.INVALID_STRUCTURE;
        }
        if (claim.explanation().codePointCount(0, claim.explanation().length())
                > MAXIMUM_EXPLANATION_CODE_POINTS) {
            return RejectionReason.LIMIT_EXCEEDED;
        }
        if (claim.exactQuote().isBlank()) {
            return RejectionReason.QUOTE_MISMATCH;
        }
        if (claim.exactQuote().codePointCount(0, claim.exactQuote().length())
                > PracticeSession.LearnerResponse.MAXIMUM_LEARNER_TEXT_CODE_POINTS) {
            return RejectionReason.LIMIT_EXCEEDED;
        }
        return null;
    }

    /** 单条 claim 的 grounding；失败返回 category，成功则追加 immutable GroundedClaim。 */
    private static RejectionReason groundClaim(
            SemanticEvaluationOutput.Claim claim,
            Map<String, PracticeSession.LearnerResponse> responseByStepId,
            SemanticEvaluationRubric rubric,
            List<GroundedClaim> groundedClaims) {
        PracticeSession.LearnerResponse response = responseByStepId.get(claim.sourceTurnId());
        if (response == null) {
            return RejectionReason.UNKNOWN_TURN;
        }
        String learnerText = response.learnerText();
        String exactQuote = claim.exactQuote();
        List<Integer> matchStarts = literalMatchStarts(learnerText, exactQuote);
        if (matchStarts.isEmpty()) {
            return RejectionReason.QUOTE_MISMATCH;
        }
        int resolvedOccurrence;
        if (matchStarts.size() == 1) {
            if (claim.occurrenceIndex() != -1 && claim.occurrenceIndex() != 0) {
                return RejectionReason.INVALID_OCCURRENCE;
            }
            resolvedOccurrence = 0;
        } else if (claim.occurrenceIndex() == -1) {
            return RejectionReason.AMBIGUOUS_OCCURRENCE;
        } else if (claim.occurrenceIndex() < -1 || claim.occurrenceIndex() >= matchStarts.size()) {
            return RejectionReason.INVALID_OCCURRENCE;
        } else {
            resolvedOccurrence = claim.occurrenceIndex();
        }
        int startOffset = matchStarts.get(resolvedOccurrence);
        int endOffset = startOffset + exactQuote.length();
        if (splitsSurrogatePair(learnerText, startOffset, endOffset)) {
            return RejectionReason.QUOTE_MISMATCH;
        }
        if (!rubric.supports(claim.issueType())) {
            return RejectionReason.UNSUPPORTED_ISSUE;
        }
        // indexOf 字面匹配保证 learnerText.substring(startOffset, endOffset).equals(exactQuote)。
        groundedClaims.add(new GroundedClaim(
                claim.sourceTurnId(),
                exactQuote,
                resolvedOccurrence,
                startOffset,
                endOffset,
                claim.issueType(),
                claim.explanation(),
                claim.confidence()));
        return null;
    }

    /** literal exact 查找：不 strip、不忽略大小写、不做 Unicode normalization、不做模糊匹配；
     *  occurrence 按起点升序计数，包含重叠匹配。 */
    private static List<Integer> literalMatchStarts(String learnerText, String exactQuote) {
        List<Integer> matchStarts = new ArrayList<>();
        int index = learnerText.indexOf(exactQuote);
        while (index >= 0) {
            matchStarts.add(index);
            index = learnerText.indexOf(exactQuote, index + 1);
        }
        return matchStarts;
    }

    /** offsets 是 UTF-16 code unit 区间，两端都不得落在 surrogate pair 内部。 */
    private static boolean splitsSurrogatePair(String learnerText, int startOffset, int endOffset) {
        boolean startInsidePair = startOffset > 0
                && Character.isHighSurrogate(learnerText.charAt(startOffset - 1))
                && Character.isLowSurrogate(learnerText.charAt(startOffset));
        boolean endInsidePair = endOffset < learnerText.length()
                && Character.isHighSurrogate(learnerText.charAt(endOffset - 1))
                && Character.isLowSurrogate(learnerText.charAt(endOffset));
        return startInsidePair || endInsidePair;
    }
}
