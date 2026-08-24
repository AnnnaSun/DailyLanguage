import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class GenerateLocalPasswordBlocklist {

    private static final String EXPECTED_SOURCE_SHA256 =
            "424a3e03a17df0a2bc2b3ca749d81b04e79d59cb7aeec8876a5a3f308d0caf51";
    private static final int SOURCE_ENTRY_LIMIT = 250_000;
    private static final int EXPECTED_BASELINE_ENTRIES = 2_065;
    private static final int MINIMUM_PASSWORD_LENGTH = 12;
    private static final int MAXIMUM_PASSWORD_LENGTH = 64;
    private static final List<String> CONTEXT_SPECIFIC_PASSWORDS = List.of(
            "DailyLanguage",
            "dailylanguage",
            "DailyLanguage1",
            "dailylanguage1",
            "DailyLanguage!",
            "dailylanguage!");

    private GenerateLocalPasswordBlocklist() {
    }

    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("Usage: <SecLists source> <output asset>");
        }

        Path source = Path.of(arguments[0]);
        Path output = Path.of(arguments[1]);
        requireExpectedSource(source);

        Set<String> filteredPasswords = readFilteredPasswords(source);
        if (filteredPasswords.size() != EXPECTED_BASELINE_ENTRIES) {
            throw new IllegalStateException(
                    "Unexpected filtered baseline entry count: " + filteredPasswords.size());
        }
        filteredPasswords.addAll(CONTEXT_SPECIFIC_PASSWORDS);

        List<byte[]> fingerprints = new ArrayList<>(filteredPasswords.size());
        for (String password : filteredPasswords) {
            fingerprints.add(sha256(password.getBytes(StandardCharsets.US_ASCII)));
        }
        fingerprints.sort(GenerateLocalPasswordBlocklist::compareUnsigned);

        Files.createDirectories(output.toAbsolutePath().getParent());
        try (OutputStream outputStream = Files.newOutputStream(output)) {
            for (byte[] fingerprint : fingerprints) {
                outputStream.write(fingerprint);
            }
        }

        System.out.println("entries=" + fingerprints.size());
        System.out.println("bytes=" + Files.size(output));
        System.out.println("sha256=" + HexFormat.of().formatHex(sha256(Files.readAllBytes(output))));
    }

    private static void requireExpectedSource(Path source) throws IOException {
        byte[] digest;
        try (InputStream inputStream = Files.newInputStream(source)) {
            MessageDigest messageDigest = newSha256();
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                messageDigest.update(buffer, 0, bytesRead);
            }
            digest = messageDigest.digest();
        }

        String actualSha256 = HexFormat.of().formatHex(digest);
        if (!EXPECTED_SOURCE_SHA256.equals(actualSha256)) {
            throw new IllegalArgumentException("SecLists source checksum does not match the pinned release");
        }
    }

    private static Set<String> readFilteredPasswords(Path source) throws IOException {
        Set<String> passwords = new TreeSet<>();
        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            for (int index = 0; index < SOURCE_ENTRY_LIMIT; index++) {
                String candidate = reader.readLine();
                if (candidate == null) {
                    throw new IllegalArgumentException("SecLists source has fewer than 250,000 entries");
                }
                if (isAllowedPassword(candidate)) {
                    passwords.add(candidate);
                }
            }
        }
        return passwords;
    }

    private static boolean isAllowedPassword(String candidate) {
        if (candidate.length() < MINIMUM_PASSWORD_LENGTH
                || candidate.length() > MAXIMUM_PASSWORD_LENGTH) {
            return false;
        }
        for (int index = 0; index < candidate.length(); index++) {
            char character = candidate.charAt(index);
            if (character < 0x20 || character > 0x7e) {
                return false;
            }
        }
        return true;
    }

    private static byte[] sha256(byte[] value) {
        return newSha256().digest(value);
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        return java.util.Arrays.compareUnsigned(left, right);
    }
}
