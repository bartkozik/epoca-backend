-- ASCII case folds only; all non-ASCII code points are preserved.
-- Email canonicalization matches JVM String.trim: U+0001..U+0020 (PostgreSQL text excludes U+0000).
ALTER TABLE "${flyway:defaultSchema}".invitation
    ADD COLUMN protocol_version INTEGER NOT NULL DEFAULT 1 CHECK (protocol_version IN (1,2)),
    ADD COLUMN first_name TEXT,
    ADD COLUMN last_name TEXT,
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    ADD COLUMN generation BIGINT NOT NULL DEFAULT 1 CHECK (generation > 0),
    ADD COLUMN token_hash BYTEA CHECK (octet_length(token_hash) = 32),
    ADD COLUMN accepted_user_id UUID REFERENCES "${flyway:defaultSchema}"."user"(id),
    ADD COLUMN locale TEXT,
    ADD COLUMN profile_draft JSONB,
    ADD COLUMN terms_draft JSONB,
    ADD COLUMN profile_prepared_by UUID REFERENCES "${flyway:defaultSchema}"."user"(id),
    ADD COLUMN terms_prepared_by UUID REFERENCES "${flyway:defaultSchema}"."user"(id),
    ADD COLUMN canonical_email TEXT GENERATED ALWAYS AS
        (CASE WHEN identity_type = 'MailAddress' THEN translate(btrim(identity_value, E'\001\002\003\004\005\006\007\010\011\012\013\014\015\016\017\020\021\022\023\024\025\026\027\030\031\032\033\034\035\036\037\040'), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz') ELSE NULL END) STORED,
    ADD CONSTRAINT invitation_v2_shape CHECK (protocol_version = 1 OR (
        identity_type = 'MailAddress' AND btrim(identity_value) <> '' AND
        first_name IS NOT NULL AND first_name = btrim(first_name) AND first_name <> '' AND
        last_name IS NOT NULL AND last_name = btrim(last_name) AND last_name <> '' AND
        cardinality(roles) = 1 AND array_position(roles, NULL) IS NULL AND roles <@ ARRAY['Owner','Manager','Employee','External']::TEXT[] AND
        expire_at IS NOT NULL AND token_hash IS NOT NULL AND locale IS NOT NULL AND btrim(locale) <> '')),
    ADD CONSTRAINT invitation_profile_draft_author CHECK ((profile_draft IS NULL) = (profile_prepared_by IS NULL) AND
        (profile_draft IS NULL OR jsonb_typeof(profile_draft) = 'object')),
    ADD CONSTRAINT invitation_terms_draft_author CHECK ((terms_draft IS NULL) = (terms_prepared_by IS NULL) AND
        (terms_draft IS NULL OR jsonb_typeof(terms_draft) = 'object')),
    ADD CONSTRAINT invitation_accepted_user_shape CHECK (accepted_user_id IS NULL OR accept_at IS NOT NULL);

-- Report only the number of conflicting groups; operators resolve them explicitly.
DO $$
DECLARE duplicate_groups BIGINT;
BEGIN
    SELECT count(*) INTO duplicate_groups FROM (
        SELECT organization_id, canonical_email FROM "${flyway:defaultSchema}".invitation
        WHERE canonical_email IS NOT NULL AND accept_at IS NULL AND reject_at IS NULL AND archived_at IS NULL
        GROUP BY organization_id, canonical_email HAVING count(*) > 1
    ) duplicates;
    IF duplicate_groups > 0 THEN
        RAISE EXCEPTION '% duplicate pending invitation email groups; resolve before migrating', duplicate_groups;
    END IF;
END $$;
CREATE UNIQUE INDEX invitation_pending_email_key ON "${flyway:defaultSchema}".invitation(organization_id, canonical_email)
    WHERE canonical_email IS NOT NULL AND accept_at IS NULL AND reject_at IS NULL AND archived_at IS NULL;
CREATE INDEX invitation_organization_page_idx ON "${flyway:defaultSchema}".invitation(organization_id, created_at DESC, id DESC);

CREATE INDEX invitation_token_hash_idx ON "${flyway:defaultSchema}".invitation(token_hash) WHERE token_hash IS NOT NULL;
CREATE INDEX invitation_email_history_idx ON "${flyway:defaultSchema}".invitation(organization_id, canonical_email, created_at DESC, id DESC);

CREATE TABLE "${flyway:defaultSchema}".invitation_delivery_attempt (
    id UUID PRIMARY KEY,
    invitation_id UUID NOT NULL REFERENCES "${flyway:defaultSchema}".invitation(id),
    generation BIGINT NOT NULL CHECK (generation > 0),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    result TEXT CHECK (result IN ('Succeeded','Failed','Unknown')),
    safe_error_code TEXT CHECK (safe_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT invitation_delivery_result_shape CHECK ((finished_at IS NULL) = (result IS NULL) AND
        (finished_at IS NULL OR finished_at >= started_at) AND (safe_error_code IS NULL OR (result IS NOT NULL AND result = 'Failed')))
);
CREATE INDEX invitation_delivery_page_idx ON "${flyway:defaultSchema}".invitation_delivery_attempt(invitation_id, started_at DESC, id DESC);
CREATE TABLE "${flyway:defaultSchema}".invitation_audit (
    id UUID PRIMARY KEY,
    invitation_id UUID NOT NULL REFERENCES "${flyway:defaultSchema}".invitation(id),
    actor_id UUID REFERENCES "${flyway:defaultSchema}"."user"(id),
    action TEXT NOT NULL CHECK (btrim(action) <> ''),
    occurred_at TIMESTAMPTZ NOT NULL,
    before_data JSONB CHECK (jsonb_typeof(before_data) = 'object'),
    after_data JSONB CHECK (jsonb_typeof(after_data) = 'object')
);
COMMENT ON COLUMN "${flyway:defaultSchema}".invitation_audit.actor_id IS 'NULL only for a pre-account recipient action';
COMMENT ON TABLE "${flyway:defaultSchema}".invitation_audit IS 'Sanitized audit projections only, never full persistence records or secrets';
CREATE INDEX invitation_audit_page_idx ON "${flyway:defaultSchema}".invitation_audit(invitation_id, occurred_at DESC, id DESC);
CREATE TABLE "${flyway:defaultSchema}".invitation_command (
    actor_id UUID NOT NULL REFERENCES "${flyway:defaultSchema}"."user"(id),
    organization_id UUID NOT NULL REFERENCES "${flyway:defaultSchema}".organization(id),
    operation TEXT NOT NULL CHECK (btrim(operation) <> ''),
    request_id UUID NOT NULL,
    payload_hash BYTEA NOT NULL CHECK (octet_length(payload_hash) = 32),
    invitation_id UUID NOT NULL REFERENCES "${flyway:defaultSchema}".invitation(id),
    result_json JSONB NOT NULL CHECK (jsonb_typeof(result_json) = 'object'),
    PRIMARY KEY(actor_id, organization_id, operation, request_id)
);
COMMENT ON TABLE "${flyway:defaultSchema}".invitation_command IS 'Sanitized replay result only; raw invitation tokens and browser cookies must never be stored';
CREATE TABLE "${flyway:defaultSchema}".invitation_access (
    id UUID PRIMARY KEY,
    cookie_hash BYTEA NOT NULL UNIQUE CHECK (octet_length(cookie_hash) = 32),
    csrf_hash BYTEA NOT NULL CHECK (octet_length(csrf_hash) = 32),
    invitation_id UUID NOT NULL REFERENCES "${flyway:defaultSchema}".invitation(id),
    generation BIGINT NOT NULL CHECK (generation > 0),
    proof JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(proof) = 'object'),
    verified_user_id UUID REFERENCES "${flyway:defaultSchema}"."user"(id),
    saml_request_id TEXT REFERENCES "${flyway:defaultSchema}".saml_request(id),
    expires_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);
COMMENT ON COLUMN "${flyway:defaultSchema}".invitation_access.proof IS 'Server-verified email and SAML evidence may coexist; never copy client claims';
CREATE INDEX invitation_access_invitation_idx ON "${flyway:defaultSchema}".invitation_access(invitation_id);
CREATE TABLE "${flyway:defaultSchema}".invitation_rate_bucket (
    dimension TEXT NOT NULL CHECK (btrim(dimension) <> ''),
    key_hash BYTEA NOT NULL CHECK (octet_length(key_hash) = 32),
    window_start TIMESTAMPTZ NOT NULL,
    count BIGINT NOT NULL CHECK (count > 0),
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > window_start),
    PRIMARY KEY(dimension, key_hash, window_start)
);
CREATE INDEX invitation_rate_expiry_idx ON "${flyway:defaultSchema}".invitation_rate_bucket(expires_at);
