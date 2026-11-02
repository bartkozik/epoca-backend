CREATE TABLE "user"
(
    id             UUID        NOT NULL PRIMARY KEY,
    first_name     TEXT        NOT NULL,
    last_name      TEXT        NOT NULL,
    email          TEXT        NOT NULL,
    avatar         TEXT        NULL,
    color          TEXT        NULL,
    is_super_admin BOOLEAN     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    modified_at    TIMESTAMPTZ NOT NULL,
    archived_at    TIMESTAMPTZ NULL
);
CREATE INDEX ON "user" (email);

CREATE TABLE user_password
(
    user_id  UUID PRIMARY KEY NOT NULL,
    password TEXT             NOT NULL
);
CREATE UNIQUE INDEX ON user_password (user_id);

CREATE TABLE user_login_token
(
    user_id     UUID        NOT NULL PRIMARY KEY REFERENCES "user" (id),
    login_token TEXT        NOT NULL,
    valid_to    TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX ON user_login_token (login_token);