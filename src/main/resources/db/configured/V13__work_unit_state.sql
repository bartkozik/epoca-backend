ALTER TABLE work_unit
    ADD COLUMN state TEXT;
UPDATE work_unit
SET state = 'Active'
WHERE state IS NULL;
ALTER TABLE work_unit
    ALTER COLUMN state SET NOT NULL;
