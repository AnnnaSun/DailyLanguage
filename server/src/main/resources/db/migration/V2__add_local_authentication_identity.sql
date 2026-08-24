CREATE TABLE auth_identity (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    user_id UUID NOT NULL,
    provider VARCHAR(32) NOT NULL,
    provider_subject VARCHAR(320) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_auth_identity_user
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT uq_auth_identity_provider_subject
        UNIQUE (provider, provider_subject),
    CONSTRAINT ck_auth_identity_provider
        CHECK (provider = 'LOCAL_EMAIL'),
    CONSTRAINT ck_auth_identity_provider_subject
        CHECK (
            provider_subject = LOWER(provider_subject)
            AND provider_subject = BTRIM(provider_subject)
            AND provider_subject <> ''
        )
);

CREATE TABLE local_password_credential (
    auth_identity_id UUID PRIMARY KEY,
    password_verifier VARCHAR(512) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_local_password_credential_identity
        FOREIGN KEY (auth_identity_id) REFERENCES auth_identity (id) ON DELETE CASCADE,
    CONSTRAINT ck_local_password_credential_verifier
        CHECK (password_verifier LIKE '{argon2id-v1}$%')
);
