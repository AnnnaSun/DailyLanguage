package com.dailylanguage.identity.credential.domain;

import org.junit.jupiter.api.Test;

import static com.dailylanguage.identity.credential.domain.LocalPasswordPolicy.ValidationResult.ACCEPTED;
import static com.dailylanguage.identity.credential.domain.LocalPasswordPolicy.ValidationResult.COMMON_OR_COMPROMISED;
import static com.dailylanguage.identity.credential.domain.LocalPasswordPolicy.ValidationResult.INVALID_CHARACTER;
import static com.dailylanguage.identity.credential.domain.LocalPasswordPolicy.ValidationResult.INVALID_LENGTH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

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
    void acceptsPrintableAsciiIncludingSpaceAndPunctuation() {
        assertThat(passwordPolicy.validate("  safe pass ~!  ", NORMALIZED_EMAIL)).isEqualTo(ACCEPTED);
    }

    @Test
    void rejectsNonPrintableCharactersBeforeLengthFeedback() {
        assertThat(passwordPolicy.validate("safe-pass-1\t", NORMALIZED_EMAIL)).isEqualTo(INVALID_CHARACTER);
        assertThat(passwordPolicy.validate("safe-pass-1\u007f", NORMALIZED_EMAIL)).isEqualTo(INVALID_CHARACTER);
        assertThat(passwordPolicy.validate("safe-中", NORMALIZED_EMAIL)).isEqualTo(INVALID_CHARACTER);
    }

    @Test
    void rejectsBlocklistedPassword() {
        assertThat(passwordPolicy.validate("123456789012", NORMALIZED_EMAIL))
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
    void rejectsInvalidSyntaxBeforeReadingBlocklistOrRequiringEmail() {
        var unreadBlocklist = mock(LocalPasswordBlocklist.class);
        var policy = new LocalPasswordPolicy(unreadBlocklist);

        assertThat(policy.validate(null, null)).isEqualTo(INVALID_LENGTH);
        assertThat(policy.validate("short", null)).isEqualTo(INVALID_LENGTH);
        assertThat(policy.validate("short中", null)).isEqualTo(INVALID_CHARACTER);
        verifyNoInteractions(unreadBlocklist);
    }

}
