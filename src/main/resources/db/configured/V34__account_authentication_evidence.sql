-- Legacy sessions remain usable without gaining trusted account evidence.
ALTER TABLE "${flyway:defaultSchema}".refresh_token
    ADD COLUMN account_authentication JSONB CHECK (account_authentication IS NULL OR jsonb_typeof(account_authentication) = 'object');
ALTER TABLE "${flyway:defaultSchema}".saml_request
    ADD COLUMN account_authentication JSONB CHECK (account_authentication IS NULL OR jsonb_typeof(account_authentication) = 'object');
