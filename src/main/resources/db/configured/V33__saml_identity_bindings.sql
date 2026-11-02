-- Canonical email uses the same JVM trim and ASCII case-fold contract as V31.
ALTER TABLE "${flyway:defaultSchema}".saml_config ADD COLUMN trust_epoch BIGINT NOT NULL DEFAULT 1 CHECK (trust_epoch > 0);
CREATE TABLE "${flyway:defaultSchema}".saml_identity_binding (
    organization_id UUID NOT NULL REFERENCES "${flyway:defaultSchema}".organization(id),
    trust_epoch BIGINT NOT NULL CHECK (trust_epoch > 0),
    issuer TEXT NOT NULL CHECK (btrim(issuer) <> ''),
    subject TEXT NOT NULL CHECK (btrim(subject) <> ''),
    user_id UUID NOT NULL REFERENCES "${flyway:defaultSchema}"."user"(id),
    canonical_email TEXT NOT NULL CHECK (canonical_email = translate(btrim(canonical_email, E'\001\002\003\004\005\006\007\010\011\012\013\014\015\016\017\020\021\022\023\024\025\026\027\030\031\032\033\034\035\036\037\040'), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz') AND canonical_email <> ''),
    verified_at TIMESTAMPTZ NOT NULL,
    proof JSONB NOT NULL CHECK (jsonb_typeof(proof) = 'object'),
    PRIMARY KEY(organization_id, trust_epoch, issuer, subject)
);
-- Intentionally no email or membership-based backfill of trusted identities.
ALTER TABLE "${flyway:defaultSchema}".saml_request
    ADD COLUMN organization_id UUID REFERENCES "${flyway:defaultSchema}".organization(id),
    ADD COLUMN trust_epoch BIGINT CHECK (trust_epoch > 0),
    ADD COLUMN purpose TEXT CHECK (purpose IN ('Login','Invitation')),
    ADD COLUMN browser_nonce_hash BYTEA CHECK (octet_length(browser_nonce_hash) = 32),
    ADD COLUMN invitation_access_id UUID REFERENCES "${flyway:defaultSchema}".invitation_access(id),
    ADD COLUMN verified_issuer TEXT,
    ADD COLUMN verified_subject TEXT,
    ADD COLUMN verified_canonical_email TEXT,
    ADD COLUMN verified_at TIMESTAMPTZ,
    ADD CONSTRAINT saml_request_metadata_shape CHECK (
        (organization_id IS NULL AND trust_epoch IS NULL AND purpose IS NULL AND browser_nonce_hash IS NULL AND invitation_access_id IS NULL) OR
        (organization_id IS NOT NULL AND trust_epoch IS NOT NULL AND purpose IS NOT NULL AND browser_nonce_hash IS NOT NULL AND
            ((purpose = 'Login' AND invitation_access_id IS NULL) OR (purpose = 'Invitation' AND invitation_access_id IS NOT NULL)))),
    ADD CONSTRAINT saml_request_verified_shape CHECK (
        (verified_issuer IS NULL AND verified_subject IS NULL AND verified_canonical_email IS NULL AND verified_at IS NULL) OR
        (verified_issuer IS NOT NULL AND btrim(verified_issuer) <> '' AND verified_subject IS NOT NULL AND btrim(verified_subject) <> '' AND
         verified_canonical_email IS NOT NULL AND verified_canonical_email = translate(btrim(verified_canonical_email, E'\001\002\003\004\005\006\007\010\011\012\013\014\015\016\017\020\021\022\023\024\025\026\027\030\031\032\033\034\035\036\037\040'), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz') AND verified_canonical_email <> '' AND verified_at IS NOT NULL));
