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
        List<PublishedLearningMaterial> materials = new ClasspathBuiltInMaterialLoader().load();

        assertThat(materials)
                .extracting(PublishedLearningMaterial::identity)
                .containsExactlyInAnyOrder(
                        new MaterialIdentity("en-builtin-greeting-intro", "v1"),
                        new MaterialIdentity("en-builtin-cafe-request", "v1"));
    }

    @Test
    void loadsPublishedMaterialWithLineageScaffoldAndTypedSteps() {
        PublishedLearningMaterial material = new ClasspathBuiltInMaterialLoader().load().stream()
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
        List<PublishedLearningMaterial> materials = new ClasspathBuiltInMaterialLoader(
                validPack()::get).load();

        assertThat(materials).hasSize(1);
        assertThat(materials.getFirst().identity()).isEqualTo(new MaterialIdentity("mat-1", "v1"));
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

    @Test
    void rejectsUnsupportedManifestVersion() {
        Map<String, byte[]> pack = validPack();
        pack.put("manifest.json", manifest(materialHash(), "\"manifestVersion\": 1", "\"manifestVersion\": 2")
                .getBytes(StandardCharsets.UTF_8));

        assertRejected(pack, "unsupported built-in material manifestVersion: 2");
    }

    @Test
    void rejectsEmptyMaterialsList() {
        Map<String, byte[]> pack = validPack();
        pack.put("manifest.json", """
                {"manifestVersion": 1, "packId": "pack", "materials": []}
                """.getBytes(StandardCharsets.UTF_8));

        assertRejected(pack, "must declare a non-empty materials list");
    }

    @Test
    void rejectsDuplicateMaterialIdInPack() {
        String materialHash = materialHash();
        Map<String, byte[]> pack = new HashMap<>();
        pack.put("materials/mat-1/v1.json", VALID_MATERIAL.getBytes(StandardCharsets.UTF_8));
        pack.put("manifest.json", ("""
                {
                  "manifestVersion": 1,
                  "packId": "pack",
                  "materials": [
                    %s,
                    %s
                  ]
                }
                """.formatted(manifestEntry(materialHash), manifestEntry(materialHash)))
                .getBytes(StandardCharsets.UTF_8));

        assertRejected(pack, "duplicate materialId in built-in pack: mat-1");
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

    private static String materialHash() {
        return sha256(materialBytes());
    }

    private static String manifest(String contentHash) {
        return manifest(contentHash, null, null);
    }

    private static String manifest(String contentHash, String replaceTarget, String replacement) {
        String manifest = """
                {
                  "manifestVersion": 1,
                  "packId": "pack",
                  "materials": [
                    %s
                  ]
                }
                """.formatted(manifestEntry(contentHash));
        return replaceTarget == null ? manifest : manifest.replace(replaceTarget, replacement);
    }

    private static String manifestEntry(String contentHash) {
        return """
                {
                  "materialId": "mat-1",
                  "publishedVersion": "v1",
                  "targetLanguage": "en",
                  "supportLanguages": ["zh-cn"],
                  "source": "PROJECT_ORIGINAL",
                  "sourceVersion": "1",
                  "license": "AGPL-3.0",
                  "resource": "materials/mat-1/v1.json",
                  "contentHash": "sha256:%s"
                }""".formatted(contentHash);
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
