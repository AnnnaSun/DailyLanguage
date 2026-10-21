package com.dailylanguage.identity.account;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.dailylanguage.identity.account.IdentityDatabaseTestData.PASSWORD_VERIFIER;
import static com.dailylanguage.identity.account.IdentityDatabaseTestData.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Human Core 的行为验收，覆盖成功提交、并发冲突和整笔回滚。
 * 不添加测试事务：必须由 production bean 自己提交或回滚，不能由测试替它提供原子性。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "IDENTITY_DATABASE_TESTS", matches = "true")
class LocalAccountPersistenceIntegrationTests {

    @Autowired
    private LocalAccountPersistence accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void requireIsolatedDatabase() {
        IdentityDatabaseTestData.requireVerificationDatabase(jdbc);
    }

    @Test
    void commitsExactlyOneCompleteAccountWithLanguageAccess() {
        String email = uniqueEmail();
        int usersBefore = count("SELECT COUNT(*) FROM app_user");
        UUID userId = accounts.createAccountWithLanguageAccess(email, PASSWORD_VERIFIER);
        assertThat(count("SELECT COUNT(*) FROM app_user")).isEqualTo(usersBefore + 1);
        assertCompleteAccount(userId, email);
    }

    @Test
    void concurrentDuplicateEmailLeavesOneCompleteAccountAndNoOrphanUser() throws Exception {
        String email = uniqueEmail();
        int usersBefore = count("SELECT COUNT(*) FROM app_user");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Attempt> attempt = () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("并发测试未能同时开始");
            }
            try {
                return new Attempt(accounts.createAccountWithLanguageAccess(email, PASSWORD_VERIFIER), false);
            }
            catch (DuplicateKeyException expectedConflict) {
                return new Attempt(null, true);
            }
        };

        List<Attempt> outcomes;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            }
            finally {
                start.countDown();
            }
            outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertThat(outcomes.stream().filter(Attempt::duplicate)).hasSize(1);
        List<UUID> successfulIds = outcomes.stream().map(Attempt::userId).filter(id -> id != null).toList();
        assertThat(successfulIds).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM app_user")).isEqualTo(usersBefore + 1);
        assertCompleteAccount(successfulIds.getFirst(), email);
    }

    @ParameterizedTest
    @ValueSource(strings = {"local_password_credential", "app_access_grant"})
    void failureDuringCredentialOrGrantInsertRollsBackTheWholeAccount(String rejectedTable) {
        String email = uniqueEmail();
        int usersBefore = count("SELECT COUNT(*) FROM app_user");
        // 仅在带验证标识的隔离库注入真实 PostgreSQL 错误；不为 production 增加故障开关。
        jdbc.execute("""
                CREATE FUNCTION r1_reject_account_write() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'R1_TEST_WRITE_REJECTED' USING ERRCODE = '23514'; END $$
                """);
        try {
            jdbc.execute("CREATE TRIGGER r1_reject_account_write BEFORE INSERT ON " + rejectedTable
                    + " FOR EACH ROW EXECUTE FUNCTION r1_reject_account_write()");
            assertThatThrownBy(() -> accounts.createAccountWithLanguageAccess(email, PASSWORD_VERIFIER))
                    .isInstanceOf(DataAccessException.class)
                    .hasStackTraceContaining("R1_TEST_WRITE_REJECTED");
            assertThat(count("SELECT COUNT(*) FROM app_user")).isEqualTo(usersBefore);
            assertThat(count("SELECT COUNT(*) FROM auth_identity WHERE provider_subject = ?", email)).isZero();
        }
        finally {
            jdbc.execute("DROP TRIGGER IF EXISTS r1_reject_account_write ON " + rejectedTable);
            jdbc.execute("DROP FUNCTION r1_reject_account_write()");
        }
    }

    private void assertCompleteAccount(UUID userId, String email) {
        assertThat(userId.version()).isEqualTo(7);
        assertThat(count("SELECT COUNT(*) FROM app_user WHERE id = ?", userId)).isOne();
        assertThat(count("""
                SELECT COUNT(*) FROM auth_identity identity
                JOIN local_password_credential credential ON credential.auth_identity_id = identity.id
                WHERE identity.user_id = ? AND identity.provider = 'LOCAL_EMAIL' AND identity.provider_subject = ?
                    AND credential.password_verifier = ?
                """, userId, email, PASSWORD_VERIFIER)).isOne();
        assertThat(jdbc.queryForList("SELECT app_code FROM app_access_grant WHERE user_id = ?",
                String.class, userId)).containsExactly("LANGUAGE");
    }

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private record Attempt(UUID userId, boolean duplicate) {
    }
}
