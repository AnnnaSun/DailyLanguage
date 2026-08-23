package com.dailylanguage.authentication;

import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class LocalPasswordBlocklistTests {

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
                .isThrownBy(() -> new LocalPasswordBlocklist(new byte[31]))
                .withMessage("Local password blocklist has an invalid length");
        assertThatIllegalStateException()
                .isThrownBy(() -> new LocalPasswordBlocklist(new byte[64]))
                .withMessage("Local password blocklist is not strictly sorted");
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
}
