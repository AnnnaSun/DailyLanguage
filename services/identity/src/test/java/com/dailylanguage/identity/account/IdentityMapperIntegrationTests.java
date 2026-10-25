package com.dailylanguage.identity.account;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static com.dailylanguage.identity.account.IdentityDatabaseTestData.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 测试事务只隔离 Mapper 样本；四表提交、并发与回滚由账户事务测试覆盖。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "IDENTITY_DATABASE_TESTS", matches = "true")
@Transactional
class IdentityMapperIntegrationTests {

    @Autowired
    private LocalAccountMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void requireIsolatedDatabase() {
        IdentityDatabaseTestData.requireVerificationDatabase(jdbc);
    }

    @Test
    void databaseRejectsPlaintextPasswordStorage() {
        UUID identityId = mapper.insertLocalEmailIdentity(mapper.insertUser(), uniqueEmail());
        assertThat(identityId.version()).isEqualTo(7);
        assertThatThrownBy(() -> mapper.insertPasswordCredential(identityId, "plaintext-is-not-a-verifier"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void schemaContainsNoBusinessOrSingletonTablesAndNoVectorExtension() {
        assertThat(jdbc.queryForList("""
                SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename
                """, String.class)).containsExactly("app_access_grant", "app_user", "auth_identity",
                        "flyway_schema_history", "local_password_credential");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'", Integer.class))
                .isZero();
    }

    @Test
    void dedicatedRoleHasNoClusterAdministrationOrOtherDatabaseAccess() {
        assertThat(jdbc.queryForObject("""
                SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname = current_user
                """, Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT has_database_privilege(current_user, 'unrelated_probe', 'CONNECT')",
                Boolean.class)).isFalse();
        String otherDatabaseUrl = System.getenv("IDENTITY_DATABASE_URL").replace("/identity", "/unrelated_probe");
        assertThatThrownBy(() -> {
            try (var ignored = DriverManager.getConnection(otherDatabaseUrl,
                    System.getenv("IDENTITY_DATABASE_USER"), System.getenv("IDENTITY_DATABASE_PASSWORD"))) {
                throw new AssertionError("Identity 账号不应能连接其他服务的数据库");
            }
        }).isInstanceOfSatisfying(SQLException.class, exception -> assertThat(exception.getSQLState()).isEqualTo("42501"));
    }
}
