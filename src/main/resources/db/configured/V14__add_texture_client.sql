ALTER TABLE client
    ADD COLUMN texture TEXT;
UPDATE client
    SET texture = ''
    WHERE texture IS NULL;
ALTER TABLE client
    ALTER COLUMN texture SET NOT NULL;
