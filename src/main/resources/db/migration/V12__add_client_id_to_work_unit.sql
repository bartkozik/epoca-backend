TRUNCATE TABLE work_unit;
ALTER TABLE work_unit
    ADD COLUMN client_id UUID NOT NULL REFERENCES client(id);
