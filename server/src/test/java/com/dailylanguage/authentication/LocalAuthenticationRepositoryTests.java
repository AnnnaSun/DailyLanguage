package com.dailylanguage.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class LocalAuthenticationRepositoryTests {

    @Test
    void rejectsRawPasswordBeforePersistence() {
        var mapper = new RecordingMapper();
        var repository = new LocalAuthenticationRepository(mapper);

        assertThatThrownBy(() -> repository.create(
                        UUID.randomUUID(),
                        "owner@example.com",
                        "raw-password"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("encodedPasswordVerifier must use argon2id-v1");
        assertThat(mapper.called).isFalse();
    }

    @Test
    void redactsVerifierFromStringRepresentation() {
        String verifier = "{argon2id-v1}$secret-verifier";
        var credential = new LocalAuthenticationCredential(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "owner@example.com",
                verifier);

        assertThat(credential.toString())
                .doesNotContain(verifier)
                .contains("passwordVerifier=<redacted>");
    }

    private static final class RecordingMapper implements LocalAuthenticationMapper {

        private boolean called;

        @Override
        public UUID insertIdentityReturningId(UUID userId, String provider, String providerSubject) {
            called = true;
            return UUID.randomUUID();
        }

        @Override
        public void insertCredential(UUID authIdentityId, String passwordVerifier) {
            called = true;
        }

        @Override
        public Optional<LocalAuthenticationCredential> findCredential(String provider, String providerSubject) {
            called = true;
            return Optional.empty();
        }
    }
}
