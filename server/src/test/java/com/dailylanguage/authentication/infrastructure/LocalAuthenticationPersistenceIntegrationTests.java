package com.dailylanguage.authentication.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.user.infrastructure.UserRepository;

@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class LocalAuthenticationPersistenceIntegrationTests {

    private static final String ENCODED_PASSWORD_HASH =
            "{argon2id-v1}$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LocalAuthenticationRepository localAuthenticationRepository;

    @Autowired
    private LocalAuthenticationMapper localAuthenticationMapper;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsUuidV7IdentityAndFindsNormalizedCredential() {
        UUID userId = userRepository.create();

        UUID authenticationIdentityId = localAuthenticationRepository.createLocalEmailIdentity(
                userId,
                " Owner@Example.COM ",
                ENCODED_PASSWORD_HASH);

        assertThat(authenticationIdentityId.version()).isEqualTo(7);
        assertThat(localAuthenticationRepository.findByEmail("owner@example.com"))
                .contains(new StoredLocalPasswordCredential(
                        authenticationIdentityId,
                        userId,
                        "owner@example.com",
                        ENCODED_PASSWORD_HASH));
    }

    @Test
    void rejectsDuplicateNormalizedIdentityAcrossUsers() {
        UUID firstUserId = userRepository.create();
        UUID secondUserId = userRepository.create();
        localAuthenticationRepository.createLocalEmailIdentity(
                firstUserId,
                "owner@example.com",
                ENCODED_PASSWORD_HASH);

        assertThatThrownBy(() -> localAuthenticationRepository.createLocalEmailIdentity(
                        secondUserId,
                        " OWNER@EXAMPLE.COM ",
                        ENCODED_PASSWORD_HASH))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsIdentityWithoutExistingUser() {
        assertThatThrownBy(() -> localAuthenticationRepository.createLocalEmailIdentity(
                        UUID.randomUUID(),
                        "missing@example.com",
                        ENCODED_PASSWORD_HASH))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsSecondCredentialForOneIdentity() {
        UUID userId = userRepository.create();
        UUID authenticationIdentityId = localAuthenticationRepository.createLocalEmailIdentity(
                userId,
                "owner@example.com",
                ENCODED_PASSWORD_HASH);

        assertThatThrownBy(() -> localAuthenticationMapper.insertLocalPasswordCredential(
                        authenticationIdentityId,
                        ENCODED_PASSWORD_HASH))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rollsBackIdentityWhenCredentialInsertFails() {
        UUID userId = userRepository.create();
        String invalidDatabaseText = ENCODED_PASSWORD_HASH + '\0';

        assertThatThrownBy(() -> localAuthenticationRepository.createLocalEmailIdentity(
                        userId,
                        "rollback@example.com",
                        invalidDatabaseText))
                .isInstanceOf(DataAccessException.class);

        assertThat(localAuthenticationRepository.findByEmail("rollback@example.com")).isEmpty();
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
    }

    @Test
    void configuresRestrictOwnerAndCascadeCredentialDeletion() throws Exception {
        assertThat(deleteRule("auth_identity", "app_user"))
                .isEqualTo((short) DatabaseMetaData.importedKeyRestrict);
        assertThat(deleteRule("local_password_credential", "auth_identity"))
                .isEqualTo((short) DatabaseMetaData.importedKeyCascade);
    }

    @Test
    void deletingIdentityCascadesItsCredential() {
        UUID userId = userRepository.create();
        UUID authenticationIdentityId = localAuthenticationRepository.createLocalEmailIdentity(
                userId,
                "owner@example.com",
                ENCODED_PASSWORD_HASH);

        jdbcTemplate.update("DELETE FROM auth_identity WHERE id = ?", authenticationIdentityId);

        assertThat(localAuthenticationRepository.findByEmail("owner@example.com")).isEmpty();
    }

    private short deleteRule(String foreignTable, String primaryTable) throws Exception {
        try (var connection = dataSource.getConnection();
                ResultSet foreignKeys = connection.getMetaData()
                        .getImportedKeys(null, null, foreignTable)) {
            while (foreignKeys.next()) {
                if (primaryTable.equals(foreignKeys.getString("PKTABLE_NAME"))) {
                    return foreignKeys.getShort("DELETE_RULE");
                }
            }
        }
        throw new AssertionError("Foreign key not found");
    }
}
