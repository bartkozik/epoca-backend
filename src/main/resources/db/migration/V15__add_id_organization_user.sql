CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

ALTER TABLE organization_user
    ADD COLUMN id UUID DEFAULT uuid_generate_v4();
ALTER TABLE organization_user
    DROP CONSTRAINT organization_user_pkey;
ALTER TABLE organization_user
    ADD PRIMARY KEY (id);
ALTER TABLE organization_user
    ADD COLUMN archived_at TIMESTAMPTZ NULL;

ALTER TABLE organization_user_email
    ADD COLUMN id UUID DEFAULT uuid_generate_v4();
ALTER TABLE organization_user_email
    DROP CONSTRAINT organization_user_email_pkey;
ALTER TABLE organization_user_email
    ADD PRIMARY KEY (id);
ALTER TABLE organization_user_email
    ADD COLUMN archived_at TIMESTAMPTZ NULL;