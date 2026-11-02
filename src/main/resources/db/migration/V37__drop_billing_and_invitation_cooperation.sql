-- Billing configuration, invoice attachments and invitation cooperation drafts are removed from the product.
-- V30, V31 and V32 are already applied in deployed environments, so their objects are dropped here instead.
-- IF EXISTS keeps the migration safe for databases whose objects were already removed manually.

-- V31: invoice attachments. The receipt table references invoice_attachment, so it is dropped first.
DROP TABLE IF EXISTS "${flyway:defaultSchema}".invoice_attachment_receipt;
DROP TABLE IF EXISTS "${flyway:defaultSchema}".invoice_attachment;

-- V30: billing configuration. Version and history tables reference billing_stream; indexes are dropped with their tables.
DROP TABLE IF EXISTS "${flyway:defaultSchema}".billing_history;
DROP TABLE IF EXISTS "${flyway:defaultSchema}".billing_terms_version;
DROP TABLE IF EXISTS "${flyway:defaultSchema}".billing_party_version;
DROP TABLE IF EXISTS "${flyway:defaultSchema}".billing_stream;

-- V32: invitation cooperation drafts. Dropping the columns also drops their foreign keys to "user".
ALTER TABLE "${flyway:defaultSchema}".invitation
    DROP CONSTRAINT IF EXISTS invitation_profile_draft_author,
    DROP CONSTRAINT IF EXISTS invitation_terms_draft_author,
    DROP COLUMN IF EXISTS profile_draft,
    DROP COLUMN IF EXISTS terms_draft,
    DROP COLUMN IF EXISTS profile_prepared_by,
    DROP COLUMN IF EXISTS terms_prepared_by;

-- Audit projections and idempotent replay results copied the drafts and the created version identifiers.
UPDATE "${flyway:defaultSchema}".invitation_audit
SET before_data = before_data - 'cooperation',
    after_data  = after_data - 'cooperation' - 'profileVersionId' - 'termsVersionId'
WHERE before_data ? 'cooperation'
   OR after_data ?| ARRAY['cooperation', 'profileVersionId', 'termsVersionId'];

UPDATE "${flyway:defaultSchema}".invitation_command
SET result_json = result_json - 'cooperation'
WHERE result_json ? 'cooperation';
