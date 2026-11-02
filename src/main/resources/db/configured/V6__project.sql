CREATE TABLE project
(
    id              UUID        NOT NULL PRIMARY KEY,
    name            TEXT        NOT NULL,
    description     TEXT        NULL,
    organization_id UUID        NOT NULL REFERENCES organization (id),
    client_id       UUID        NOT NULL REFERENCES client (id),
    created_at      TIMESTAMPTZ NOT NULL,
    created_by      UUID        NOT NULL,
    modified_at     TIMESTAMPTZ NOT NULL,
    modified_by     UUID        NOT NULL,
    archived_at     TIMESTAMPTZ NULL,
    archived_by     UUID        NULL
);

CREATE TABLE project_user
(
    id          UUID        NOT NULL PRIMARY KEY,
    project_id  UUID        NOT NULL REFERENCES project (id),
    user_id     UUID        NOT NULL REFERENCES "user" (id),
    role        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    archived_at TIMESTAMPTZ NULL
);
CREATE INDEX ON project_user (project_id, user_id);