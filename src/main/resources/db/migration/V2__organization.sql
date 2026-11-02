CREATE TABLE organization
(
    id          UUID PRIMARY KEY,
    name        TEXT        NOT NULL,
    slug        TEXT        NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL,
    created_by  UUID        NOT NULL,
    modified_at TIMESTAMPTZ NOT NULL,
    modified_by UUID        NOT NULL,
    archived_at TIMESTAMPTZ NULL
);

CREATE TABLE organization_user
(
    organization_id UUID   NOT NULL REFERENCES organization (id),
    user_id         UUID   NOT NULL REFERENCES "user" (id),
    roles           TEXT[] NOT NULL,
    PRIMARY KEY (organization_id, user_id)
);

CREATE TABLE organization_user_email
(
    organization_id UUID   NOT NULL REFERENCES organization (id),
    user_email      TEXT   NOT NULL,
    roles           TEXT[] NOT NULL,
    PRIMARY KEY (organization_id, user_email)
);