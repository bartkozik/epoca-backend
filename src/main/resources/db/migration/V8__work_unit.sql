CREATE TABLE work_unit
(
    id              UUID        NOT NULL PRIMARY KEY,
    organization_id UUID        NOT NULL REFERENCES organization (id),
    user_id         UUID        NOT NULL REFERENCES "user" (id),
    project_id      UUID        NOT NULL REFERENCES project (id),
    date            DATE        NOT NULL,
    duration        INTERVAL    NOT NULL,
    description     TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    modified_at     TIMESTAMPTZ NOT NULL,
    archived_at     TIMESTAMPTZ NULL
);
CREATE INDEX ON work_unit (user_id);