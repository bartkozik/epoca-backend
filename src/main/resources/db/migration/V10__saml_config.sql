CREATE TABLE saml_config (
    organization_id     UUID        PRIMARY KEY REFERENCES organization(id),
    idp_cert            TEXT        NOT NULL,
    idp_entity_id       TEXT        NOT NULL,
    idp_sso_url         TEXT        NOT NULL,
    lang_code           TEXT        NOT NULL,
    validity_duration   INTERVAL    NOT NULL,
    allow_account_init  BOOLEAN     NOT NULL,
    is_enabled          BOOLEAN     NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL
);

CREATE TABLE saml_request(
    id                  TEXT        PRIMARY KEY,
    one_time_token      UUID        NULL,
    user_id             UUID        NULL REFERENCES "user"(id),
    used                BOOLEAN     NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL
);

CREATE INDEX ON saml_request(one_time_token);



