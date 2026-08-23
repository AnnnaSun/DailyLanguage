package com.dailylanguage.authentication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@ExtendWith(OutputCaptureExtension.class)
class LocalPasswordHasherTests {

    private final LocalPasswordHasher passwordHasher = new LocalPasswordHasher();

    @Test
    void hashesWithVersionedArgon2idParameters() {
        String verifier = passwordHasher.hash("correct horse battery staple");

        assertThat(verifier).matches(
                "\\{argon2id-v1}\\$argon2id\\$v=19\\$m=19456,t=2,p=1"
                        + "\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}");
        assertThat(passwordHasher.needsUpgrade(verifier)).isFalse();
    }

    @Test
    void usesRandomSaltForEveryHash() {
        String firstVerifier = passwordHasher.hash("same password every time");
        String secondVerifier = passwordHasher.hash("same password every time");

        assertThat(firstVerifier).isNotEqualTo(secondVerifier);
        assertThat(passwordHasher.matches("same password every time", firstVerifier)).isTrue();
        assertThat(passwordHasher.matches("same password every time", secondVerifier)).isTrue();
    }

    @Test
    void rejectsWrongPassword() {
        String verifier = passwordHasher.hash("the correct password");

        assertThat(passwordHasher.matches("the wrong password", verifier)).isFalse();
    }

    @Test
    void malformedAndUnknownVerifiersFailClosedWithoutSensitiveLogging(CapturedOutput output) {
        String malformedVerifier = "{argon2id-v1}$argon2id$v=19$m=999999,t=2,p=1$not-a-salt$not-a-hash";
        String unknownVerifier = "{argon2id-v2}$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA";

        assertThat(passwordHasher.matches("password", malformedVerifier)).isFalse();
        assertThat(passwordHasher.matches("password", unknownVerifier)).isFalse();
        assertThat(passwordHasher.matches("password", null)).isFalse();
        assertThat(passwordHasher.matches(null, malformedVerifier)).isFalse();
        assertThat(passwordHasher.needsUpgrade(malformedVerifier)).isFalse();
        assertThat(passwordHasher.needsUpgrade(unknownVerifier)).isFalse();
        assertThat(output).doesNotContain(malformedVerifier).doesNotContain(unknownVerifier);
    }

    @Test
    void verifierCannotSelectDifferentArgon2ResourceParameters() {
        String verifier = passwordHasher.hash("resource parameters stay code-owned");
        String weakerMemoryVerifier = verifier.replace("m=19456", "m=19455");

        assertThat(weakerMemoryVerifier).isNotEqualTo(verifier);
        assertThat(passwordHasher.matches("resource parameters stay code-owned", weakerMemoryVerifier)).isFalse();
        assertThat(passwordHasher.needsUpgrade(weakerMemoryVerifier)).isFalse();
    }

    @Test
    void rejectsNullPasswordWithoutEchoingIt() {
        assertThatNullPointerException()
                .isThrownBy(() -> passwordHasher.hash(null))
                .withMessage("rawPassword must not be null");
    }
}
