-- One current document per month; receipts retain results without retaining old files.
CREATE TABLE "${flyway:defaultSchema}".invoice_attachment
(
    id UUID NOT NULL PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES "${flyway:defaultSchema}".organization (id),
    user_id UUID NOT NULL REFERENCES "${flyway:defaultSchema}"."user" (id),
    month DATE NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    source_nanoseconds NUMERIC(30, 0) NOT NULL,
    target_nanoseconds NUMERIC(30, 0) NOT NULL,
    delta_nanoseconds NUMERIC(30, 0) NOT NULL,
    source_token TEXT NOT NULL CHECK (source_token ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL CHECK (updated_at >= created_at),
    filename TEXT NOT NULL CHECK (filename ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,179}\.xlsx$'),
    CHECK (month >= DATE '0001-01-01' AND month <= DATE '9999-12-01' AND EXTRACT(DAY FROM month) = 1),
    CHECK (source_nanoseconds > 0 AND source_nanoseconds <= 9223372036854775807999999999),
    CHECK (target_nanoseconds >= 0 AND target_nanoseconds <= 9223372036854775807999999999),
    CHECK (delta_nanoseconds BETWEEN -9223372036854775807999999999 AND 9223372036854775807999999999),
    CHECK (delta_nanoseconds = target_nanoseconds - source_nanoseconds),
    format_version INTEGER NOT NULL CHECK (format_version > 0),
    snapshot JSONB NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    locale TEXT NOT NULL CHECK (locale IN ('pl', 'en')),
    xlsx BYTEA NOT NULL CHECK (octet_length(xlsx) BETWEEN 1 AND 20971520),
    CONSTRAINT invoice_attachment_month_key UNIQUE (organization_id, user_id, month),
    CONSTRAINT invoice_attachment_owner_key UNIQUE (id, organization_id, user_id, month)
);

CREATE TABLE "${flyway:defaultSchema}".invoice_attachment_receipt
(
    organization_id UUID NOT NULL,
    user_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    request_hash TEXT NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    attachment_id UUID NOT NULL,
    month DATE NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    source_nanoseconds NUMERIC(30, 0) NOT NULL,
    target_nanoseconds NUMERIC(30, 0) NOT NULL,
    delta_nanoseconds NUMERIC(30, 0) NOT NULL,
    source_token TEXT NOT NULL CHECK (source_token ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL CHECK (updated_at >= created_at),
    filename TEXT NOT NULL CHECK (filename ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,179}\.xlsx$'),
    CHECK (month >= DATE '0001-01-01' AND month <= DATE '9999-12-01' AND EXTRACT(DAY FROM month) = 1),
    CHECK (source_nanoseconds > 0 AND source_nanoseconds <= 9223372036854775807999999999),
    CHECK (target_nanoseconds >= 0 AND target_nanoseconds <= 9223372036854775807999999999),
    CHECK (delta_nanoseconds BETWEEN -9223372036854775807999999999 AND 9223372036854775807999999999),
    CHECK (delta_nanoseconds = target_nanoseconds - source_nanoseconds),
    CONSTRAINT invoice_attachment_receipt_key PRIMARY KEY (organization_id, user_id, operation_id),
    CONSTRAINT invoice_attachment_receipt_owner_fk FOREIGN KEY (attachment_id, organization_id, user_id, month)
        REFERENCES "${flyway:defaultSchema}".invoice_attachment (id, organization_id, user_id, month)
);
