package com.dailylanguage.authentication;

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

    private final byte[] sortedFingerprints;

    public LocalPasswordBlocklist() {
        this(readResource(), true);
    }

    LocalPasswordBlocklist(byte[] sortedFingerprints) {
        this(sortedFingerprints, false);
    }

    private LocalPasswordBlocklist(byte[] sortedFingerprints, boolean requirePinnedResource) {
        Objects.requireNonNull(sortedFingerprints, "sortedFingerprints must not be null");
        validateStructure(sortedFingerprints);
        if (requirePinnedResource) {
            validatePinnedResource(sortedFingerprints);
        }
        this.sortedFingerprints = sortedFingerprints.clone();
    }

    boolean contains(String candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        // SHA-256 is only a public blocklist lookup key; stored credentials continue to use Argon2id.
        byte[] candidateFingerprint = sha256(candidate.getBytes(StandardCharsets.UTF_8));

        int low = 0;
        int high = entryCount() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int offset = middle * FINGERPRINT_BYTES;
            int comparison = Arrays.compareUnsigned(
                    candidateFingerprint,
                    0,
                    FINGERPRINT_BYTES,
                    sortedFingerprints,
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
        return sortedFingerprints.length / FINGERPRINT_BYTES;
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

    private static void validateStructure(byte[] fingerprints) {
        if (fingerprints.length == 0 || fingerprints.length % FINGERPRINT_BYTES != 0) {
            throw new IllegalStateException("Local password blocklist has an invalid length");
        }
        for (int offset = FINGERPRINT_BYTES; offset < fingerprints.length; offset += FINGERPRINT_BYTES) {
            int comparison = Arrays.compareUnsigned(
                    fingerprints,
                    offset - FINGERPRINT_BYTES,
                    offset,
                    fingerprints,
                    offset,
                    offset + FINGERPRINT_BYTES);
            if (comparison >= 0) {
                throw new IllegalStateException("Local password blocklist is not strictly sorted");
            }
        }
    }

    private static void validatePinnedResource(byte[] fingerprints) {
        if (fingerprints.length != EXPECTED_ENTRY_COUNT * FINGERPRINT_BYTES) {
            throw new IllegalStateException("Local password blocklist entry count is unexpected");
        }
        String actualSha256 = HexFormat.of().formatHex(sha256(fingerprints));
        // A resource update must not silently change the registration security policy.
        if (!EXPECTED_RESOURCE_SHA256.equals(actualSha256)) {
            throw new IllegalStateException("Local password blocklist checksum is unexpected");
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
