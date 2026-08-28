package com.dailylanguage.authentication.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LocalEmailNormalizerTests {

    @Test
    void trimsAndLowercasesAsciiEmail() {
        assertThat(LocalEmailNormalizer.normalize(" Anna.Sun+Study@Example.COM "))
                .isEqualTo("anna.sun+study@example.com");
    }

    @Test
    void rejectsNonAsciiAndMalformedAddresses() {
        assertThatThrownBy(() -> LocalEmailNormalizer.normalize("安娜@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalEmailNormalizer.normalize("anna..sun@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalEmailNormalizer.normalize("anna@-example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnicodeCompatibilityCharacterBeforeLowercasing() {
        assertThatThrownBy(() -> LocalEmailNormalizer.normalize("user@K.example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("email must be a valid ASCII address");
    }
}
