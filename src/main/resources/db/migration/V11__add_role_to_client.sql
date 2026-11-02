ALTER TABLE client
    ADD COLUMN type TEXT;
UPDATE client
SET type = 'External'
WHERE type IS NULL;
ALTER TABLE client
    ALTER COLUMN type SET NOT NULL;
