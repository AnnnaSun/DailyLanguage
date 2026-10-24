package com.dailylanguage.identity.credential;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalPasswordBlocklistTests {

    private static final String RESOURCE_NAME = "security/local-password-blocklist-v1.bin";

    private final LocalPasswordBlocklist blocklist = new LocalPasswordBlocklist();

    @Test
    void loadsPinnedSortedFingerprintResource() {
        assertThat(blocklist.entryCount()).isEqualTo(2_071);
        assertThat(blocklist.contains("123456789012")).isTrue();
        assertThat(blocklist.contains("qwertyuiop12")).isTrue();
        assertThat(blocklist.contains("abcdefghijkl")).isTrue();
        assertThat(blocklist.contains("DailyLanguage")).isTrue();
    }

    @Test
    void doesNotMatchPasswordOutsideTheBlocklist() {
        assertThat(blocklist.contains("correct horse battery staple")).isFalse();
    }

    @Test
    void malformedOrUnsortedFingerprintDataFailsClosed() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new LocalPasswordBlocklist(new byte[0]))
                .withMessage("Local password blocklist has an invalid length");
        assertThatIllegalStateException()
                .isThrownBy(() -> new LocalPasswordBlocklist(new byte[31]))
                .withMessage("Local password blocklist has an invalid length");
        assertThatIllegalStateException()
                .isThrownBy(() -> new LocalPasswordBlocklist(new byte[64]))
                .withMessage("Local password blocklist is not strictly sorted");
    }

    @Test
    void rejectsNullCandidate() {
        assertThatNullPointerException().isThrownBy(() -> blocklist.contains(null));
    }

    @Test
    void copiesSuppliedFingerprintBytes() throws Exception {
        byte[] fingerprint = MessageDigest.getInstance("SHA-256")
                .digest("synthetic password".getBytes(StandardCharsets.UTF_8));
        var copiedBlocklist = new LocalPasswordBlocklist(fingerprint);
        Arrays.fill(fingerprint, (byte) 0);

        assertThat(copiedBlocklist.contains("synthetic password")).isTrue();
    }

    @Test
    void immutableBlocklistSupportsConcurrentReads() {
        boolean allResultsCorrect = IntStream.range(0, 1_000)
                .parallel()
                .allMatch(index -> index % 2 == 0
                        ? blocklist.contains("123456789012")
                        : !blocklist.contains("a password not in the list"));

        assertThat(allResultsCorrect).isTrue();
    }

    @Test
    void startsRealPolicyComponentsWithPinnedResource() {
        new ApplicationContextRunner()
                .withBean(LocalPasswordBlocklist.class)
                .withBean(LocalPasswordPolicy.class)
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(LocalPasswordBlocklist.class)
                        .hasSingleBean(LocalPasswordPolicy.class));
    }

    @ParameterizedTest(name = "{index}: {1}")
    @MethodSource("invalidProductionResources")
    void rejectsMissingOrCorruptResourceInProductionConstructor(byte[] resourceBytes, String expectedMessage)
            throws Exception {
        String className = LocalPasswordBlocklist.class.getName();
        byte[] classBytes;
        try (InputStream input = LocalPasswordBlocklist.class.getResourceAsStream(
                "/" + className.replace('.', '/') + ".class")) {
            classBytes = input.readAllBytes();
        }
        // 仅替换测试 ClassLoader 的资源，不写坏工作区或为 production 增加可关闭校验的配置。
        var resourceLoader = new ClassLoader(LocalPasswordBlocklist.class.getClassLoader()) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (RESOURCE_NAME.equals(name)) {
                    return resourceBytes == null ? null : new ByteArrayInputStream(resourceBytes);
                }
                return super.getResourceAsStream(name);
            }

            Class<?> defineBlocklistClass() {
                return defineClass(className, classBytes, 0, classBytes.length);
            }
        };
        Class<?> isolatedBlocklistClass = resourceLoader.defineBlocklistClass();

        assertThatThrownBy(() -> isolatedBlocklistClass.getConstructor().newInstance())
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .cause().hasMessage(expectedMessage);
    }

    static Stream<Arguments> invalidProductionResources() throws IOException {
        byte[] resourceBytes;
        try (InputStream input = LocalPasswordBlocklist.class.getResourceAsStream("/" + RESOURCE_NAME)) {
            resourceBytes = input.readAllBytes();
        }
        byte[] wrongChecksum = resourceBytes.clone();
        // 保持条目数量与严格排序，单独触发 checksum 校验。
        Arrays.fill(wrongChecksum, 0, 32, (byte) 0);
        return Stream.of(
                Arguments.of(null, "Local password blocklist resource is missing"),
                Arguments.of(new byte[31], "Local password blocklist has an invalid length"),
                Arguments.of(new byte[64], "Local password blocklist is not strictly sorted"),
                Arguments.of(Arrays.copyOf(resourceBytes, resourceBytes.length - 32),
                        "Local password blocklist entry count is unexpected"),
                Arguments.of(wrongChecksum, "Local password blocklist checksum is unexpected"));
    }
}
