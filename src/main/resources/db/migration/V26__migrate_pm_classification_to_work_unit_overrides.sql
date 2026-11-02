-- Coordinated cutover: drain all old application writers before applying V26.
LOCK TABLE "${flyway:defaultSchema}".work_unit, "${flyway:defaultSchema}".work_unit_pm_classification,
    "${flyway:defaultSchema}".work_unit_pm_classification_history IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM "${flyway:defaultSchema}".work_unit_pm_classification c
        JOIN LATERAL (
            SELECT * FROM "${flyway:defaultSchema}".work_unit_pm_classification_history h
            WHERE h.work_unit_id = c.work_unit_id ORDER BY version DESC LIMIT 1
        ) h ON TRUE
        WHERE c.version < h.version OR (c.version = h.version AND (
            c.pm_project_id IS DISTINCT FROM h.after_pm_project_id OR
            c.pm_task_id IS DISTINCT FROM h.after_pm_task_id OR
            c.last_changed_by IS DISTINCT FROM h.actor_id OR
            c.last_changed_at IS DISTINCT FROM h.changed_at
        ))
    ) THEN
        RAISE EXCEPTION 'Work-unit override migration: contradictory current/history state';
    END IF;
END $$;

ALTER TABLE "${flyway:defaultSchema}".work_unit
    ADD COLUMN override_project_id UUID REFERENCES "${flyway:defaultSchema}".project(id),
    ADD COLUMN task_id_override_set BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN override_task_id TEXT,
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    ADD COLUMN override_version BIGINT NOT NULL DEFAULT 0 CHECK (override_version >= 0),
    ADD COLUMN override_has_history BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN override_last_changed_by UUID REFERENCES "${flyway:defaultSchema}"."user"(id),
    ADD COLUMN override_last_changed_at TIMESTAMPTZ,
    ADD CONSTRAINT work_unit_override_task_valid CHECK (
        (task_id_override_set OR override_task_id IS NULL) AND
        (override_task_id IS NULL OR (btrim(override_task_id) <> '' AND char_length(override_task_id) <= 255))
    ),
    ADD CONSTRAINT work_unit_override_metadata_valid CHECK (
        (override_version = 0 AND override_last_changed_by IS NULL AND override_last_changed_at IS NULL
            AND override_project_id IS NULL AND NOT task_id_override_set AND NOT override_has_history)
        OR (override_version > 0 AND override_last_changed_by IS NOT NULL AND override_last_changed_at IS NOT NULL)
    );

CREATE TEMPORARY TABLE ct567_known_state ON COMMIT DROP AS
WITH latest_history AS (
    SELECT DISTINCT ON (work_unit_id) *
    FROM "${flyway:defaultSchema}".work_unit_pm_classification_history
    ORDER BY work_unit_id, version DESC
)
SELECT c.work_unit_id, c.pm_project_id, c.pm_task_id, c.version, c.last_changed_by, c.last_changed_at
FROM "${flyway:defaultSchema}".work_unit_pm_classification c
UNION ALL
SELECT h.work_unit_id, h.after_pm_project_id, h.after_pm_task_id,
    h.version, h.actor_id, h.changed_at
FROM latest_history h
WHERE NOT EXISTS (SELECT 1 FROM "${flyway:defaultSchema}".work_unit_pm_classification c WHERE c.work_unit_id = h.work_unit_id);

UPDATE "${flyway:defaultSchema}".work_unit w SET
    override_project_id = s.pm_project_id,
    task_id_override_set = s.pm_task_id IS NOT NULL,
    override_task_id = s.pm_task_id,
    revision = s.version,
    override_version = s.version,
    override_has_history = EXISTS (SELECT 1 FROM "${flyway:defaultSchema}".work_unit_pm_classification_history h WHERE h.work_unit_id = w.id),
    override_last_changed_by = s.last_changed_by,
    override_last_changed_at = s.last_changed_at
FROM ct567_known_state s WHERE w.id = s.work_unit_id;

DO $$
BEGIN
    IF (SELECT count(*) FROM ct567_known_state) <> (SELECT count(*) FROM "${flyway:defaultSchema}".work_unit WHERE override_version > 0)
        OR EXISTS (
            SELECT 1 FROM ct567_known_state s LEFT JOIN "${flyway:defaultSchema}".work_unit w ON w.id = s.work_unit_id
            WHERE w.id IS NULL OR w.override_project_id IS DISTINCT FROM s.pm_project_id
                OR w.override_task_id IS DISTINCT FROM s.pm_task_id
                OR w.task_id_override_set IS DISTINCT FROM (s.pm_task_id IS NOT NULL)
                OR w.override_version IS DISTINCT FROM s.version OR w.revision IS DISTINCT FROM s.version
                OR w.override_last_changed_by IS DISTINCT FROM s.last_changed_by
                OR w.override_last_changed_at IS DISTINCT FROM s.last_changed_at
                OR w.override_has_history IS DISTINCT FROM EXISTS (
                    SELECT 1 FROM "${flyway:defaultSchema}".work_unit_pm_classification_history h WHERE h.work_unit_id = w.id)
        ) THEN
        RAISE EXCEPTION 'Work-unit override migration: current-state reconciliation failed';
    END IF;
END $$;

-- Rename preserves every legacy event byte-for-byte, including its identity and labels.
-- V23 lost source snapshots and the distinction between clear and inherit. Never infer them.
ALTER TABLE "${flyway:defaultSchema}".work_unit_pm_classification_history RENAME TO work_unit_override_history;
ALTER TABLE "${flyway:defaultSchema}".work_unit_override_history
    ADD COLUMN provenance TEXT NOT NULL DEFAULT 'LegacyPmSnapshot',
    ADD COLUMN before_snapshot JSONB,
    ADD COLUMN after_snapshot JSONB,
    ADD CONSTRAINT work_unit_override_history_provenance_valid CHECK (
        (provenance = 'LegacyPmSnapshot' AND before_snapshot IS NULL AND after_snapshot IS NULL)
        OR (provenance = 'WorkUnitOverride' AND before_snapshot IS NOT NULL AND after_snapshot IS NOT NULL)
    );
ALTER TABLE "${flyway:defaultSchema}".work_unit_override_history ALTER COLUMN provenance DROP DEFAULT;

DROP TABLE "${flyway:defaultSchema}".work_unit_pm_classification;

CREATE INDEX work_unit_override_effective_project_idx
    ON "${flyway:defaultSchema}".work_unit (organization_id, (COALESCE(override_project_id, project_id)));

-- Every source/state mutation invalidates an already-open override form, including
-- archive and administrative SQL writers. Override writes also advance this token.
CREATE FUNCTION "${flyway:defaultSchema}".work_unit_advance_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.user_id, NEW.organization_id, NEW.project_id, NEW.task_id, NEW.date, NEW.duration,
           NEW.entry_type, NEW.description, NEW.state, NEW.created_at, NEW.modified_at, NEW.archived_at,
           NEW.override_project_id, NEW.task_id_override_set, NEW.override_task_id, NEW.override_version)
        IS DISTINCT FROM
       ROW(OLD.user_id, OLD.organization_id, OLD.project_id, OLD.task_id, OLD.date, OLD.duration,
           OLD.entry_type, OLD.description, OLD.state, OLD.created_at, OLD.modified_at, OLD.archived_at,
           OLD.override_project_id, OLD.task_id_override_set, OLD.override_task_id, OLD.override_version)
    THEN NEW.revision := OLD.revision + 1;
    ELSE NEW.revision := OLD.revision;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER work_unit_advance_revision BEFORE UPDATE ON "${flyway:defaultSchema}".work_unit
    FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".work_unit_advance_revision();
