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
        TextPracticeStep step = new TextPracticeStep(
                "step", TextStepKind.EXACT, TextLearningPurpose.PRACTICE, "prompt", source);

        source.add("changed");

        assertThat(step.acceptedAnswers()).containsExactly("answer");
        assertThatThrownBy(() -> step.acceptedAnswers().add("changed"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void interpretsMissingLearningPurposeAsLegacyPractice() {
        // 旧 artifact / legacy fixture 缺失 learningPurpose 时在 typed contract 层解释为 PRACTICE。
        TextPracticeStep step = new TextPracticeStep(
                "step", TextStepKind.EXACT, null, "prompt", List.of("answer"));

        assertThat(step.learningPurpose()).isEqualTo(TextLearningPurpose.PRACTICE);
    }

    @Test
    void copiesTargetCoreSteps() {
        TextPracticeStep step = new TextPracticeStep(
                "step", TextStepKind.SEMANTIC_ONLY, TextLearningPurpose.PRACTICE, "prompt", List.of());
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
    void defaultsMissingGuidedStepsToEmptyImmutableList() {
        SupportScaffold scaffold = new SupportScaffold("zh-cn", "i", "e", "h", null, null);

        assertThat(scaffold.guidedSteps()).isEmpty();
        assertThatThrownBy(() -> scaffold.guidedSteps().add(
                new GuidedStepScaffold("step", "instruction", null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void copiesScaffoldGuidedSteps() {
        GuidedStepScaffold guidedStep = new GuidedStepScaffold("step", "instruction", "Could I have ___, please?");
        List<GuidedStepScaffold> source = new ArrayList<>(List.of(guidedStep));
        SupportScaffold scaffold = new SupportScaffold("zh-cn", "i", "e", "h", null, source);

        source.clear();

        assertThat(scaffold.guidedSteps()).containsExactly(guidedStep);
        assertThatThrownBy(() -> scaffold.guidedSteps().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void copiesPublishedMaterialScaffolds() {
        SupportScaffold scaffold = new SupportScaffold("zh-cn", "i", "e", "h", null, List.of());
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
                                "step", TextStepKind.SEMANTIC_ONLY, TextLearningPurpose.PRACTICE,
                                "prompt", List.of())),
                        "rubric/v1"),
                source,
                new MaterialSourceLineage("PROJECT_ORIGINAL", "1", "AGPL-3.0", "sha256:test"));

        source.clear();

        assertThat(material.supportScaffolds()).containsExactly(scaffold);
        assertThatThrownBy(() -> material.supportScaffolds().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
