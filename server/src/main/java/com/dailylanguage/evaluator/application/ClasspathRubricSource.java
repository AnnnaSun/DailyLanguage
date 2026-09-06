package com.dailylanguage.evaluator.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.core.io.ClassPathResource;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.dailylanguage.evaluator.domain.SemanticEvaluationOutput.IssueType;
import com.dailylanguage.evaluator.domain.SemanticEvaluationRubric;

/** Backend-owned classpath rubric adapter：strict binding；任何缺失或损坏都 fail closed 为 empty。 */
public final class ClasspathRubricSource implements RubricSource {

    public static final String DEFAULT_ROOT = "evaluator/rubrics";

    private final Function<String, byte[]> resourceReader;
    private final JsonMapper jsonMapper;

    public ClasspathRubricSource() {
        this(ClasspathRubricSource::readClasspathResource);
    }

    ClasspathRubricSource(Function<String, byte[]> resourceReader) {
        this.resourceReader = Objects.requireNonNull(resourceReader, "resourceReader must not be null");
        this.jsonMapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    @Override
    public Optional<SemanticEvaluationRubric> resolve(String rubricReference, String targetLanguage) {
        if (!isSafeReference(rubricReference) || targetLanguage == null || targetLanguage.isBlank()) {
            return Optional.empty();
        }
        byte[] resourceBytes;
        try {
            resourceBytes = resourceReader.apply(DEFAULT_ROOT + "/" + rubricReference + ".json");
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
        try {
            JsonNode root = jsonMapper.readTree(resourceBytes);
            if (root == null || root.isMissingNode() || !root.isObject()) {
                return Optional.empty();
            }
            StoredRubric stored = jsonMapper.treeToValue(root, StoredRubric.class);
            SemanticEvaluationRubric rubric = new SemanticEvaluationRubric(
                    stored.rubricReference(),
                    stored.targetLanguage(),
                    stored.issueDefinitions() == null ? null : stored.issueDefinitions().stream()
                            .map(definition -> new SemanticEvaluationRubric.IssueDefinition(
                                    IssueType.valueOf(definition.issueType()),
                                    definition.scope(),
                                    definition.explanationRequirement()))
                            .toList());
            if (!rubricReference.equals(rubric.rubricReference())
                    || !targetLanguage.equals(rubric.targetLanguage())) {
                return Optional.empty();
            }
            return Optional.of(rubric);
        } catch (RuntimeException exception) {
            // 损坏或不完整的 built-in resource：fail closed，按 RUBRIC_UNAVAILABLE 拒绝。
            return Optional.empty();
        }
    }

    private static boolean isSafeReference(String reference) {
        return reference != null && !reference.isBlank() && reference.equals(reference.strip())
                && !reference.startsWith("/") && !reference.endsWith("/")
                && !reference.contains("..") && !reference.contains("//") && !reference.contains("\\");
    }

    private static byte[] readClasspathResource(String location) {
        ClassPathResource resource = new ClassPathResource(location);
        try (var inputStream = resource.getInputStream()) {
            return inputStream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException("classpath resource not readable: " + location, exception);
        }
    }

    record StoredRubric(
            String rubricReference,
            String targetLanguage,
            List<StoredIssueDefinition> issueDefinitions) {
    }

    record StoredIssueDefinition(String issueType, String scope, String explanationRequirement) {
    }
}
