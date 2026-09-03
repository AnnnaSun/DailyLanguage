import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 为 Built-in pack 的 material artifact 计算 manifest contentHash。
 *
 * 默认输出 materials 目录下每个 .json 的 sha256；作者把结果复制进 manifest.json 的对应 entry。
 * 使用 --check 并传入 manifest 路径时验证 manifest 已记录 hash 与磁盘 artifact 一致，不一致则非零退出。
 */
public final class GenerateBuiltInMaterialHash {

    // 工具通过 JDK source-file mode 独立运行，不加载 Backend Jackson classpath；这里只提取 manifest v2
    // 扁平 entry 的 resource/hash 配对，完整 JSON/schema 校验仍由 ClasspathBuiltInMaterialLoader 负责。
    private static final Pattern FLAT_JSON_OBJECT = Pattern.compile("\\{([^{}]*)}", Pattern.DOTALL);
    private static final Pattern RESOURCE_FIELD = Pattern.compile("\\\"resource\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern CONTENT_HASH_FIELD =
            Pattern.compile("\\\"contentHash\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    private GenerateBuiltInMaterialHash() {
    }

    public static void main(String[] arguments) throws IOException {
        boolean checkMode = arguments.length == 3 && "--check".equals(arguments[1]);
        if (arguments.length != 1 && !checkMode) {
            throw new IllegalArgumentException("Usage: <pack root> [--check <manifest.json>]");
        }

        Path packRoot = Path.of(arguments[0]);
        List<Path> materialFiles;
        try (Stream<Path> files = Files.walk(packRoot.resolve("materials"))) {
            materialFiles = files
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }
        if (materialFiles.isEmpty()) {
            throw new IllegalStateException("No material artifacts found under " + packRoot.resolve("materials"));
        }

        int mismatches = 0;
        Map<String, String> remainingManifestHashes = checkMode
                ? readManifestResourceHashes(Path.of(arguments[2]))
                : new LinkedHashMap<>();
        for (Path materialFile : materialFiles) {
            String relativeLocation = packRoot.relativize(materialFile).toString().replace('\\', '/');
            String contentHash = "sha256:" + sha256Hex(Files.readAllBytes(materialFile));
            System.out.println(relativeLocation + "  " + contentHash);
            if (checkMode) {
                String declaredHash = remainingManifestHashes.remove(relativeLocation);
                if (declaredHash == null) {
                    System.out.println("MISMATCH: manifest does not declare resource " + relativeLocation);
                    mismatches++;
                } else if (!declaredHash.equals(contentHash)) {
                    System.out.println("MISMATCH: manifest declares " + declaredHash + " for " + relativeLocation
                            + " but artifact is " + contentHash);
                    mismatches++;
                }
            }
        }
        if (checkMode) {
            for (String missingResource : remainingManifestHashes.keySet()) {
                System.out.println("MISMATCH: manifest resource does not exist: " + missingResource);
                mismatches++;
            }
        }
        if (checkMode && mismatches > 0) {
            System.out.println(mismatches + " hash mismatch(es)");
            System.exit(1);
        }
    }

    private static Map<String, String> readManifestResourceHashes(Path manifestPath) throws IOException {
        String manifestText = Files.readString(manifestPath);
        Map<String, String> resourceHashes = new LinkedHashMap<>();
        Matcher objectMatcher = FLAT_JSON_OBJECT.matcher(manifestText);
        while (objectMatcher.find()) {
            String objectBody = objectMatcher.group(1);
            Matcher resourceMatcher = RESOURCE_FIELD.matcher(objectBody);
            Matcher contentHashMatcher = CONTENT_HASH_FIELD.matcher(objectBody);
            boolean hasResource = resourceMatcher.find();
            boolean hasContentHash = contentHashMatcher.find();
            if (!hasResource && !hasContentHash) {
                continue;
            }
            if (!hasResource || !hasContentHash) {
                throw new IllegalStateException(
                        "manifest entry must declare resource and contentHash together: " + manifestPath);
            }
            String resource = resourceMatcher.group(1);
            String contentHash = contentHashMatcher.group(1);
            if (resourceHashes.putIfAbsent(resource, contentHash) != null) {
                throw new IllegalStateException("duplicate manifest resource: " + resource);
            }
        }
        if (resourceHashes.isEmpty()) {
            throw new IllegalStateException("manifest does not contain resource/contentHash entries: " + manifestPath);
        }
        return resourceHashes;
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
}
