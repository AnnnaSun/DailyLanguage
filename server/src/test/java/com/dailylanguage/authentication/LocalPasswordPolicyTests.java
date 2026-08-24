package com.dailylanguage.authentication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static com.dailylanguage.authentication.LocalPasswordPolicy.ValidationResult.ACCEPTED;
import static com.dailylanguage.authentication.LocalPasswordPolicy.ValidationResult.COMMON_OR_COMPROMISED;
import static com.dailylanguage.authentication.LocalPasswordPolicy.ValidationResult.INVALID_CHARACTER;
import static com.dailylanguage.authentication.LocalPasswordPolicy.ValidationResult.INVALID_LENGTH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@ExtendWith(OutputCaptureExtension.class)
class LocalPasswordPolicyTests {

    private static final String NORMALIZED_EMAIL = "verylonguser@example.com";

    private final LocalPasswordPolicy passwordPolicy =
            new LocalPasswordPolicy(new LocalPasswordBlocklist());

    @Test
    void acceptsInclusiveTwelveToSixtyFourCharacterBoundary() {
        assertThat(passwordPolicy.validate("safe-pass-12", NORMALIZED_EMAIL)).isEqualTo(ACCEPTED);
        assertThat(passwordPolicy.validate("Z".repeat(64), NORMALIZED_EMAIL)).isEqualTo(ACCEPTED);
        assertThat(passwordPolicy.validate("abcdefghijk", NORMALIZED_EMAIL)).isEqualTo(INVALID_LENGTH);
        assertThat(passwordPolicy.validate("Z".repeat(65), NORMALIZED_EMAIL)).isEqualTo(INVALID_LENGTH);
        assertThat(passwordPolicy.validate(null, NORMALIZED_EMAIL)).isEqualTo(INVALID_LENGTH);
    }

    @Test
    void acceptsEveryPrintableAsciiCharacterIncludingSpace() {
        for (char character = 0x20; character <= 0x7e; character++) {
            String submittedPassword = "SecureBase12" + character;

            assertThat(passwordPolicy.validate(submittedPassword, NORMALIZED_EMAIL))
                    .as("printable ASCII U+%04X", (int) character)
                    .isEqualTo(ACCEPTED);
        }
        assertThat(passwordPolicy.validate("  safe pass  ", NORMALIZED_EMAIL)).isEqualTo(ACCEPTED);
    }

    @Test
    void rejectsControlUnicodeFullWidthAndEmojiCharactersBeforeLengthFeedback() {
        assertThat(passwordPolicy.validate("safe-pass-1\t", NORMALIZED_EMAIL)).isEqualTo(INVALID_CHARACTER);
        assertThat(passwordPolicy.validate("safe-pass-1\n", NORMALIZED_EMAIL)).isEqualTo(INVALID_CHARACTER);
        assertThat(passwordPolicy.validate("safe-pass-1\u007f", NORMALIZED_EMAIL)).isEqualTo(INVALID_CHARACTER);
        assertThat(passwordPolicy.validate("safe-pass-1中", NORMALIZED_EMAIL)).isEqualTo(INVALID_CHARACTER);
        assertThat(passwordPolicy.validate("safe-pass-1Ａ", NORMALIZED_EMAIL)).isEqualTo(INVALID_CHARACTER);
        assertThat(passwordPolicy.validate("safe-pass-1🙂", NORMALIZED_EMAIL)).isEqualTo(INVALID_CHARACTER);
        assertThat(passwordPolicy.validate("safe-中", NORMALIZED_EMAIL)).isEqualTo(INVALID_CHARACTER);
    }

    @Test
    void rejectsKnownAndContextSpecificWeakPasswords() {
        assertThat(passwordPolicy.validate("123456789012", NORMALIZED_EMAIL))
                .isEqualTo(COMMON_OR_COMPROMISED);
        assertThat(passwordPolicy.validate("DailyLanguage", NORMALIZED_EMAIL))
                .isEqualTo(COMMON_OR_COMPROMISED);
    }

    @Test
    void rejectsExactLoginEmailAndLocalPartWithoutCaseFolding() {
        assertThat(passwordPolicy.validate(NORMALIZED_EMAIL, NORMALIZED_EMAIL))
                .isEqualTo(COMMON_OR_COMPROMISED);
        assertThat(passwordPolicy.validate("verylonguser", NORMALIZED_EMAIL))
                .isEqualTo(COMMON_OR_COMPROMISED);
        assertThat(passwordPolicy.validate("VeryLongUser", NORMALIZED_EMAIL)).isEqualTo(ACCEPTED);
    }

    @Test
    void requiresNormalizedEmailAfterCandidateSyntaxIsValid() {
        assertThatNullPointerException()
                .isThrownBy(() -> passwordPolicy.validate("safe-pass-12", null))
                .withMessage("normalizedEmail must not be null");
    }

    @Test
    void validationDoesNotLogRawPassword(CapturedOutput output) {
        String submittedPassword = "submitted-password-must-not-leak";

        passwordPolicy.validate(submittedPassword, NORMALIZED_EMAIL);

        assertThat(output).doesNotContain(submittedPassword);
    }
}
