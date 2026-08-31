package com.dailylanguage.modelgateway.structuredoutput;

import java.util.Objects;
import java.util.function.Predicate;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 把 Model 生成的 JSON object 严格绑定为 Java-owned record，并在 typed binding 后执行 semantic rule。
 */
public final class StructuredOutputValidator {

    private final JsonMapper jsonMapper;

    public StructuredOutputValidator(JsonMapper jsonMapper) {
        JsonMapper sourceMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
        this.jsonMapper = sourceMapper.rebuild()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    public <T> StructuredOutputValidation<T> validateJsonObject(
            String generatedText,
            Class<T> outputType) {
        return validateJsonObject(generatedText, outputType, output -> true);
    }

    public <T> StructuredOutputValidation<T> validateJsonObject(
            String generatedText,
            Class<T> outputType,
            Predicate<? super T> semanticRule) {
        Objects.requireNonNull(generatedText, "generatedText must not be null");
        Objects.requireNonNull(outputType, "outputType must not be null");
        Objects.requireNonNull(semanticRule, "semanticRule must not be null");
        if (!outputType.isRecord()) {
            throw new IllegalArgumentException("outputType must be a record");
        }

        JsonNode root;
        try {
            root = jsonMapper.readTree(generatedText);
        }
        catch (JacksonException exception) {
            return StructuredOutputValidation.invalid(StructuredOutputFailure.MALFORMED_JSON);
        }
        if (root == null || root.isMissingNode()) {
            return StructuredOutputValidation.invalid(StructuredOutputFailure.MALFORMED_JSON);
        }
        if (!root.isObject()) {
            return StructuredOutputValidation.invalid(StructuredOutputFailure.SHAPE_INVALID);
        }

        T output;
        try {
            output = jsonMapper.treeToValue(root, outputType);
        }
        catch (InvalidFormatException exception) {
            if (exception.getTargetType() != null && exception.getTargetType().isEnum()) {
                return StructuredOutputValidation.invalid(StructuredOutputFailure.ENUM_INVALID);
            }
            return StructuredOutputValidation.invalid(StructuredOutputFailure.SHAPE_INVALID);
        }
        catch (JacksonException exception) {
            return StructuredOutputValidation.invalid(StructuredOutputFailure.SHAPE_INVALID);
        }

        if (!semanticRule.test(output)) {
            return StructuredOutputValidation.invalid(StructuredOutputFailure.SEMANTIC_INVALID);
        }
        return StructuredOutputValidation.valid(output);
    }
}
