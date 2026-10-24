package com.dailylanguage.identity.account;

import java.util.UUID;

import com.dailylanguage.identity.credential.LocalPasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实密码组件与既有事务 bean 的组合；没有测试外层事务，也不新增注册入口。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "IDENTITY_DATABASE_TESTS", matches = "true")
class IdentityPasswordHashingIntegrationTests {

    @Autowired
    private LocalPasswordHasher passwords;

    @Autowired
    private LocalAccountPersistence accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void requireIsolatedDatabase() {
        IdentityDatabaseTestData.requireVerificationDatabase(jdbc);
    }

    @Test
    void storesAndVerifiesRealArgon2CredentialThroughExistingAccountTransaction() {
        String submittedPassword = "r1b synthetic password";
        String email = IdentityDatabaseTestData.uniqueEmail();
        // 昂贵的散列先完成，再调用 Spring 事务 bean；数据库里只保存 verifier。
        String verifier = passwords.hash(submittedPassword);
        UUID userId = accounts.createAccountWithLanguageAccess(email, verifier);
        String storedVerifier = jdbc.queryForObject("""
                SELECT credential.password_verifier FROM local_password_credential credential
                JOIN auth_identity identity ON identity.id = credential.auth_identity_id
                WHERE identity.user_id = ? AND identity.provider = 'LOCAL_EMAIL' AND identity.provider_subject = ?
                """, String.class, userId, email);
        assertThat(storedVerifier).isEqualTo(verifier);
        assertThat(passwords.matches(submittedPassword, storedVerifier)).isTrue();
        assertThat(passwords.matches("a different password", storedVerifier)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id = ?", Integer.class, userId)).isOne();
        assertThat(jdbc.queryForList("SELECT app_code FROM app_access_grant WHERE user_id = ?",
                String.class, userId)).containsExactly("LANGUAGE");
    }
}
