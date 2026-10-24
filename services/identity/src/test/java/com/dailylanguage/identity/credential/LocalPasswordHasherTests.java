package com.dailylanguage.identity.credential;

import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 使用真实 Argon2；容量替身只直接执行操作，因此本组通过不证明 Human Core 的并发行为。 */
@ExtendWith(OutputCaptureExtension.class)
class LocalPasswordHasherTests {

    private static final String FORMAT_SAMPLE = "{argon2id-v1}$argon2id$v=19$m=19456,t=2,p=1$"
            + "MDEyMzQ1Njc4OWFiY2RlZg$MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY";

    private final PasswordHashConcurrencyGate capacity = mock(PasswordHashConcurrencyGate.class);
    private final LocalPasswordHasher passwords = new LocalPasswordHasher(capacity);

    @BeforeEach
    void isolatePasswordAlgorithmFromPendingHumanCore() {
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get())
                .when(capacity).runWithAvailableSlot(any());
    }

    @Test
    void hashesWithPinnedParametersAndRejectsWrongPassword() {
        String verifier = passwords.hash("r1b synthetic password");
        assertThat(verifier).matches("\\{argon2id-v1}\\$argon2id\\$v=19\\$m=19456,t=2,p=1"
                + "\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}");
        assertThat(passwords.matches("r1b synthetic password", verifier)).isTrue();
        assertThat(passwords.matches("a different password", verifier)).isFalse();
    }

    @Test
    void randomizesSaltAndPreservesPasswordWhitespaceAndCase() {
        String submittedPassword = "  Mixed CASE password!  ";
        String first = passwords.hash(submittedPassword);
        String second = passwords.hash(submittedPassword);
        assertThat(first).isNotEqualTo(second);
        assertThat(passwords.matches(submittedPassword, first)).isTrue();
        assertThat(passwords.matches(submittedPassword, second)).isTrue();
        assertThat(passwords.matches(submittedPassword.strip(), first)).isFalse();
        assertThat(passwords.matches("  mixed case password!  ", first)).isFalse();
    }

    @ParameterizedTest
    @MethodSource("unsupportedVerifiers")
    void rejectsUnsupportedVerifiersBeforeUsingCapacity(String verifier, CapturedOutput output) {
        assertThat(passwords.matches("sensitive-password-sentinel", verifier)).isFalse();
        verifyNoInteractions(capacity);
        assertThat(output).doesNotContain("sensitive-password-sentinel");
        if (verifier != null && !verifier.isEmpty()) {
            assertThat(output).doesNotContain(verifier);
        }
    }

    static Stream<String> unsupportedVerifiers() {
        return Stream.of(null, "", "sensitive-verifier-sentinel", "{noop}plaintext",
                FORMAT_SAMPLE.replace("{argon2id-v1}", "{argon2id-v2}"),
                FORMAT_SAMPLE.substring("{argon2id-v1}".length()),
                FORMAT_SAMPLE.replace("m=19456", "m=99999999"),
                FORMAT_SAMPLE.replace("t=2", "t=99"),
                FORMAT_SAMPLE.replace("p=1", "p=99"),
                FORMAT_SAMPLE.replace("v=19", "v=16"),
                FORMAT_SAMPLE.replace("Zg$", "Zh$"),
                FORMAT_SAMPLE.substring(0, FORMAT_SAMPLE.length() - 1) + "Z",
                FORMAT_SAMPLE + "$extra");
    }

    @Test
    void rejectsNullInputsBeforeUsingCapacity() {
        assertThatThrownBy(() -> passwords.hash(null)).isInstanceOf(NullPointerException.class)
                .hasMessage("submittedPassword must not be null");
        assertThat(passwords.matches(null, FORMAT_SAMPLE)).isFalse();
        verifyNoInteractions(capacity);
    }

    @Test
    void propagatesCapacityExhaustionForHashAndVerification(CapturedOutput output) {
        doThrow(new PasswordHashCapacityExceededException())
                .when(capacity).runWithAvailableSlot(any());
        assertThatThrownBy(() -> passwords.hash("sensitive-password-sentinel"))
                .isInstanceOf(PasswordHashCapacityExceededException.class);
        assertThatThrownBy(() -> passwords.matches("sensitive-password-sentinel", FORMAT_SAMPLE))
                .isInstanceOf(PasswordHashCapacityExceededException.class);
        assertThat(output).doesNotContain("sensitive-password-sentinel").doesNotContain(FORMAT_SAMPLE);
    }
}
