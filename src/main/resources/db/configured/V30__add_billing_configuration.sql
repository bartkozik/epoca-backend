CREATE TABLE "${flyway:defaultSchema}".billing_stream
(
    id              UUID   NOT NULL PRIMARY KEY,
    organization_id UUID   NOT NULL,
    user_id         UUID,
    kind            TEXT   NOT NULL,
    revision        BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT billing_stream_organization_id_fkey FOREIGN KEY (organization_id)
        REFERENCES "${flyway:defaultSchema}".organization (id),
    CONSTRAINT billing_stream_user_id_fkey FOREIGN KEY (user_id)
        REFERENCES "${flyway:defaultSchema}"."user" (id),
    CONSTRAINT billing_stream_kind_check CHECK (kind IN ('OrganizationParty', 'MemberProfile', 'MemberTerms')),
    CONSTRAINT billing_stream_subject CHECK (
        (kind = 'OrganizationParty' AND user_id IS NULL)
        OR (kind IN ('MemberProfile', 'MemberTerms') AND user_id IS NOT NULL)
    ),
    CONSTRAINT billing_stream_revision_check CHECK (revision >= 0),
    CONSTRAINT billing_stream_id_kind_key UNIQUE (id, kind)
);

CREATE UNIQUE INDEX billing_stream_org_key
    ON "${flyway:defaultSchema}".billing_stream (organization_id, kind)
    WHERE user_id IS NULL;

CREATE UNIQUE INDEX billing_stream_member_key
    ON "${flyway:defaultSchema}".billing_stream (organization_id, user_id, kind)
    WHERE user_id IS NOT NULL;

CREATE TABLE "${flyway:defaultSchema}".billing_party_version
(
    id               UUID        NOT NULL PRIMARY KEY,
    stream_id        UUID        NOT NULL,
    kind             TEXT        NOT NULL,
    effective_from   DATE        NOT NULL,
    cooperation_type TEXT,
    legal_name       TEXT,
    tax_country      TEXT,
    tax_id           TEXT,
    address_line1    TEXT,
    address_line2    TEXT,
    postal_code      TEXT,
    city             TEXT,
    address_country  TEXT,
    created_at       TIMESTAMPTZ NOT NULL,
    modified_at      TIMESTAMPTZ NOT NULL,
    created_by       UUID        NOT NULL REFERENCES "${flyway:defaultSchema}"."user" (id),
    modified_by      UUID        NOT NULL REFERENCES "${flyway:defaultSchema}"."user" (id),
    CONSTRAINT billing_party_stream_fk FOREIGN KEY (stream_id, kind)
        REFERENCES "${flyway:defaultSchema}".billing_stream (id, kind),
    CONSTRAINT billing_party_effective_key UNIQUE (stream_id, effective_from),
    CONSTRAINT billing_party_kind CHECK (
        (kind = 'OrganizationParty' AND cooperation_type IS NULL)
        OR (kind = 'MemberProfile' AND cooperation_type IS NOT NULL)
    ),
    CONSTRAINT billing_party_cooperation_type_check CHECK (
        cooperation_type IS NULL OR cooperation_type IN ('Unspecified', 'Employment', 'B2B', 'Other')
    )
);

CREATE TABLE "${flyway:defaultSchema}".billing_terms_version
(
    id                 UUID        NOT NULL PRIMARY KEY,
    stream_id          UUID        NOT NULL,
    kind               TEXT        NOT NULL DEFAULT 'MemberTerms',
    effective_from     DATE        NOT NULL,
    contract_reference TEXT,
    contract_date      DATE,
    hourly_rate        NUMERIC(20, 6),
    currency           TEXT,
    created_at         TIMESTAMPTZ NOT NULL,
    modified_at        TIMESTAMPTZ NOT NULL,
    created_by         UUID        NOT NULL REFERENCES "${flyway:defaultSchema}"."user" (id),
    modified_by        UUID        NOT NULL REFERENCES "${flyway:defaultSchema}"."user" (id),
    CONSTRAINT billing_terms_stream_fk FOREIGN KEY (stream_id, kind)
        REFERENCES "${flyway:defaultSchema}".billing_stream (id, kind),
    CONSTRAINT billing_terms_effective_key UNIQUE (stream_id, effective_from),
    CONSTRAINT billing_terms_kind_check CHECK (kind = 'MemberTerms'),
    CONSTRAINT billing_terms_version_hourly_rate_check CHECK (hourly_rate >= 0),
    CONSTRAINT billing_terms_currency_check CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$')
);

CREATE TABLE "${flyway:defaultSchema}".billing_history
(
    id          UUID        NOT NULL PRIMARY KEY,
    stream_id   UUID        NOT NULL REFERENCES "${flyway:defaultSchema}".billing_stream (id),
    -- A version belongs to one of two version tables, so version_id is resolved by stream kind.
    version_id  UUID        NOT NULL,
    revision    BIGINT      NOT NULL,
    operation   TEXT        NOT NULL,
    actor_id    UUID        NOT NULL REFERENCES "${flyway:defaultSchema}"."user" (id),
    occurred_at TIMESTAMPTZ NOT NULL,
    before_data JSONB,
    after_data  JSONB       NOT NULL,
    CONSTRAINT billing_history_revision_key UNIQUE (stream_id, revision),
    CONSTRAINT billing_history_revision_check CHECK (revision > 0),
    CONSTRAINT billing_history_operation_check CHECK (operation IN ('Create', 'Correct')),
    CONSTRAINT billing_history_operation_shape_check CHECK (
        (operation = 'Create' AND before_data IS NULL)
        OR (operation = 'Correct' AND before_data IS NOT NULL)
    ),
    CONSTRAINT billing_history_before_object_check CHECK (before_data IS NULL OR jsonb_typeof(before_data) = 'object'),
    CONSTRAINT billing_history_after_object_check CHECK (jsonb_typeof(after_data) = 'object')
);

CREATE INDEX billing_history_page
    ON "${flyway:defaultSchema}".billing_history (stream_id, occurred_at DESC, id DESC);
