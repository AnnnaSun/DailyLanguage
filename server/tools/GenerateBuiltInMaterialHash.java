import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * 为 Built-in pack 的 material artifact 计算 manifest contentHash。
 *
 * 默认输出 materials 目录下每个 .json 的 sha256；作者把结果复制进 manifest.json 的对应 entry。
 * 使用 --check 并传入 manifest 路径时验证 manifest 已记录 hash 与磁盘 artifact 一致，不一致则非零退出。
 */
public final class GenerateBuiltInMaterialHash {

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
        String manifestText = checkMode ? Files.readString(Path.of(arguments[2])) : null;
        for (Path materialFile : materialFiles) {
            String relativeLocation = packRoot.relativize(materialFile).toString().replace('\\', '/');
            String contentHash = "sha256:" + sha256Hex(Files.readAllBytes(materialFile));
            System.out.println(relativeLocation + "  " + contentHash);
            if (checkMode && !manifestText.contains(contentHash)) {
                System.out.println("MISMATCH: manifest does not contain " + contentHash + " for " + relativeLocation);
                mismatches++;
            }
        }
        if (checkMode && mismatches > 0) {
            System.out.println(mismatches + " hash mismatch(es)");
            System.exit(1);
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
}
