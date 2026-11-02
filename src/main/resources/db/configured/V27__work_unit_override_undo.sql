LOCK TABLE "${flyway:defaultSchema}".work_unit, "${flyway:defaultSchema}".work_unit_override_history IN ACCESS EXCLUSIVE MODE;

ALTER TABLE "${flyway:defaultSchema}".work_unit_override_history
    ADD COLUMN parent_correction_id UUID,
    ADD COLUMN reverts_event_id UUID,
    ADD CONSTRAINT work_unit_override_history_work_unit_id_id_key UNIQUE (work_unit_id, id),
    DROP CONSTRAINT work_unit_pm_classification_history_operation_check,
    ADD CONSTRAINT work_unit_override_history_operation_check CHECK (operation IN ('Replace', 'Clear', 'Undo')),
    ADD CONSTRAINT work_unit_override_history_undo_shape_check CHECK (
        (operation = 'Undo' AND provenance = 'WorkUnitOverride' AND reverts_event_id IS NOT NULL)
        OR (operation <> 'Undo' AND reverts_event_id IS NULL)
    ),
    ADD CONSTRAINT work_unit_override_history_parent_fkey
        FOREIGN KEY (work_unit_id, parent_correction_id)
        REFERENCES "${flyway:defaultSchema}".work_unit_override_history (work_unit_id, id),
    ADD CONSTRAINT work_unit_override_history_reverts_fkey
        FOREIGN KEY (work_unit_id, reverts_event_id)
        REFERENCES "${flyway:defaultSchema}".work_unit_override_history (work_unit_id, id);

CREATE UNIQUE INDEX work_unit_override_history_reverts_event_id_key
    ON "${flyway:defaultSchema}".work_unit_override_history (reverts_event_id) WHERE reverts_event_id IS NOT NULL;

-- Legacy snapshots lack the original correction intent and break the undo chain.
-- Version gaps do not: use the immediately preceding recorded event, not version - 1.
WITH ancestry AS (
    SELECT id, provenance,
        lag(id) OVER (PARTITION BY work_unit_id ORDER BY version) AS parent_id,
        lag(provenance) OVER (PARTITION BY work_unit_id ORDER BY version) AS parent_provenance
    FROM "${flyway:defaultSchema}".work_unit_override_history
)
UPDATE "${flyway:defaultSchema}".work_unit_override_history h
SET parent_correction_id = a.parent_id
FROM ancestry a
WHERE h.id = a.id AND a.provenance = 'WorkUnitOverride' AND a.parent_provenance = 'WorkUnitOverride';

CREATE FUNCTION "${flyway:defaultSchema}".work_unit_override_history_validate_ancestry() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    parent_event "${flyway:defaultSchema}".work_unit_override_history%ROWTYPE;
    reverted_event "${flyway:defaultSchema}".work_unit_override_history%ROWTYPE;
BEGIN
    IF NEW.parent_correction_id IS NOT NULL THEN
        SELECT * INTO parent_event FROM "${flyway:defaultSchema}".work_unit_override_history
        WHERE work_unit_id = NEW.work_unit_id AND id = NEW.parent_correction_id;
        IF NOT FOUND OR parent_event.provenance <> 'WorkUnitOverride'
            OR parent_event.operation NOT IN ('Replace', 'Clear') OR parent_event.version >= NEW.version THEN
            RAISE EXCEPTION 'Correction parent must be an earlier full correction on the same work unit'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NEW.operation = 'Undo' THEN
        SELECT * INTO reverted_event FROM "${flyway:defaultSchema}".work_unit_override_history
        WHERE work_unit_id = NEW.work_unit_id AND id = NEW.reverts_event_id;
        IF NOT FOUND OR reverted_event.provenance <> 'WorkUnitOverride'
            OR reverted_event.operation NOT IN ('Replace', 'Clear') OR reverted_event.version >= NEW.version THEN
            RAISE EXCEPTION 'Undo target must be an earlier full correction on the same work unit'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.parent_correction_id IS DISTINCT FROM reverted_event.parent_correction_id THEN
            RAISE EXCEPTION 'Undo must restore the target correction parent'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER work_unit_override_history_validate_ancestry
    BEFORE INSERT ON "${flyway:defaultSchema}".work_unit_override_history
    FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".work_unit_override_history_validate_ancestry();
