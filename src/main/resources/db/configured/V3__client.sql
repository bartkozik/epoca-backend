CREATE TABLE client
(
    id              UUID        NOT NULL PRIMARY KEY,
    name            TEXT        NOT NULL,
    color           TEXT        NOT NULL,
    organization_id UUID        NOT NULL REFERENCES organization (id),
    created_at      TIMESTAMPTZ NOT NULL,
    created_by      UUID        NOT NULL,
    modified_at     TIMESTAMPTZ NOT NULL,
    modified_by     UUID        NOT NULL,
    archived_at     TIMESTAMPTZ NULL,
    archived_by     UUID        NULL
);