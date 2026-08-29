package com.dailylanguage.authentication.domain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

import org.springframework.stereotype.Component;

@Component
public final class LocalPasswordBlocklist {

    private static final String RESOURCE_PATH = "/security/local-password-blocklist-v1.bin";
    private static final int FINGERPRINT_BYTES = 32;
    private static final int EXPECTED_ENTRY_COUNT = 2_071;
    private static final String EXPECTED_RESOURCE_SHA256 =
            "dc4260507736ce8464d32b760097c38e105c66158fb8a9192e0a324d25929aa8";

    private final byte[] sortedBlockedPasswordFingerprints;

    public LocalPasswordBlocklist() {
        this(readResource(), true);
    }

    LocalPasswordBlocklist(byte[] sortedBlockedPasswordFingerprints) {
        this(sortedBlockedPasswordFingerprints, false);
    }

    private LocalPasswordBlocklist(byte[] sortedBlockedPasswordFingerprints, boolean requirePinnedResource) {
        Objects.requireNonNull(
                sortedBlockedPasswordFingerprints,
                "sortedBlockedPasswordFingerprints must not be null");
        validateStructure(sortedBlockedPasswordFingerprints);
        if (requirePinnedResource) {
            validatePinnedResource(sortedBlockedPasswordFingerprints);
        }
        this.sortedBlockedPasswordFingerprints = sortedBlockedPasswordFingerprints.clone();
    }

    /**
     * 只检查应用管理 password credential 的 submitted password。
     * External provider password 与 phone OTP value 不进入该 blocklist。
     */
    boolean contains(String submittedPassword) {
        Objects.requireNonNull(submittedPassword, "submittedPassword must not be null");
        // SHA-256 只作为公开 blocklist lookup key；stored credential 仍必须使用 Argon2id。
        byte[] submittedPasswordFingerprint = sha256(submittedPassword.getBytes(StandardCharsets.UTF_8));

        int low = 0;
        int high = entryCount() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int offset = middle * FINGERPRINT_BYTES;
            int comparison = Arrays.compareUnsigned(
                    submittedPasswordFingerprint,
                    0,
                    FINGERPRINT_BYTES,
                    sortedBlockedPasswordFingerprints,
                    offset,
                    offset + FINGERPRINT_BYTES);
            if (comparison == 0) {
                return true;
            }
            if (comparison < 0) {
                high = middle - 1;
            }
            else {
                low = middle + 1;
            }
        }
        return false;
    }

    int entryCount() {
        return sortedBlockedPasswordFingerprints.length / FINGERPRINT_BYTES;
    }

    private static byte[] readResource() {
        try (InputStream inputStream = LocalPasswordBlocklist.class.getResourceAsStream(RESOURCE_PATH)) {
            if (inputStream == null) {
                throw new IllegalStateException("Local password blocklist resource is missing");
            }
            return inputStream.readAllBytes();
        }
        catch (IOException exception) {
            throw new IllegalStateException("Local password blocklist resource could not be read", exception);
        }
    }

    private static void validateStructure(byte[] blockedPasswordFingerprints) {
        if (blockedPasswordFingerprints.length == 0
                || blockedPasswordFingerprints.length % FINGERPRINT_BYTES != 0) {
            throw new IllegalStateException("Local password blocklist has an invalid length");
        }
        for (int offset = FINGERPRINT_BYTES;
                offset < blockedPasswordFingerprints.length;
                offset += FINGERPRINT_BYTES) {
            int comparison = Arrays.compareUnsigned(
                    blockedPasswordFingerprints,
                    offset - FINGERPRINT_BYTES,
                    offset,
                    blockedPasswordFingerprints,
                    offset,
                    offset + FINGERPRINT_BYTES);
            if (comparison >= 0) {
                throw new IllegalStateException("Local password blocklist is not strictly sorted");
            }
        }
    }

    private static void validatePinnedResource(byte[] blockedPasswordFingerprints) {
        if (blockedPasswordFingerprints.length != EXPECTED_ENTRY_COUNT * FINGERPRINT_BYTES) {
            throw new IllegalStateException("Local password blocklist entry count is unexpected");
        }
        String actualResourceSha256 = HexFormat.of().formatHex(sha256(blockedPasswordFingerprints));
        // resource 更新不得静默改变 registration security policy。
        if (!EXPECTED_RESOURCE_SHA256.equals(actualResourceSha256)) {
            throw new IllegalStateException("Local password blocklist checksum is unexpected");
        }
    }

    private static byte[] sha256(byte[] bytesToHash) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytesToHash);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
