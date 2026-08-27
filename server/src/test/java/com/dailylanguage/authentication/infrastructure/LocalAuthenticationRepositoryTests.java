package com.dailylanguage.authentication.infrastructure;

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

        assertThatThrownBy(() -> repository.createLocalEmailIdentity(
                        UUID.randomUUID(),
                        "owner@example.com",
                        "raw-password"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("encodedPasswordHash must use argon2id-v1");
        assertThat(mapper.called).isFalse();
    }

    @Test
    void redactsStoredPasswordHashFromStringRepresentation() {
        String storedPasswordHash = "{argon2id-v1}$secret-password-hash";
        var credential = new StoredLocalPasswordCredential(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "owner@example.com",
                storedPasswordHash);

        assertThat(credential.toString())
                .doesNotContain(storedPasswordHash)
                .contains("storedPasswordHash=<redacted>");
    }

    private static final class RecordingMapper implements LocalAuthenticationMapper {

        private boolean called;

        @Override
        public UUID insertAuthenticationIdentityAndReturnId(
                UUID userId,
                String provider,
                String providerSubject) {
            called = true;
            return UUID.randomUUID();
        }

        @Override
        public void insertLocalPasswordCredential(
                UUID authenticationIdentityId,
                String encodedPasswordHash) {
            called = true;
        }

        @Override
        public Optional<StoredLocalPasswordCredential> findLocalPasswordCredential(
                String provider,
                String providerSubject) {
            called = true;
            return Optional.empty();
        }
    }
}
