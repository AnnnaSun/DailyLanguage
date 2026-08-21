CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE app_user (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE language_profile (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    user_id UUID NOT NULL,
    language_code VARCHAR(35) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_language_profile_user
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT uq_language_profile_user_language
        UNIQUE (user_id, language_code),
    CONSTRAINT ck_language_profile_language_code
        CHECK (language_code = LOWER(language_code) AND BTRIM(language_code) <> '')
);
