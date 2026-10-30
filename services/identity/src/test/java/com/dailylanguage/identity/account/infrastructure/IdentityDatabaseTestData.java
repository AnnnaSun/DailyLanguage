package com.dailylanguage.identity.account.infrastructure;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

final class IdentityDatabaseTestData {

    // 仅为格式合法的合成存储样本，不是某个真实账户的密码，也不证明 Argon2 验证能力。
    static final String PASSWORD_VERIFIER = "{argon2id-v1}$argon2id$v=19$m=19456,t=2,p=1$"
            + "MDEyMzQ1Njc4OWFiY2RlZg$MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY";

    private IdentityDatabaseTestData() {
    }

    static String uniqueEmail() {
        return "r1-" + UUID.randomUUID() + "@example.com";
    }

    static void requireVerificationDatabase(JdbcTemplate jdbc) {
        // 失败注入会创建测试 trigger；先检查实例标识，防止把这些操作指向真实业务环境。
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("identity");
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("identity_app");
        assertThat(jdbc.queryForObject("""
                SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname = current_database()
                """, String.class)).isEqualTo("DAILYLANGUAGE_R1_ISOLATED_VERIFICATION_ONLY");
    }
}
