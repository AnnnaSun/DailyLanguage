package com.dailylanguage.planner.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextMessage;
import com.dailylanguage.modelgateway.text.TextOutputSpecification;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.planner.domain.PlanningCandidateSet;
import com.dailylanguage.planner.domain.PlanningRequest;

/**
 * 把 Java 已验证的 PlanningCandidateSet 与 planning constraints 投影为 provider-neutral
 * Planner enrichment request。读取 prompt 前先逐个核对每个 candidate 与 PlanningRequest 的
 * profile、target/support language、difficulty、duration 与 exclusion 一致，任何不一致
 * fail closed 为 typed INVALID_CANDIDATE_SET。Prompt input 只包含 target/support language、
 * difficulty、available time 与 candidate metadata（identity、scenario、communicationObjective），
 * 不包含 userId、languageProfileId、Content body 或任何 learner state；本类不调用 Model、
 * 不产生副作用。
 */
@Component
public class PlannerEnrichmentTextRequestFactory {

    static final String PROMPT_V1_RESOURCE = "planner/prompts/enrichment/v1.txt";

    private final Function<String, String> promptReader;
    private final JsonMapper jsonMapper;

    public PlannerEnrichmentTextRequestFactory() {
        this(PlannerEnrichmentTextRequestFactory::readClasspathText);
    }

    PlannerEnrichmentTextRequestFactory(Function<String, String> promptReader) {
        this.promptReader = Objects.requireNonNull(promptReader, "promptReader must not be null");
        this.jsonMapper = JsonMapper.builder().build();
    }

    public BuildResult build(PlanningRequest request, PlanningCandidateSet candidateSet) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(candidateSet, "candidateSet must not be null");

        UnavailabilityReason inconsistency = checkCandidateSetConsistency(request, candidateSet);
        if (inconsistency != null) {
            return new BuildResult.Unavailable(inconsistency);
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

        PromptInput promptInput = toPromptInput(request, candidateSet);
        String userJson;
        try {
            userJson = jsonMapper.writeValueAsString(promptInput);
        } catch (RuntimeException exception) {
            // 这里只序列化内部封闭 record；失败表示 programming/configuration invariant 被破坏。
            throw new IllegalStateException(
                    "planner enrichment prompt input could not be serialized", exception);
        }
        TextGenerationRequest textRequest = new TextGenerationRequest(
                ModelPurpose.PLANNING,
                List.of(
                        new TextMessage(TextMessage.Role.INSTRUCTION, instruction),
                        new TextMessage(TextMessage.Role.USER, userJson)),
                TextOutputSpecification.jsonObject());
        return new BuildResult.ReadyRequest(textRequest);
    }

    private static PromptInput toPromptInput(PlanningRequest request, PlanningCandidateSet candidateSet) {
        List<PromptCandidate> candidates = candidateSet.candidates().stream()
                .map(PlannerEnrichmentTextRequestFactory::toPromptCandidate)
                .toList();
        return new PromptInput(
                request.languageProfile().languageCode(),
                request.supportLanguage(),
                request.requestedDifficulty().name(),
                request.availableMinutes(),
                candidates);
    }

    private static PromptCandidate toPromptCandidate(LearningTaskPlan plan) {
        // v1 contract 冻结的序列化字段是 communicationObjective；内部来源 LearningTaskPlan.primaryGoal
        // 承载的正是 material 的 communication objective（由 EligibleLearningTaskCandidateReader 写入）。
        return new PromptCandidate(
                plan.materialIdentity().materialId(),
                plan.materialIdentity().publishedVersion(),
                plan.scenario(),
                plan.primaryGoal());
    }

    /**
     * request 与 candidate set 可独立构造，发送给 Model 前必须重新绑定：任一 candidate 违反
     * 本次 request 的 profile / language / difficulty / duration / exclusion 约束即整体拒绝，
     * 不把 partial set 交给 Model。null 表示全部一致。
     */
    private static UnavailabilityReason checkCandidateSetConsistency(
            PlanningRequest request, PlanningCandidateSet candidateSet) {
        for (LearningTaskPlan plan : candidateSet.candidates()) {
            if (!plan.languageProfileId().equals(request.languageProfile().id())
                    || !plan.targetLanguage().equals(request.languageProfile().languageCode())
                    || !plan.supportLanguage().equals(request.supportLanguage())
                    || plan.difficulty() != request.requestedDifficulty()
                    || plan.estimatedDurationMinutes() > request.availableMinutes()
                    || request.excludedMaterials().contains(plan.materialIdentity())) {
                return UnavailabilityReason.INVALID_CANDIDATE_SET;
            }
        }
        return null;
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
        INVALID_CANDIDATE_SET,
        PROMPT_UNAVAILABLE
    }

    record PromptInput(
            String targetLanguage,
            String supportLanguage,
            String difficulty,
            int availableMinutes,
            List<PromptCandidate> candidates) {
    }

    record PromptCandidate(
            String materialId,
            String publishedVersion,
            String scenario,
            String communicationObjective) {
    }
}
