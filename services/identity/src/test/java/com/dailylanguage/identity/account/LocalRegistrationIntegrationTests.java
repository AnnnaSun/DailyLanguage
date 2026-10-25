package com.dailylanguage.identity.account;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.dailylanguage.identity.credential.LocalPasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** 验证真实注册组件接通、成功落库与散列在事务外；不添加测试外层事务。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "IDENTITY_DATABASE_TESTS", matches = "true")
class LocalRegistrationIntegrationTests {

    private static final String PASSWORD = "  R1d synthetic password 42  ";

    @Autowired
    private LocalRegistrationService registrations;

    @MockitoSpyBean
    private LocalPasswordHasher passwords;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void requireIsolatedDatabase() {
        IdentityDatabaseTestData.requireVerificationDatabase(jdbc);
    }

    @Test
    void registersNormalizedEmailAndOriginalPasswordWithHashingOutsideTransaction() {
        String email = IdentityDatabaseTestData.uniqueEmail();
        List<Integer> before = tableCounts();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(passwords).hash(any());

        UUID userId = registrations.register(" " + email.toUpperCase(Locale.ROOT) + " ", PASSWORD);

        assertCompleteAccount(userId, email);
        assertThat(tableCounts()).containsExactlyElementsOf(before.stream().map(count -> count + 1).toList());
    }

    private void assertCompleteAccount(UUID userId, String email) {
        assertThat(userId.version()).isEqualTo(7);
        assertThat(count("SELECT COUNT(*) FROM app_user WHERE id = ?", userId)).isOne();
        assertThat(count("SELECT COUNT(*) FROM auth_identity WHERE user_id = ?", userId)).isOne();
        String verifier = jdbc.queryForObject("""
                SELECT credential.password_verifier FROM local_password_credential credential
                JOIN auth_identity identity ON identity.id = credential.auth_identity_id
                WHERE identity.user_id = ? AND identity.provider = 'LOCAL_EMAIL' AND identity.provider_subject = ?
                """, String.class, userId, email);
        assertThat(passwords.matches(PASSWORD, verifier)).isTrue();
        assertThat(passwords.matches(PASSWORD.strip(), verifier)).isFalse();
        assertThat(jdbc.queryForList("SELECT app_code FROM app_access_grant WHERE user_id = ?",
                String.class, userId)).containsExactly("LANGUAGE");
    }

    private List<Integer> tableCounts() {
        return List.of(count("SELECT COUNT(*) FROM app_user"), count("SELECT COUNT(*) FROM auth_identity"),
                count("SELECT COUNT(*) FROM local_password_credential"), count("SELECT COUNT(*) FROM app_access_grant"));
    }

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

}
