-- Match EmailCanonicalization.column exactly; retain ambiguous historical identities for fail-closed resolution.
CREATE INDEX user_identity_canonical_email_idx ON "${flyway:defaultSchema}".user_identity
    (translate(btrim(value, E'\001\002\003\004\005\006\007\010\011\012\013\014\015\016\017\020\021\022\023\024\025\026\027\030\031\032\033\034\035\036\037\040'), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'))
    WHERE type IN ('MailAddress', 'Saml');

CREATE INDEX invitation_access_expiry_idx ON "${flyway:defaultSchema}".invitation_access(expires_at, id);
CREATE INDEX invitation_access_saml_request_idx ON "${flyway:defaultSchema}".invitation_access(saml_request_id)
    WHERE saml_request_id IS NOT NULL;
CREATE INDEX saml_request_invitation_access_idx ON "${flyway:defaultSchema}".saml_request(invitation_access_id)
    WHERE invitation_access_id IS NOT NULL;
