package com.dailylanguage.content.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PublishedLearningMaterialImmutabilityTests {

    @Test
    void copiesAvailableSummarySupportLanguages() {
        List<String> source = new ArrayList<>(List.of("zh-cn"));
        AvailableMaterialSummary summary = new AvailableMaterialSummary(
                new MaterialIdentity("material", "v1"),
                "en",
                MaterialDifficulty.FOUNDATION,
                "SCENARIO",
                source);

        source.add("ja");

        assertThat(summary.supportLanguages()).containsExactly("zh-cn");
        assertThatThrownBy(() -> summary.supportLanguages().add("ja"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void copiesAcceptedAnswers() {
        List<String> source = new ArrayList<>(List.of("answer"));
        TextPracticeStep step = new TextPracticeStep("step", TextStepKind.EXACT, "prompt", source);

        source.add("changed");

        assertThat(step.acceptedAnswers()).containsExactly("answer");
        assertThatThrownBy(() -> step.acceptedAnswers().add("changed"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void copiesTargetCoreSteps() {
        TextPracticeStep step = new TextPracticeStep(
                "step", TextStepKind.SEMANTIC_ONLY, "prompt", List.of());
        List<TextPracticeStep> source = new ArrayList<>(List.of(step));
        TargetPracticeCore targetCore = new TargetPracticeCore(
                "en",
                MaterialDifficulty.FOUNDATION,
                "SCENARIO",
                "objective",
                "text",
                null,
                source,
                "rubric/v1");

        source.clear();

        assertThat(targetCore.steps()).containsExactly(step);
        assertThatThrownBy(() -> targetCore.steps().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void copiesPublishedMaterialScaffolds() {
        SupportScaffold scaffold = new SupportScaffold("zh-cn", "i", "e", "h", null);
        List<SupportScaffold> source = new ArrayList<>(List.of(scaffold));
        PublishedLearningMaterial material = new PublishedLearningMaterial(
                new MaterialIdentity("material", "v1"),
                new TargetPracticeCore(
                        "en",
                        MaterialDifficulty.FOUNDATION,
                        "SCENARIO",
                        "objective",
                        "text",
                        null,
                        List.of(new TextPracticeStep(
                                "step", TextStepKind.SEMANTIC_ONLY, "prompt", List.of())),
                        "rubric/v1"),
                source,
                new MaterialSourceLineage("PROJECT_ORIGINAL", "1", "AGPL-3.0", "sha256:test"));

        source.clear();

        assertThat(material.supportScaffolds()).containsExactly(scaffold);
        assertThatThrownBy(() -> material.supportScaffolds().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
