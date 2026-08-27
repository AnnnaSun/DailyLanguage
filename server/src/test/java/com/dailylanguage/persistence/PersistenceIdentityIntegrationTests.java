package com.dailylanguage.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.UUID;

import javax.sql.DataSource;

import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.languageprofile.infrastructure.LanguageProfileRepository;
import com.dailylanguage.user.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class PersistenceIdentityIntegrationTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LanguageProfileRepository languageProfileRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    void createsUuidV7UserAndNormalizedLanguageProfileIdentity() {
        UUID userId = userRepository.create();

        LanguageProfileIdentity profile = languageProfileRepository.create(userId, " zh-Hans ");

        assertThat(userId.version()).isEqualTo(7);
        assertThat(profile.id().version()).isEqualTo(7);
        assertThat(profile.userId()).isEqualTo(userId);
        assertThat(profile.languageCode()).isEqualTo("zh-hans");
    }

    @Test
    void scopesProfileLookupToItsOwner() {
        UUID ownerId = userRepository.create();
        UUID otherUserId = userRepository.create();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "ja");

        assertThat(languageProfileRepository.findByIdAndUserId(profile.id(), ownerId))
                .contains(profile);
        assertThat(languageProfileRepository.findByIdAndUserId(profile.id(), otherUserId))
                .isEmpty();
    }

    @Test
    void rejectsDuplicateLanguageWithinOneUser() {
        UUID userId = userRepository.create();
        languageProfileRepository.create(userId, "EN");

        assertThatThrownBy(() -> languageProfileRepository.create(userId, "en"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsProfileWithoutExistingUser() {
        assertThatThrownBy(() -> languageProfileRepository.create(UUID.randomUUID(), "en"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsMalformedLanguageCode() {
        UUID userId = userRepository.create();

        assertThatThrownBy(() -> languageProfileRepository.create(userId, "not a tag"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("languageCode must be a well-formed BCP 47 language tag");
    }

    @Test
    void rejectsSqlInjectionPayloadBeforePersistenceAndKeepsRepositoryUsable() {
        UUID userId = userRepository.create();

        assertThatThrownBy(() -> languageProfileRepository.create(
                        userId,
                        "en'); DROP TABLE app_user; --"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("languageCode must be a well-formed BCP 47 language tag");

        LanguageProfileIdentity validProfile = languageProfileRepository.create(userId, "en");
        assertThat(languageProfileRepository.findByIdAndUserId(validProfile.id(), userId))
                .contains(validProfile);
    }

    @Test
    void configuresRestrictDeleteForLanguageProfileOwner() throws Exception {
        try (var connection = dataSource.getConnection();
                ResultSet foreignKeys = connection.getMetaData()
                        .getImportedKeys(null, null, "language_profile")) {
            boolean restrictRuleFound = false;
            while (foreignKeys.next()) {
                if ("app_user".equals(foreignKeys.getString("PKTABLE_NAME"))
                        && foreignKeys.getShort("DELETE_RULE") == DatabaseMetaData.importedKeyRestrict) {
                    restrictRuleFound = true;
                }
            }

            assertThat(restrictRuleFound).isTrue();
        }
    }

    @Test
    void installsVectorTypeThroughFlyway() throws Exception {
        try (var connection = dataSource.getConnection();
                ResultSet types = connection.getMetaData().getTypeInfo()) {
            boolean vectorTypeFound = false;
            while (types.next()) {
                if ("vector".equals(types.getString("TYPE_NAME"))) {
                    vectorTypeFound = true;
                }
            }

            assertThat(vectorTypeFound).isTrue();
        }
    }
}
