package com.dailylanguage.evaluator.application;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.dailylanguage.evaluator.domain.SemanticEvaluationOutput.IssueType;
import com.dailylanguage.evaluator.domain.SemanticEvaluationRubric;
import com.dailylanguage.evaluator.domain.SemanticEvaluationRubric.IssueDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClasspathRubricSourceTests {

    private static final String BUILTIN_REFERENCE = "builtin-text-communication-rubric/v1";

    private final ClasspathRubricSource source =
            new ClasspathRubricSource();

    @Test
    void resolvesBuiltInEnglishRubricForExactReferenceAndLanguage() {
        Optional<SemanticEvaluationRubric> resolved = source.resolve(BUILTIN_REFERENCE, "en");

        assertThat(resolved).isPresent();
        SemanticEvaluationRubric rubric = resolved.orElseThrow();
        assertThat(rubric.rubricReference()).isEqualTo(BUILTIN_REFERENCE);
        assertThat(rubric.targetLanguage()).isEqualTo("en");
        assertThat(rubric.issueDefinitions()).hasSize(3);
        assertThat(rubric.issueDefinitions())
                .extracting(IssueDefinition::issueType)
                .containsExactly(IssueType.GRAMMAR, IssueType.NATURALNESS, IssueType.TASK_RESPONSE);
        assertThat(rubric.issueDefinitions())
                .allSatisfy(definition -> {
                    assertThat(definition.scope()).isNotBlank();
                    assertThat(definition.explanationRequirement()).isNotBlank();
                });
        assertThat(rubric.supports(IssueType.GRAMMAR)).isTrue();
        assertThat(rubric.supports(IssueType.TASK_RESPONSE)).isTrue();
    }

    @Test
    void unknownReferenceOrLanguageMismatchFailsClosedToEmpty() {
        assertThat(source.resolve("missing-rubric/v1", "en")).isEmpty();
        // reference 存在，但请求语言与 resource 不一致：不得跨语言落位。
        assertThat(source.resolve(BUILTIN_REFERENCE, "ja")).isEmpty();
        assertThat(source.resolve(BUILTIN_REFERENCE, "EN")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "../builtin-text-communication-rubric/v1",
            "/builtin-text-communication-rubric/v1", "builtin-text-communication-rubric//v1",
            "builtin-text-communication-rubric\\v1", "builtin-text-communication-rubric/../v1"})
    void unsafeReferencesFailClosedWithoutResourceAccess(String reference) {
        // reader 一旦被调用即失败：unsafe reference 必须在路径拼接前被拒绝。
        ClasspathRubricSource guardedSource =
                new ClasspathRubricSource(location -> {
                    throw new AssertionError("unsafe reference must not reach the resource reader: " + location);
                });

        assertThat(guardedSource.resolve(reference, "en")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{not json",
            "[]",
            "{\"rubricReference\":\"builtin-text-communication-rubric/v1\",\"targetLanguage\":\"en\"}",
            "{\"rubricReference\":\"builtin-text-communication-rubric/v1\",\"targetLanguage\":\"en\","
                    + "\"issueDefinitions\":[]}",
            "{\"rubricReference\":\"builtin-text-communication-rubric/v1\",\"targetLanguage\":\"en\","
                    + "\"issueDefinitions\":[{\"issueType\":\"GRAMMAR\",\"scope\":\" \","
                    + "\"explanationRequirement\":\"ok\"}]}",
            "{\"rubricReference\":\"builtin-text-communication-rubric/v1\",\"targetLanguage\":\"en\","
                    + "\"issueDefinitions\":[{\"issueType\":\"GRAMMAR\",\"scope\":\"ok\","
                    + "\"explanationRequirement\":\" \"}]}",
            "{\"rubricReference\":\"builtin-text-communication-rubric/v1\",\"targetLanguage\":\"en\","
                    + "\"issueDefinitions\":[{\"issueType\":\"GRAMMAR\",\"scope\":\"ok\","
                    + "\"explanationRequirement\":\"ok\"},{\"issueType\":\"GRAMMAR\",\"scope\":\"ok\","
                    + "\"explanationRequirement\":\"ok\"}]}",
            "{\"rubricReference\":\"builtin-text-communication-rubric/v1\",\"targetLanguage\":\"en\","
                    + "\"issueDefinitions\":[{\"issueType\":\"PRONUNCIATION\",\"scope\":\"ok\","
                    + "\"explanationRequirement\":\"ok\"}]}",
            "{\"rubricReference\":\"builtin-text-communication-rubric/v1\",\"targetLanguage\":\"en\","
                    + "\"issueDefinitions\":[{\"issueType\":\"GRAMMAR\",\"scope\":\"ok\","
                    + "\"explanationRequirement\":\"ok\"}],\"extra\":true}",
            "{\"rubricReference\":\"other-rubric/v1\",\"targetLanguage\":\"en\","
                    + "\"issueDefinitions\":[{\"issueType\":\"GRAMMAR\",\"scope\":\"ok\","
                    + "\"explanationRequirement\":\"ok\"}]}"})
    void adversarialResourcesFailClosedToEmpty(String resourceJson) {
        ClasspathRubricSource inMemorySource =
                new ClasspathRubricSource(
                        location -> resourceJson.getBytes(StandardCharsets.UTF_8));

        assertThat(inMemorySource.resolve(BUILTIN_REFERENCE, "en")).isEmpty();
    }

    @Test
    void unreadableResourceFailsClosedToEmpty() {
        ClasspathRubricSource unreadableSource =
                new ClasspathRubricSource(location -> {
                    throw new IllegalStateException("resource unreadable: " + location);
                });

        assertThat(unreadableSource.resolve(BUILTIN_REFERENCE, "en")).isEmpty();
    }

    @Test
    void validInMemoryResourceBindsAfterStrictValidation() {
        String resourceJson = "{\"rubricReference\":\"test-rubric/v2\",\"targetLanguage\":\"ja\","
                + "\"issueDefinitions\":[{\"issueType\":\"NATURALNESS\",\"scope\":\"ok\","
                + "\"explanationRequirement\":\"ok\"}]}";
        ClasspathRubricSource inMemorySource =
                new ClasspathRubricSource(
                        location -> resourceJson.getBytes(StandardCharsets.UTF_8));

        Optional<SemanticEvaluationRubric> resolved = inMemorySource.resolve("test-rubric/v2", "ja");
        assertThat(resolved).isPresent();
        assertThat(resolved.orElseThrow().supports(IssueType.NATURALNESS)).isTrue();
        assertThat(resolved.orElseThrow().supports(IssueType.GRAMMAR)).isFalse();
    }

    @Test
    void typedRubricConstructorRejectsInvalidDefinitions() {
        IssueDefinition grammar =
                new IssueDefinition(IssueType.GRAMMAR, "scope", "requirement");
        List<IssueDefinition> duplicateTypes = List.of(grammar, grammar);

        assertThatThrownBy(() -> new SemanticEvaluationRubric(" ", "en", List.of(grammar)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SemanticEvaluationRubric("r/v1", " ", List.of(grammar)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SemanticEvaluationRubric("r/v1", "en", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SemanticEvaluationRubric("r/v1", "en", duplicateTypes))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IssueDefinition(IssueType.GRAMMAR, " ", "requirement"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IssueDefinition(null, "scope", "requirement"))
                .isInstanceOf(NullPointerException.class);
    }
}
