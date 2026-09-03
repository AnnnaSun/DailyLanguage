package com.dailylanguage.content.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IllformedLocaleException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.content.domain.MaterialSourceLineage;
import com.dailylanguage.content.domain.PublishedLearningMaterial;
import com.dailylanguage.content.domain.SupportScaffold;
import com.dailylanguage.content.domain.TargetPracticeCore;
import com.dailylanguage.content.domain.TextPracticeStep;

/**
 * Backend-owned classpath Built-in pack adapter：manifest + immutable material artifact 经 strict record
 * binding、SHA-256 lineage 与 fail-closed 校验后成为 typed published material。任何不匹配都在启动期拒绝，
 * 不允许损坏的 published content 进入 runtime。
 */
public final class ClasspathBuiltInMaterialLoader {

    public static final String DEFAULT_ROOT = "content/builtin";
    private static final String MANIFEST_LOCATION = "manifest.json";
    private static final int SUPPORTED_MANIFEST_VERSION = 2;
    private static final int MAX_LANGUAGE_CODE_LENGTH = 35;
    private static final Pattern CONTENT_HASH_PATTERN = Pattern.compile("sha256:[0-9a-f]{64}");

    private final BuiltInMaterialResourceReader resourceReader;
    private final JsonMapper jsonMapper;

    public ClasspathBuiltInMaterialLoader() {
        this(relativeLocation -> readClasspathResource(DEFAULT_ROOT + "/" + relativeLocation));
    }

    ClasspathBuiltInMaterialLoader(BuiltInMaterialResourceReader resourceReader) {
        this.resourceReader = Objects.requireNonNull(resourceReader, "resourceReader must not be null");
        // 与 modelgateway StructuredOutputValidator 相同的 strict feature 集；Content 边界不依赖 Model 基础设施，
        // 缺失 / null 字段改由下方显式 non-blank 校验给出带上下文的 fail-closed 错误。
        this.jsonMapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    public BuiltInMaterialPack load() {
        BuiltInMaterialManifest manifest = bindJson(
                readResource(MANIFEST_LOCATION), BuiltInMaterialManifest.class, MANIFEST_LOCATION);
        validateManifest(manifest);

        List<PublishedLearningMaterial> materials = new ArrayList<>(manifest.materials().size());
        Set<MaterialIdentity> plannableMaterialIdentities = new LinkedHashSet<>();
        for (BuiltInMaterialManifest.Entry entry : manifest.materials()) {
            PublishedLearningMaterial material = loadMaterial(entry);
            materials.add(material);
            if (entry.planningAvailability() == BuiltInMaterialManifest.PlanningAvailability.PLANNABLE) {
                plannableMaterialIdentities.add(material.identity());
            }
        }
        return new BuiltInMaterialPack(materials, plannableMaterialIdentities);
    }

    private PublishedLearningMaterial loadMaterial(BuiltInMaterialManifest.Entry entry) {
        byte[] artifactBytes = readResource(entry.resource());
        // hash 先于结构校验：字节与 manifest 不一致时直接拒绝，不再解析可能被篡改的内容。
        String actualHash = "sha256:" + sha256Hex(artifactBytes);
        if (!actualHash.equals(entry.contentHash())) {
            throw new BuiltInMaterialValidationException("content hash mismatch for material "
                    + entry.materialId() + " (" + entry.resource() + "): manifest declares "
                    + entry.contentHash() + " but artifact is " + actualHash);
        }

        BuiltInMaterialArtifact artifact = bindJson(artifactBytes, BuiltInMaterialArtifact.class, entry.resource());
        validateArtifact(artifact, entry);

        return new PublishedLearningMaterial(
                new MaterialIdentity(artifact.materialId(), artifact.publishedVersion()),
                artifact.targetCore(),
                artifact.supportScaffolds(),
                new MaterialSourceLineage(
                        entry.source(), entry.sourceVersion(), entry.license(), entry.contentHash()));
    }

    private void validateManifest(BuiltInMaterialManifest manifest) {
        if (manifest == null || manifest.materials() == null || manifest.materials().isEmpty()) {
            throw new BuiltInMaterialValidationException(
                    "built-in material manifest must declare a non-empty materials list");
        }
        if (manifest.manifestVersion() != SUPPORTED_MANIFEST_VERSION) {
            throw new BuiltInMaterialValidationException("unsupported built-in material manifestVersion: "
                    + manifest.manifestVersion() + " (expected " + SUPPORTED_MANIFEST_VERSION + ")");
        }
        requireNonBlank(manifest.packId(), "manifest.packId", "manifest");

        Set<MaterialIdentity> materialIdentities = new LinkedHashSet<>();
        Set<String> plannableMaterialIds = new LinkedHashSet<>();
        for (BuiltInMaterialManifest.Entry entry : manifest.materials()) {
            if (entry == null) {
                throw new BuiltInMaterialValidationException("manifest entry must not be null");
            }
            String context = "manifest entry " + requireNonBlank(entry.materialId(), "materialId", "manifest entry");
            requireNonBlank(entry.publishedVersion(), "publishedVersion", context);
            requireCanonicalLanguageCode(entry.targetLanguage(), "targetLanguage", context);
            requireNonBlank(entry.source(), "source", context);
            requireNonBlank(entry.sourceVersion(), "sourceVersion", context);
            requireNonBlank(entry.license(), "license", context);
            requireResourceLocation(entry.resource(), context);
            requireContentHash(entry.contentHash(), context);
            if (entry.planningAvailability() == null) {
                throw new BuiltInMaterialValidationException(context + ": planningAvailability must not be null");
            }

            if (entry.supportLanguages() == null || entry.supportLanguages().isEmpty()) {
                throw new BuiltInMaterialValidationException(context + ": supportLanguages must not be empty");
            }
            Set<String> supportLanguages = new LinkedHashSet<>();
            for (String supportLanguage : entry.supportLanguages()) {
                requireCanonicalLanguageCode(supportLanguage, "supportLanguage", context);
                if (!supportLanguages.add(supportLanguage)) {
                    throw new BuiltInMaterialValidationException(
                            context + ": duplicate supportLanguage " + supportLanguage);
                }
            }

            MaterialIdentity identity = new MaterialIdentity(entry.materialId(), entry.publishedVersion());
            if (!materialIdentities.add(identity)) {
                throw new BuiltInMaterialValidationException(
                        "duplicate material identity in built-in pack: " + identity);
            }
            if (entry.planningAvailability() == BuiltInMaterialManifest.PlanningAvailability.PLANNABLE
                    && !plannableMaterialIds.add(entry.materialId())) {
                throw new BuiltInMaterialValidationException(
                        "multiple plannable versions for materialId: " + entry.materialId());
            }
        }
    }

    private void validateArtifact(BuiltInMaterialArtifact artifact, BuiltInMaterialManifest.Entry entry) {
        String context = "material " + entry.materialId() + " (" + entry.resource() + ")";
        if (artifact == null) {
            throw new BuiltInMaterialValidationException(context + ": artifact must not be null");
        }
        if (!entry.materialId().equals(artifact.materialId())
                || !entry.publishedVersion().equals(artifact.publishedVersion())) {
            throw new BuiltInMaterialValidationException(context + ": artifact identity ("
                    + artifact.materialId() + "/" + artifact.publishedVersion()
                    + ") does not match manifest entry (" + entry.materialId() + "/" + entry.publishedVersion() + ")");
        }

        TargetPracticeCore targetCore = artifact.targetCore();
        if (targetCore == null) {
            throw new BuiltInMaterialValidationException(context + ": targetCore must not be null");
        }
        requireNonBlank(targetCore.scenario(), "targetCore.scenario", context);
        requireNonBlank(targetCore.communicationObjective(), "targetCore.communicationObjective", context);
        requireNonBlank(targetCore.targetLanguageText(), "targetCore.targetLanguageText", context);
        requireNonBlank(targetCore.semanticRubricReference(), "targetCore.semanticRubricReference", context);
        if (!entry.targetLanguage().equals(targetCore.targetLanguage())) {
            throw new BuiltInMaterialValidationException(context + ": targetCore.targetLanguage ("
                    + targetCore.targetLanguage() + ") does not match manifest targetLanguage ("
                    + entry.targetLanguage() + ")");
        }
        if (targetCore.difficulty() == null) {
            throw new BuiltInMaterialValidationException(context + ": targetCore.difficulty must not be null");
        }
        if (targetCore.readingInfo() != null) {
            requireNonBlank(targetCore.readingInfo().readingLine(), "targetCore.readingInfo.readingLine", context);
        }

        if (targetCore.steps() == null || targetCore.steps().isEmpty()) {
            throw new BuiltInMaterialValidationException(context + ": targetCore.steps must not be empty");
        }
        Set<String> stepIds = new LinkedHashSet<>();
        for (TextPracticeStep step : targetCore.steps()) {
            if (step == null) {
                throw new BuiltInMaterialValidationException(context + ": step must not be null");
            }
            String stepContext = context + " step " + requireNonBlank(step.stepId(), "stepId", context);
            if (step.kind() == null) {
                throw new BuiltInMaterialValidationException(stepContext + ": kind must not be null");
            }
            requireNonBlank(step.prompt(), "prompt", stepContext);
            if (!stepIds.add(step.stepId())) {
                throw new BuiltInMaterialValidationException(stepContext + ": duplicate stepId");
            }
            validateAcceptedAnswers(step, stepContext);
        }

        if (artifact.supportScaffolds() == null || artifact.supportScaffolds().isEmpty()) {
            throw new BuiltInMaterialValidationException(context + ": supportScaffolds must not be empty");
        }
        Set<String> scaffoldLanguages = new LinkedHashSet<>();
        for (SupportScaffold scaffold : artifact.supportScaffolds()) {
            if (scaffold == null) {
                throw new BuiltInMaterialValidationException(context + ": supportScaffold must not be null");
            }
            String scaffoldContext = context + " supportScaffold "
                    + requireCanonicalLanguageCode(scaffold.supportLanguage(), "supportLanguage", context);
            requireNonBlank(scaffold.instruction(), "instruction", scaffoldContext);
            requireNonBlank(scaffold.explanation(), "explanation", scaffoldContext);
            requireNonBlank(scaffold.hint(), "hint", scaffoldContext);
            if (scaffold.contrastiveNote() != null && scaffold.contrastiveNote().isBlank()) {
                throw new BuiltInMaterialValidationException(
                        scaffoldContext + ": contrastiveNote must be null or non-blank");
            }
            if (!scaffoldLanguages.add(scaffold.supportLanguage())) {
                throw new BuiltInMaterialValidationException(
                        scaffoldContext + ": duplicate supportLanguage in material");
            }
        }
        if (!scaffoldLanguages.equals(new LinkedHashSet<>(entry.supportLanguages()))) {
            throw new BuiltInMaterialValidationException(context + ": artifact support scaffold languages "
                    + scaffoldLanguages + " do not match manifest supportLanguages " + entry.supportLanguages());
        }
    }

    private void validateAcceptedAnswers(TextPracticeStep step, String stepContext) {
        switch (step.kind()) {
            case EXACT -> {
                if (step.acceptedAnswers() == null || step.acceptedAnswers().isEmpty()) {
                    throw new BuiltInMaterialValidationException(
                            stepContext + ": EXACT step must declare at least one acceptedAnswer");
                }
                for (String acceptedAnswer : step.acceptedAnswers()) {
                    requireNonBlank(acceptedAnswer, "acceptedAnswer", stepContext);
                    // deterministic matching 只做 NFC + 外层空白处理，artifact 必须预先满足该前置条件。
                    if (!Normalizer.isNormalized(acceptedAnswer, Normalizer.Form.NFC)) {
                        throw new BuiltInMaterialValidationException(
                                stepContext + ": acceptedAnswer must be NFC-normalized: " + acceptedAnswer);
                    }
                    if (!acceptedAnswer.strip().equals(acceptedAnswer)) {
                        throw new BuiltInMaterialValidationException(
                                stepContext + ": acceptedAnswer must not have leading or trailing whitespace: "
                                        + acceptedAnswer);
                    }
                }
            }
            case SEMANTIC_ONLY -> {
                if (step.acceptedAnswers() == null || !step.acceptedAnswers().isEmpty()) {
                    throw new BuiltInMaterialValidationException(
                            stepContext + ": SEMANTIC_ONLY step must declare an empty acceptedAnswers list");
                }
            }
        }
    }

    private byte[] readResource(String location) {
        try {
            return resourceReader.read(location);
        } catch (RuntimeException exception) {
            throw new BuiltInMaterialValidationException(
                    "built-in material resource unreadable: " + location, exception);
        }
    }

    private <T> T bindJson(byte[] bytes, Class<T> type, String location) {
        JsonNode root;
        try {
            root = jsonMapper.readTree(bytes);
        } catch (JacksonException exception) {
            throw new BuiltInMaterialValidationException(
                    "malformed JSON in " + location + ": " + exception.getMessage(), exception);
        }
        if (root == null || root.isMissingNode() || !root.isObject()) {
            throw new BuiltInMaterialValidationException("JSON root in " + location + " must be an object");
        }
        try {
            return jsonMapper.treeToValue(root, type);
        } catch (JacksonException exception) {
            throw new BuiltInMaterialValidationException(
                    "invalid JSON structure in " + location + ": " + exception.getMessage(), exception);
        }
    }

    private static String requireNonBlank(String value, String field, String context) {
        if (value == null || value.isBlank()) {
            throw new BuiltInMaterialValidationException(context + ": " + field + " must not be blank");
        }
        return value;
    }

    private static String requireCanonicalLanguageCode(String value, String field, String context) {
        requireNonBlank(value, field, context);
        if (value.length() > MAX_LANGUAGE_CODE_LENGTH) {
            throw new BuiltInMaterialValidationException(
                    context + ": " + field + " exceeds " + MAX_LANGUAGE_CODE_LENGTH + " characters: " + value);
        }
        String canonical;
        try {
            // 与 LanguageProfileRepository.normalizeLanguageCode 相同的 canonical lowercase BCP 47 表示，
            // 保证 catalog 查询可与 persisted languageCode 精确匹配。
            canonical = new Locale.Builder()
                    .setLanguageTag(value)
                    .build()
                    .toLanguageTag()
                    .toLowerCase(Locale.ROOT);
        } catch (IllformedLocaleException exception) {
            throw new BuiltInMaterialValidationException(
                    context + ": " + field + " must be a well-formed BCP 47 language tag: " + value);
        }
        if (!canonical.equals(value)) {
            throw new BuiltInMaterialValidationException(
                    context + ": " + field + " must use canonical lowercase form " + canonical + ": " + value);
        }
        return value;
    }

    private static void requireResourceLocation(String value, String context) {
        requireNonBlank(value, "resource", context);
        if (!value.startsWith("materials/") || !value.endsWith(".json") || value.contains("..")
                || value.startsWith("/") || value.contains("//") || value.contains("\\")) {
            throw new BuiltInMaterialValidationException(
                    context + ": resource must be a pack-relative materials/*.json path: " + value);
        }
    }

    private static void requireContentHash(String value, String context) {
        requireNonBlank(value, "contentHash", context);
        if (!CONTENT_HASH_PATTERN.matcher(value).matches()) {
            throw new BuiltInMaterialValidationException(
                    context + ": contentHash must match sha256:<64 lowercase hex>: " + value);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available", exception);
        }
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private static byte[] readClasspathResource(String location) {
        ClassPathResource resource = new ClassPathResource(location);
        try (InputStream inputStream = resource.getInputStream()) {
            return inputStream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException("classpath resource not readable: " + location, exception);
        }
    }
}
