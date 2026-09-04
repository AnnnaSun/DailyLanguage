package com.dailylanguage.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.FileNotFoundException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.content.domain.PublishedLearningMaterial;
import com.dailylanguage.content.domain.TextStepKind;

/**
 * 真实 Built-in pack 的加载断言，加上基于 in-memory reader seam 的 fail-closed fixture 矩阵：
 * 通过改写字符串再重算 hash 构造损坏 pack，无需修改 classpath。
 */
class ClasspathBuiltInMaterialLoaderTests {

    private static final String STEP_LINE =
            "\"steps\": [{\"stepId\": \"s1\", \"kind\": \"EXACT\", \"prompt\": \"prompt one\", "
                    + "\"acceptedAnswers\": [\"ok\"]}, {\"stepId\": \"s2\", \"kind\": \"SEMANTIC_ONLY\", "
                    + "\"prompt\": \"prompt two\", \"acceptedAnswers\": []}]";
    private static final String SCAFFOLD_LINE =
            "\"supportScaffolds\": [{\"supportLanguage\": \"zh-cn\", \"instruction\": \"i\", "
                    + "\"explanation\": \"e\", \"hint\": \"h\", \"contrastiveNote\": null}]";
    private static final String VALID_MATERIAL = """
            {
              "materialId": "mat-1",
              "publishedVersion": "v1",
              "targetCore": {
                "targetLanguage": "en",
                "difficulty": "FOUNDATION",
                "scenario": "SCENARIO_A",
                "communicationObjective": "objective",
                "targetLanguageText": "scenario text",
                "readingInfo": null,
                STEPS_PLACEHOLDER
                "semanticRubricReference": "rubric/v1"
              },
              SCAFFOLDS_PLACEHOLDER
            }
            """
            .replace("STEPS_PLACEHOLDER", STEP_LINE + ",")
            .replace("SCAFFOLDS_PLACEHOLDER", SCAFFOLD_LINE);

    @Test
    void loadsBuiltInEnglishPackFromClasspath() {
        BuiltInMaterialPack pack = new ClasspathBuiltInMaterialLoader().load();

        assertThat(pack.materials())
                .extracting(PublishedLearningMaterial::identity)
                .containsExactlyInAnyOrder(
                        new MaterialIdentity("en-builtin-greeting-intro", "v1"),
                        new MaterialIdentity("en-builtin-cafe-request", "v1"));
        assertThat(pack.plannableMaterialIdentities())
                .containsExactlyInAnyOrder(
                        new MaterialIdentity("en-builtin-greeting-intro", "v1"),
                        new MaterialIdentity("en-builtin-cafe-request", "v1"));
    }

    @Test
    void loadsPublishedMaterialWithLineageScaffoldAndTypedSteps() {
        PublishedLearningMaterial material = new ClasspathBuiltInMaterialLoader().load().materials().stream()
                .filter(loaded -> loaded.identity()
                        .equals(new MaterialIdentity("en-builtin-greeting-intro", "v1")))
                .findFirst()
                .orElseThrow();

        assertThat(material.targetCore().targetLanguage()).isEqualTo("en");
        assertThat(material.targetCore().difficulty()).isEqualTo(MaterialDifficulty.FOUNDATION);
        assertThat(material.targetCore().semanticRubricReference())
                .isEqualTo("builtin-text-communication-rubric/v1");
        assertThat(material.supportScaffolds()).hasSize(1);
        assertThat(material.supportScaffolds().getFirst().supportLanguage()).isEqualTo("zh-cn");
        assertThat(material.sourceLineage().source()).isEqualTo("PROJECT_ORIGINAL");
        assertThat(material.sourceLineage().license()).isEqualTo("AGPL-3.0");
        assertThat(material.sourceLineage().contentHash()).startsWith("sha256:");
        assertThat(material.targetCore().steps())
                .extracting(step -> step.kind())
                .containsExactly(TextStepKind.EXACT, TextStepKind.SEMANTIC_ONLY);
        assertThat(material.targetCore().steps().getFirst().acceptedAnswers()).isNotEmpty();
        assertThat(material.targetCore().steps().get(1).acceptedAnswers()).isEmpty();
    }

    @Test
    void acceptsValidInMemoryPack() {
        BuiltInMaterialPack pack = new ClasspathBuiltInMaterialLoader(
                validPack()::get).load();

        assertThat(pack.materials()).hasSize(1);
        assertThat(pack.materials().getFirst().identity()).isEqualTo(new MaterialIdentity("mat-1", "v1"));
        assertThat(pack.plannableMaterialIdentities())
                .containsExactly(new MaterialIdentity("mat-1", "v1"));
    }

    @Test
    void retainsHistoricalVersionWhileMarkingOnlyCurrentVersionPlannable() {
        BuiltInMaterialPack pack = new ClasspathBuiltInMaterialLoader(
                twoVersionPack("HISTORICAL_ONLY", "PLANNABLE")::get).load();

        assertThat(pack.materials())
                .extracting(PublishedLearningMaterial::identity)
                .containsExactly(
                        new MaterialIdentity("mat-1", "v1"),
                        new MaterialIdentity("mat-1", "v2"));
        assertThat(pack.plannableMaterialIdentities())
                .containsExactly(new MaterialIdentity("mat-1", "v2"));
    }

    @Test
    void rejectsMissingManifest() {
        Map<String, byte[]> pack = validPack();
        pack.remove("manifest.json");

        assertRejected(pack, "built-in material resource unreadable: manifest.json");
    }

    @Test
    void rejectsMissingMaterialResource() {
        Map<String, byte[]> pack = validPack();
        pack.remove("materials/mat-1/v1.json");

        assertRejected(pack, "built-in material resource unreadable: materials/mat-1/v1.json");
    }

    @Test
    void rejectsMalformedManifestJson() {
        Map<String, byte[]> pack = validPack();
        pack.put("manifest.json", "{ not json".getBytes(StandardCharsets.UTF_8));

        assertRejected(pack, "malformed JSON in manifest.json");
    }

    @Test
    void rejectsNonObjectManifestRoot() {
        Map<String, byte[]> pack = validPack();
        pack.put("manifest.json", "[]".getBytes(StandardCharsets.UTF_8));

        assertRejected(pack, "JSON root in manifest.json must be an object");
    }

    @Test
    void rejectsUnknownManifestField() {
        assertRejectedWithManifest(
                "\"packId\": \"pack\"", "\"packId\": \"pack\", \"extra\": 1",
                "invalid JSON structure in manifest.json");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void rejectsUnsupportedManifestVersion(int unsupportedVersion) {
        Map<String, byte[]> pack = validPack();
        pack.put("manifest.json", manifest(
                materialHash(),
                "\"manifestVersion\": 2",
                "\"manifestVersion\": " + unsupportedVersion)
                .getBytes(StandardCharsets.UTF_8));

        assertRejected(pack, "unsupported built-in material manifestVersion: " + unsupportedVersion);
    }

    @Test
    void rejectsEmptyMaterialsList() {
        Map<String, byte[]> pack = validPack();
        pack.put("manifest.json", """
                {"manifestVersion": 2, "packId": "pack", "materials": []}
                """.getBytes(StandardCharsets.UTF_8));

        assertRejected(pack, "must declare a non-empty materials list");
    }

    @Test
    void rejectsDuplicateMaterialIdentityInPack() {
        String materialHash = materialHash();
        Map<String, byte[]> pack = new HashMap<>();
        pack.put("materials/mat-1/v1.json", VALID_MATERIAL.getBytes(StandardCharsets.UTF_8));
        pack.put("manifest.json", ("""
                {
                  "manifestVersion": 2,
                  "packId": "pack",
                  "materials": [
                    %s,
                    %s
                  ]
                }
                """.formatted(manifestEntry(materialHash), manifestEntry(materialHash)))
                .getBytes(StandardCharsets.UTF_8));

        assertRejected(pack, "duplicate material identity in built-in pack");
    }

    @Test
    void rejectsMultiplePlannableVersionsForSameMaterialId() {
        assertRejected(
                twoVersionPack("PLANNABLE", "PLANNABLE"),
                "multiple plannable versions for materialId: mat-1");
    }

    @Test
    void rejectsNullPlanningAvailability() {
        assertRejectedWithManifest(
                "\"planningAvailability\": \"PLANNABLE\"", "\"planningAvailability\": null",
                "planningAvailability must not be null");
    }

    @Test
    void rejectsMalformedContentHash() {
        Map<String, byte[]> pack = validPack();
        pack.put("manifest.json", manifest("not-hex").getBytes(StandardCharsets.UTF_8));

        assertRejected(pack, "contentHash must match sha256:<64 lowercase hex>");
    }

    @Test
    void rejectsNonCanonicalLanguageCode() {
        assertRejectedWithManifest(
                "\"targetLanguage\": \"en\"", "\"targetLanguage\": \"EN\"",
                "must use canonical lowercase form en");
    }

    @Test
    void rejectsResourcePathTraversal() {
        assertRejectedWithManifest(
                "\"resource\": \"materials/mat-1/v1.json\"", "\"resource\": \"../secrets.json\"",
                "resource must be a pack-relative materials/*.json path");
    }

    @Test
    void rejectsEntryWithoutSupportLanguages() {
        assertRejectedWithManifest(
                "\"supportLanguages\": [\"zh-cn\"]", "\"supportLanguages\": []",
                "supportLanguages must not be empty");
    }

    @Test
    void rejectsContentHashMismatch() {
        Map<String, byte[]> pack = validPack();
        pack.put("materials/mat-1/v1.json",
                VALID_MATERIAL.replace("\"scenario text\"", "\"tampered text\"")
                        .getBytes(StandardCharsets.UTF_8));

        assertRejected(pack, "content hash mismatch for material mat-1");
    }

    @Test
    void rejectsArtifactIdentityMismatch() {
        assertRejectedWithMaterial(
                material().replace("\"materialId\": \"mat-1\"", "\"materialId\": \"other\""),
                "does not match manifest entry");
    }

    @Test
    void rejectsTargetLanguageMismatchBetweenArtifactAndManifest() {
        assertRejectedWithMaterial(
                material().replace("\"targetLanguage\": \"en\"", "\"targetLanguage\": \"ja\""),
                "does not match manifest targetLanguage");
    }

    @Test
    void rejectsUnknownArtifactField() {
        assertRejectedWithMaterial(
                material().replace("\"publishedVersion\": \"v1\",",
                        "\"publishedVersion\": \"v1\", \"surprise\": true,"),
                "invalid JSON structure in materials/mat-1/v1.json");
    }

    @Test
    void rejectsInvalidDifficultyEnum() {
        assertRejectedWithMaterial(
                material().replace("\"FOUNDATION\"", "\"ADVANCED_PLUS\""),
                "invalid JSON structure in materials/mat-1/v1.json");
    }

    @Test
    void rejectsMissingTargetCore() {
        assertRejectedWithMaterial(
                "{\"materialId\": \"mat-1\", \"publishedVersion\": \"v1\"}\n",
                "targetCore must not be null");
    }

    @Test
    void rejectsEmptySteps() {
        assertRejectedWithMaterial(
                material().replace(STEP_LINE, "\"steps\": []"),
                "targetCore.steps must not be empty");
    }

    @Test
    void rejectsDuplicateStepId() {
        assertRejectedWithMaterial(
                material().replace("\"stepId\": \"s2\"", "\"stepId\": \"s1\""),
                "duplicate stepId");
    }

    @Test
    void rejectsBlankStepPrompt() {
        assertRejectedWithMaterial(
                material().replace("\"prompt\": \"prompt two\"", "\"prompt\": \" \""),
                "prompt must not be blank");
    }

    @Test
    void rejectsExactStepWithoutAcceptedAnswers() {
        assertRejectedWithMaterial(
                material().replace("\"acceptedAnswers\": [\"ok\"]", "\"acceptedAnswers\": []"),
                "EXACT step must declare at least one acceptedAnswer");
    }

    @Test
    void rejectsSemanticStepWithAcceptedAnswers() {
        assertRejectedWithMaterial(
                material().replace(
                        "\"prompt\": \"prompt two\", \"acceptedAnswers\": []",
                        "\"prompt\": \"prompt two\", \"acceptedAnswers\": [\"ok\"]"),
                "SEMANTIC_ONLY step must declare an empty acceptedAnswers list");
    }

    @Test
    void rejectsNonNfcAcceptedAnswer() {
        // "e" + combining acute 是非 NFC 序列，load 期 fail closed。
        assertRejectedWithMaterial(
                material().replace(
                        "\"acceptedAnswers\": [\"ok\"]",
                        "\"acceptedAnswers\": [\"caf" + "e\u0301\"]"),
                "must be NFC-normalized");
    }

    @Test
    void rejectsWhitespacePaddedAcceptedAnswer() {
        assertRejectedWithMaterial(
                material().replace("\"acceptedAnswers\": [\"ok\"]", "\"acceptedAnswers\": [\" ok \"]"),
                "must not have leading or trailing whitespace");
    }

    @Test
    void rejectsScaffoldSetMismatchWithManifest() {
        assertRejectedWithManifest(
                "\"supportLanguages\": [\"zh-cn\"]", "\"supportLanguages\": [\"zh-cn\", \"ja\"]",
                "do not match manifest supportLanguages");
    }

    @Test
    void rejectsDuplicateScaffoldLanguageInMaterial() {
        assertRejectedWithMaterial(
                material().replace(SCAFFOLD_LINE, "\"supportScaffolds\": ["
                        + "{\"supportLanguage\": \"zh-cn\", \"instruction\": \"i\", \"explanation\": \"e\", "
                        + "\"hint\": \"h\", \"contrastiveNote\": null},"
                        + "{\"supportLanguage\": \"zh-cn\", \"instruction\": \"i2\", \"explanation\": \"e2\", "
                        + "\"hint\": \"h2\", \"contrastiveNote\": null}]"),
                "duplicate supportLanguage in material");
    }

    @Test
    void rejectsBlankScaffoldHint() {
        assertRejectedWithMaterial(
                material().replace("\"hint\": \"h\"", "\"hint\": \"\""),
                "hint must not be blank");
    }

    private static String material() {
        return VALID_MATERIAL;
    }

    private static byte[] materialBytes() {
        return VALID_MATERIAL.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> validPack() {
        Map<String, byte[]> pack = new HashMap<>();
        pack.put("materials/mat-1/v1.json", materialBytes());
        pack.put("manifest.json", manifest(materialHash()).getBytes(StandardCharsets.UTF_8));
        return pack;
    }

    private static Map<String, byte[]> twoVersionPack(String v1Availability, String v2Availability) {
        byte[] v1Material = materialBytes();
        byte[] v2Material = VALID_MATERIAL
                .replace("\"publishedVersion\": \"v1\"", "\"publishedVersion\": \"v2\"")
                .getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> pack = new HashMap<>();
        pack.put("materials/mat-1/v1.json", v1Material);
        pack.put("materials/mat-1/v2.json", v2Material);
        pack.put("manifest.json", ("""
                {
                  "manifestVersion": 2,
                  "packId": "pack",
                  "materials": [
                    %s,
                    %s
                  ]
                }
                """.formatted(
                        manifestEntry("v1", "materials/mat-1/v1.json", sha256(v1Material), v1Availability),
                        manifestEntry("v2", "materials/mat-1/v2.json", sha256(v2Material), v2Availability)))
                .getBytes(StandardCharsets.UTF_8));
        return pack;
    }

    private static String materialHash() {
        return sha256(materialBytes());
    }

    private static String manifest(String contentHash) {
        return manifest(contentHash, null, null);
    }

    private static String manifest(String contentHash, String replaceTarget, String replacement) {
        String manifest = """
                {
                  "manifestVersion": 2,
                  "packId": "pack",
                  "materials": [
                    %s
                  ]
                }
                """.formatted(manifestEntry(contentHash));
        return replaceTarget == null ? manifest : manifest.replace(replaceTarget, replacement);
    }

    private static String manifestEntry(String contentHash) {
        return manifestEntry("v1", "materials/mat-1/v1.json", contentHash, "PLANNABLE");
    }

    private static String manifestEntry(
            String publishedVersion,
            String resource,
            String contentHash,
            String planningAvailability
    ) {
        return """
                {
                  "materialId": "mat-1",
                  "publishedVersion": "%s",
                  "targetLanguage": "en",
                  "supportLanguages": ["zh-cn"],
                  "source": "PROJECT_ORIGINAL",
                  "sourceVersion": "1",
                  "license": "AGPL-3.0",
                  "resource": "%s",
                  "contentHash": "sha256:%s",
                  "planningAvailability": "%s"
                }""".formatted(publishedVersion, resource, contentHash, planningAvailability);
    }

    private static void assertRejectedWithManifest(
            String replaceTarget, String replacement, String expectedMessagePart) {
        Map<String, byte[]> pack = validPack();
        pack.put("manifest.json",
                manifest(materialHash(), replaceTarget, replacement).getBytes(StandardCharsets.UTF_8));

        assertRejected(pack, expectedMessagePart);
    }

    private static void assertRejectedWithMaterial(String materialJson, String expectedMessagePart) {
        byte[] material = materialJson.getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> pack = new HashMap<>();
        pack.put("materials/mat-1/v1.json", material);
        pack.put("manifest.json", manifest(sha256(material)).getBytes(StandardCharsets.UTF_8));

        assertRejected(pack, expectedMessagePart);
    }

    private static void assertRejected(Map<String, byte[]> pack, String expectedMessagePart) {
        assertThatThrownBy(() -> new ClasspathBuiltInMaterialLoader(readerFor(pack)).load())
                .isInstanceOf(BuiltInMaterialValidationException.class)
                .hasMessageContaining(expectedMessagePart);
    }

    private static BuiltInMaterialResourceReader readerFor(Map<String, byte[]> pack) {
        return location -> {
            byte[] content = pack.get(location);
            if (content == null) {
                throw new UncheckedIOException(
                        "resource not found: " + location, new FileNotFoundException(location));
            }
            return content;
        };
    }

    private static String sha256(byte[] bytes) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available", exception);
        }
        return HexFormat.of().formatHex(digest.digest(bytes));
    }
}
