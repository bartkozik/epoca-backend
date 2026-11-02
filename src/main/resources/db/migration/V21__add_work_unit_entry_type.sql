ALTER TABLE work_unit
    ADD COLUMN entry_type TEXT NOT NULL DEFAULT 'Worklog';

UPDATE work_unit
SET entry_type = 'Note'
WHERE duration = INTERVAL '0 seconds';

ALTER TABLE work_unit
    ADD CONSTRAINT work_unit_entry_type_check
        CHECK (entry_type IN ('Worklog', 'Note')),
    ADD CONSTRAINT work_unit_entry_type_duration_check
        CHECK (
            (entry_type = 'Worklog' AND duration >= INTERVAL '1 second')
            OR
            (entry_type = 'Note' AND duration = INTERVAL '0 seconds')
        );
