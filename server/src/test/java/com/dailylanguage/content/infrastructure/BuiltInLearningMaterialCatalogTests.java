package com.dailylanguage.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dailylanguage.content.domain.AvailableMaterialSummary;
import com.dailylanguage.content.domain.LearningMaterialCatalog;
import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.content.domain.MaterialQueryResult;
import com.dailylanguage.content.domain.MaterialUnavailableReason;
import com.dailylanguage.content.domain.PublishedLearningMaterial;

/**
 * catalog 可用性语义：published pair 可查且排序确定；未发布 target / support language 返回 unavailable，
 * 证明多 language-pair schema 下没有 cross-language fallback。
 */
class BuiltInLearningMaterialCatalogTests {

    private final LearningMaterialCatalog catalog =
            new BuiltInLearningMaterialCatalog(new ClasspathBuiltInMaterialLoader().load());

    @Test
    void listsEnglishMaterialsForPublishedLanguagePairInDeterministicOrder() {
        List<AvailableMaterialSummary> summaries = catalog.listAvailable("en", "zh-cn");

        assertThat(summaries)
                .extracting(summary -> summary.identity().materialId())
                .containsExactly("en-builtin-cafe-request", "en-builtin-greeting-intro");
        assertThat(summaries)
                .allSatisfy(summary -> {
                    assertThat(summary.targetLanguage()).isEqualTo("en");
                    assertThat(summary.supportLanguages()).containsExactly("zh-cn");
                });
    }

    @Test
    void returnsEmptyForUnpublishedTargetLanguageInsteadOfCrossLanguageFallback() {
        assertThat(catalog.listAvailable("ja", "zh-cn")).isEmpty();
        assertThat(catalog.listAvailable("fr", "zh-cn")).isEmpty();
    }

    @Test
    void returnsEmptyForUnpublishedSupportLanguage() {
        assertThat(catalog.listAvailable("en", "en")).isEmpty();
        assertThat(catalog.listAvailable("en", "ja")).isEmpty();
    }

    @Test
    void resolvesPublishedMaterialWithSelectedScaffold() {
        MaterialQueryResult result = catalog.findByIdentity(
                new MaterialIdentity("en-builtin-greeting-intro", "v1"), "zh-cn");

        assertThat(result).isInstanceOf(MaterialQueryResult.Available.class);
        MaterialQueryResult.Available available = (MaterialQueryResult.Available) result;
        assertThat(available.material().identity().materialId()).isEqualTo("en-builtin-greeting-intro");
        assertThat(available.selectedScaffold().supportLanguage()).isEqualTo("zh-cn");
    }

    @Test
    void reportsMaterialNotPublishedForUnknownIdentity() {
        MaterialQueryResult result = catalog.findByIdentity(
                new MaterialIdentity("ja-builtin-clarify-repeat", "v1"), "zh-cn");

        assertThat(result).isEqualTo(new MaterialQueryResult.Unavailable(
                MaterialUnavailableReason.MATERIAL_NOT_PUBLISHED));
    }

    @Test
    void reportsSupportLanguageNotPublishedForKnownMaterial() {
        MaterialQueryResult result = catalog.findByIdentity(
                new MaterialIdentity("en-builtin-cafe-request", "v1"), "zh-tw");

        assertThat(result).isEqualTo(new MaterialQueryResult.Unavailable(
                MaterialUnavailableReason.SUPPORT_LANGUAGE_NOT_PUBLISHED));
    }

    @Test
    void rejectsNullQueryArguments() {
        assertThatThrownBy(() -> catalog.findByIdentity(null, "zh-cn"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> catalog.findByIdentity(
                new MaterialIdentity("en-builtin-cafe-request", "v1"), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> catalog.listAvailable(null, "zh-cn"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> catalog.listAvailable("en", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsDuplicateIdentityAtConstruction() {
        List<PublishedLearningMaterial> loaded = new ClasspathBuiltInMaterialLoader().load();
        List<PublishedLearningMaterial> duplicated = List.of(loaded.getFirst(), loaded.getFirst());

        assertThatThrownBy(() -> new BuiltInLearningMaterialCatalog(duplicated))
                .isInstanceOf(BuiltInMaterialValidationException.class)
                .hasMessageContaining("duplicate material identity");
    }
}
