package com.dailylanguage.practice.application;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.dailylanguage.content.domain.LearningMaterialCatalog;
import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.content.domain.MaterialQueryResult;
import com.dailylanguage.content.infrastructure.BuiltInLearningMaterialCatalog;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.SupportObservation;
import com.dailylanguage.practice.domain.PracticeSession.ResponseSupportCondition;
import com.dailylanguage.practice.domain.PracticeSession.SupportExposure;

import static com.dailylanguage.practice.domain.PracticeSession.SupportExposure.NOT_PROVIDED;
import static com.dailylanguage.practice.domain.PracticeSession.SupportExposure.OPENED;
import static com.dailylanguage.practice.domain.PracticeSession.SupportExposure.PROVIDED;
import static com.dailylanguage.practice.domain.PracticeSession.SupportExposure.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * S11-A Human Core 的行为验收；使用真实、经过 loader 校验的材料，避免依赖不合法的 synthetic scaffold。
 * Human Core 完成前这些测试预期 RED，不禁用测试，也不把 placeholder exception 当作目标行为。
 */
class PracticeSupportObservationTests {

    private static final LearningMaterialCatalog CATALOG =
            new BuiltInLearningMaterialCatalog();

    @Test
    void missingObservationKeepsLegacyUnknownForGuidedMaterial() {
        assertThat(validate(guidedCafe(), null)).contains(ResponseSupportCondition.unknown());
    }

    @Test
    void missingObservationDoesNotRelabelLegacyHistoryEvenWhenFrameIsAbsent() {
        assertThat(validate(material("ja-builtin-clarify-repeat", "v1"), null))
                .contains(ResponseSupportCondition.unknown());
    }

    @Test
    void preservesDifferentReportedConditionsWithoutTurningUnknownIntoNoSupport() {
        SupportObservation observation = new SupportObservation(PROVIDED, OPENED, UNKNOWN, PROVIDED);

        assertThat(validate(guidedCafe(), observation))
                .contains(new ResponseSupportCondition(PROVIDED, OPENED, UNKNOWN, PROVIDED));
    }

    @ParameterizedTest
    @EnumSource(value = SupportExposure.class, names = {"PROVIDED", "OPENED"})
    void preservesEachPositiveExposureForEveryAvailableDimension(SupportExposure exposure) {
        SupportObservation observation = new SupportObservation(exposure, exposure, exposure, exposure);

        assertThat(validate(guidedCafe(), observation))
                .contains(new ResponseSupportCondition(exposure, exposure, exposure, exposure));
    }

    @Test
    void availableButUnobservedSupportRemainsUnknown() {
        assertThat(validate(guidedCafe(), allUnknown()))
                .contains(ResponseSupportCondition.unknown());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ja-builtin-clarify-repeat", "en-builtin-greeting-intro", "en-builtin-cafe-request"
    })
    void derivesAbsentFrameFromWholeSelectedMaterialWithoutLanguageBranches(String materialId) {
        assertThat(validate(material(materialId, "v1"), allUnknown()))
                .contains(new ResponseSupportCondition(UNKNOWN, UNKNOWN, UNKNOWN, NOT_PROVIDED));
    }

    @Test
    void frameAbsentOnTransferStepDoesNotMeanFrameAbsentFromSessionMaterial() {
        MaterialQueryResult.Available material = guidedCafe();
        assertThat(material.selectedScaffold().guidedSteps().getLast().responseFrame()).isNull();

        assertThat(validate(material, allUnknown())).contains(ResponseSupportCondition.unknown());
    }

    @Test
    void previouslyOpenedFrameIsNotErasedByTheTransferStepHavingNoFrame() {
        SupportObservation observation = new SupportObservation(PROVIDED, UNKNOWN, UNKNOWN, OPENED);

        assertThat(validate(guidedCafe(), observation))
                .contains(new ResponseSupportCondition(PROVIDED, UNKNOWN, UNKNOWN, OPENED));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void rejectsEachMissingFieldInsteadOfDefaultingItToUnknown(int dimension) {
        assertThat(validate(guidedCafe(), withDimension(dimension, null))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void rejectsClientDeclaredNotProvidedInEveryDimension(int dimension) {
        assertThat(validate(guidedCafe(), withDimension(dimension, NOT_PROVIDED))).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = SupportExposure.class, names = {"PROVIDED", "OPENED"})
    void rejectsReportedFrameWhenJapaneseMaterialHasNone(SupportExposure exposure) {
        assertThat(validate(material("ja-builtin-clarify-repeat", "v1"), withDimension(3, exposure)))
                .isEmpty();
    }

    @Test
    void rejectsClientDeclaredNotProvidedEvenWhenBackendCouldDeriveIt() {
        assertThat(validate(material("ja-builtin-clarify-repeat", "v1"), withDimension(3, NOT_PROVIDED)))
                .isEmpty();
    }

    private static Optional<ResponseSupportCondition> validate(
            MaterialQueryResult.Available material, SupportObservation observation) {
        return PracticeSessionApplicationService.validateSupportObservation(material, observation);
    }

    private static SupportObservation allUnknown() {
        return new SupportObservation(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN);
    }

    private static SupportObservation withDimension(int dimension, SupportExposure exposure) {
        SupportExposure[] observations = {UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN};
        observations[dimension] = exposure;
        return new SupportObservation(observations[0], observations[1], observations[2], observations[3]);
    }

    private static MaterialQueryResult.Available guidedCafe() {
        return material("en-builtin-cafe-request", "v2");
    }

    private static MaterialQueryResult.Available material(String materialId, String version) {
        return (MaterialQueryResult.Available) CATALOG.findByIdentity(
                new MaterialIdentity(materialId, version), "zh-cn");
    }
}
