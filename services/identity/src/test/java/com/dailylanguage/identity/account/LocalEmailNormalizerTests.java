package com.dailylanguage.identity.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalEmailNormalizerTests {

    @Test
    void stripsWhitespaceAndLowercasesAsciiEmail() {
        assertThat(LocalEmailNormalizer.normalize(" \tAnna.Sun+Study@Example.COM\n"))
                .isEqualTo("anna.sun+study@example.com");
    }

    @Test
    void acceptsExistingLocalDomainAndMaximumLengthRules() {
        assertThat(LocalEmailNormalizer.normalize("owner@localhost")).isEqualTo("owner@localhost");
        String email = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63)
                + "." + "d".repeat(61);
        assertThat(email).hasSize(254);
        assertThat(LocalEmailNormalizer.normalize(email)).isEqualTo(email);
    }

    @Test
    void rejectsEachLengthLimitIndependently() {
        assertInvalid("a".repeat(65) + "@example.com");
        assertInvalid("owner@" + "b".repeat(64) + ".com");
        assertInvalid("a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63)
                + "." + "d".repeat(62));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", "owner@@example.com", "own..er@example.com",
            "owner@-example.com", "owner@K.example"})
    void rejectsMalformedAndNonAsciiEmail(String submittedEmail) {
        assertInvalid(submittedEmail);
    }

    private static void assertInvalid(String submittedEmail) {
        assertThatThrownBy(() -> LocalEmailNormalizer.normalize(submittedEmail))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("email must be a valid ASCII address")
                .hasNoCause();
    }
}
