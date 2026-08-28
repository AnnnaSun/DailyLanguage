CREATE TABLE single_user_instance (
    singleton_key BOOLEAN PRIMARY KEY,
    user_id UUID UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_single_user_instance_key
        CHECK (singleton_key),
    CONSTRAINT fk_single_user_instance_user
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT
);

INSERT INTO single_user_instance (singleton_key) VALUES (TRUE);
