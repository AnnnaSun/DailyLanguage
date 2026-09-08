package com.dailylanguage.evaluator.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import com.dailylanguage.content.domain.TargetPracticeCore;
import com.dailylanguage.content.domain.TextPracticeStep;
import com.dailylanguage.evaluator.domain.EvaluationRun;
import com.dailylanguage.evaluator.domain.GroundedEvaluationInput;
import com.dailylanguage.evaluator.domain.SemanticEvaluationRubric;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextMessage;
import com.dailylanguage.modelgateway.text.TextOutputSpecification;
import com.dailylanguage.practice.domain.DeterministicAssessment;
import com.dailylanguage.practice.domain.PracticeSession;

/**
 * 将可信 completed Practice snapshot 投影为 provider-neutral Evaluator request。
 */
@Component
public class EvaluationTextRequestFactory {

    static final String PROMPT_V1_RESOURCE = "evaluator/prompts/semantic-evaluation/v1.txt";

    private final RubricSource rubricSource;
    private final Function<String, String> promptReader;
    private final JsonMapper jsonMapper;

    public EvaluationTextRequestFactory() {
        this(new ClasspathRubricSource(), EvaluationTextRequestFactory::readClasspathText);
    }

    EvaluationTextRequestFactory(
            RubricSource rubricSource,
            Function<String, String> promptReader) {
        this.rubricSource = Objects.requireNonNull(rubricSource, "rubricSource must not be null");
        this.promptReader = Objects.requireNonNull(promptReader, "promptReader must not be null");
        this.jsonMapper = JsonMapper.builder().build();
    }

    public BuildResult build(
            GroundedEvaluationInputResult.Ready ready,
            long workflowVersion) {
        Objects.requireNonNull(ready, "ready must not be null");
        if (workflowVersion != EvaluationRun.CURRENT_WORKFLOW_VERSION) {
            return new BuildResult.Unavailable(UnavailabilityReason.UNSUPPORTED_WORKFLOW_VERSION);
        }

        GroundedEvaluationInput input = ready.input();
        TargetPracticeCore targetCore = input.material().targetCore();
        Optional<SemanticEvaluationRubric> resolvedRubric = rubricSource.resolve(
                targetCore.semanticRubricReference(), input.task().targetLanguage());
        if (resolvedRubric.isEmpty()) {
            return new BuildResult.Unavailable(UnavailabilityReason.RUBRIC_UNAVAILABLE);
        }

        String instruction;
        try {
            instruction = promptReader.apply(PROMPT_V1_RESOURCE);
        } catch (RuntimeException exception) {
            return new BuildResult.Unavailable(UnavailabilityReason.PROMPT_UNAVAILABLE);
        }
        if (instruction == null || instruction.isBlank()) {
            return new BuildResult.Unavailable(UnavailabilityReason.PROMPT_UNAVAILABLE);
        }

        PromptInput promptInput = toPromptInput(input, resolvedRubric.orElseThrow());
        String userJson;
        try {
            userJson = jsonMapper.writeValueAsString(promptInput);
        } catch (RuntimeException exception) {
            // 这里只序列化内部封闭 record；失败表示 programming/configuration invariant 被破坏。
            throw new IllegalStateException("evaluation prompt input could not be serialized", exception);
        }
        TextGenerationRequest request = new TextGenerationRequest(
                ModelPurpose.EVALUATION,
                List.of(
                        new TextMessage(TextMessage.Role.INSTRUCTION, instruction),
                        new TextMessage(TextMessage.Role.USER, userJson)),
                TextOutputSpecification.jsonObject());
        return new BuildResult.ReadyRequest(request);
    }

    private static PromptInput toPromptInput(
            GroundedEvaluationInput input,
            SemanticEvaluationRubric rubric) {
        TargetPracticeCore targetCore = input.material().targetCore();
        List<PromptStep> steps = targetCore.steps().stream()
                .map(EvaluationTextRequestFactory::toPromptStep)
                .toList();
        List<PromptLearnerResponse> learnerResponses = input.responses().stream()
                .map(EvaluationTextRequestFactory::toPromptLearnerResponse)
                .toList();
        List<PromptStepResult> deterministicStepResults = input.assessment().stepResults().stream()
                .map(EvaluationTextRequestFactory::toPromptStepResult)
                .toList();
        PromptRubric promptRubric = new PromptRubric(
                rubric.rubricReference(),
                rubric.targetLanguage(),
                rubric.issueDefinitions().stream()
                        .map(definition -> new PromptIssueDefinition(
                                definition.issueType().name(),
                                definition.scope(),
                                definition.explanationRequirement()))
                        .toList());
        return new PromptInput(
                input.task().targetLanguage(),
                input.task().difficulty().name(),
                input.task().scenario(),
                input.task().primaryGoal(),
                targetCore.communicationObjective(),
                targetCore.targetLanguageText(),
                steps,
                learnerResponses,
                deterministicStepResults,
                promptRubric);
    }

    private static PromptStep toPromptStep(TextPracticeStep step) {
        return new PromptStep(step.stepId(), step.kind().name(), step.prompt());
    }

    private static PromptLearnerResponse toPromptLearnerResponse(
            PracticeSession.LearnerResponse response) {
        return new PromptLearnerResponse(response.stepId(), response.learnerText());
    }

    private static PromptStepResult toPromptStepResult(
            DeterministicAssessment.StepResult stepResult) {
        return new PromptStepResult(
                stepResult.stepId(), stepResult.stepKind().name(), stepResult.outcome().name());
    }

    private static String readClasspathText(String location) {
        ClassPathResource resource = new ClassPathResource(location);
        try (InputStream inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("classpath resource not readable", exception);
        }
    }

    public sealed interface BuildResult {

        record ReadyRequest(TextGenerationRequest request) implements BuildResult {

            public ReadyRequest {
                Objects.requireNonNull(request, "request must not be null");
            }
        }

        record Unavailable(UnavailabilityReason reason) implements BuildResult {

            public Unavailable {
                Objects.requireNonNull(reason, "reason must not be null");
            }
        }
    }

    public enum UnavailabilityReason {
        UNSUPPORTED_WORKFLOW_VERSION,
        RUBRIC_UNAVAILABLE,
        PROMPT_UNAVAILABLE
    }

    record PromptInput(
            String targetLanguage,
            String difficulty,
            String scenario,
            String taskPrimaryGoal,
            String communicationObjective,
            String targetLanguageText,
            List<PromptStep> steps,
            List<PromptLearnerResponse> learnerResponses,
            List<PromptStepResult> deterministicStepResults,
            PromptRubric rubric) {
    }

    record PromptStep(String stepId, String kind, String prompt) {
    }

    record PromptLearnerResponse(String sourceTurnId, String learnerText) {
    }

    record PromptStepResult(String stepId, String stepKind, String outcome) {
    }

    record PromptRubric(
            String rubricReference,
            String targetLanguage,
            List<PromptIssueDefinition> issueDefinitions) {
    }

    record PromptIssueDefinition(
            String issueType,
            String scope,
            String explanationRequirement) {
    }
}
