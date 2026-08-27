package com.dailylanguage.authentication.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@ExtendWith(OutputCaptureExtension.class)
class LocalPasswordHasherTests {

    private final LocalPasswordHasher passwordHasher =
            new LocalPasswordHasher(new PasswordHashConcurrencyGate(1));

    @Test
    void hashesWithVersionedArgon2idParameters() {
        String encodedPasswordHash = passwordHasher.hash("correct horse battery staple");

        assertThat(encodedPasswordHash).matches(
                "\\{argon2id-v1}\\$argon2id\\$v=19\\$m=19456,t=2,p=1"
                        + "\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}");
        assertThat(passwordHasher.needsUpgrade(encodedPasswordHash)).isFalse();
    }

    @Test
    void usesRandomSaltForEveryHash() {
        String firstPasswordHash = passwordHasher.hash("same password every time");
        String secondPasswordHash = passwordHasher.hash("same password every time");

        assertThat(firstPasswordHash).isNotEqualTo(secondPasswordHash);
        assertThat(passwordHasher.matches("same password every time", firstPasswordHash)).isTrue();
        assertThat(passwordHasher.matches("same password every time", secondPasswordHash)).isTrue();
    }

    @Test
    void rejectsWrongPassword() {
        String encodedPasswordHash = passwordHasher.hash("the correct password");

        assertThat(passwordHasher.matches("the wrong password", encodedPasswordHash)).isFalse();
    }

    @Test
    void malformedAndUnknownPasswordHashesFailClosedWithoutSensitiveLogging(CapturedOutput output) {
        String malformedPasswordHash =
                "{argon2id-v1}$argon2id$v=19$m=999999,t=2,p=1$not-a-salt$not-a-hash";
        String unknownPasswordHash = "{argon2id-v2}$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA";

        assertThat(passwordHasher.matches("password", malformedPasswordHash)).isFalse();
        assertThat(passwordHasher.matches("password", unknownPasswordHash)).isFalse();
        assertThat(passwordHasher.matches("password", null)).isFalse();
        assertThat(passwordHasher.matches(null, malformedPasswordHash)).isFalse();
        assertThat(passwordHasher.needsUpgrade(malformedPasswordHash)).isFalse();
        assertThat(passwordHasher.needsUpgrade(unknownPasswordHash)).isFalse();
        assertThat(output).doesNotContain(malformedPasswordHash).doesNotContain(unknownPasswordHash);
    }

    @Test
    void passwordHashCannotSelectDifferentArgon2ResourceParameters() {
        String encodedPasswordHash = passwordHasher.hash("resource parameters stay code-owned");
        String weakerMemoryPasswordHash = encodedPasswordHash.replace("m=19456", "m=19455");

        assertThat(weakerMemoryPasswordHash).isNotEqualTo(encodedPasswordHash);
        assertThat(passwordHasher.matches("resource parameters stay code-owned", weakerMemoryPasswordHash))
                .isFalse();
        assertThat(passwordHasher.needsUpgrade(weakerMemoryPasswordHash)).isFalse();
    }

    @Test
    void rejectsNullPasswordWithoutEchoingIt() {
        assertThatNullPointerException()
                .isThrownBy(() -> passwordHasher.hash(null))
                .withMessage("submittedPassword must not be null");
    }
}
