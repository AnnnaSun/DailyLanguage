-- 新身份库仅持有平台账户与应用准入；不复制 Language 表、singleton slot 或历史数据。
CREATE TABLE app_user (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE auth_identity (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    user_id UUID NOT NULL REFERENCES app_user (id) ON DELETE RESTRICT,
    provider VARCHAR(32) NOT NULL CHECK (provider = 'LOCAL_EMAIL'),
    provider_subject VARCHAR(320) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_auth_identity_provider_subject UNIQUE (provider, provider_subject),
    CONSTRAINT ck_auth_identity_provider_subject CHECK (
        provider_subject = LOWER(provider_subject)
        AND provider_subject = BTRIM(provider_subject)
        AND provider_subject <> ''
    )
);

CREATE TABLE local_password_credential (
    auth_identity_id UUID PRIMARY KEY REFERENCES auth_identity (id) ON DELETE CASCADE,
    password_verifier VARCHAR(512) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 这是存储格式的最低约束，不替代未来注册流程的密码 policy 或 Argon2 验证。
    CONSTRAINT ck_local_password_credential_verifier CHECK (password_verifier LIKE '{argon2id-v1}$%')
);

CREATE TABLE app_access_grant (
    user_id UUID NOT NULL REFERENCES app_user (id) ON DELETE RESTRICT,
    app_code VARCHAR(32) NOT NULL CHECK (app_code IN ('LANGUAGE', 'PERSONAL_NOTE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, app_code)
);

COMMENT ON TABLE app_access_grant IS
    '平台应用准入，不代表业务资源归属；注册只创建 LANGUAGE 记录，登录不得自动恢复撤销的准入。';
