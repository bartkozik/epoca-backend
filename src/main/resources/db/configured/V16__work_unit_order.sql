CREATE TABLE work_unit_order (
    organization_id     UUID        NOT NULL REFERENCES organization(id),
    user_id             UUID        NOT NULL REFERENCES "user"(id),
    date                DATE        NOT NULL,
    work_unit_ids       UUID[]      NOT NULL,
    PRIMARY KEY (organization_id, user_id, date)
);


