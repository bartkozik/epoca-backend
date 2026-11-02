ALTER TABLE client
    ADD COLUMN logo TEXT;
UPDATE client
SET logo = ''
WHERE logo IS NULL;
ALTER TABLE client
    ALTER COLUMN logo SET NOT NULL;
