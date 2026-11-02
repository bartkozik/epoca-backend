LOCK TABLE "${flyway:defaultSchema}".work_unit, "${flyway:defaultSchema}".work_unit_correction_history IN ACCESS EXCLUSIVE MODE;

CREATE TABLE "${flyway:defaultSchema}".work_unit_pm_classification
(
    work_unit_id    UUID        NOT NULL PRIMARY KEY REFERENCES "${flyway:defaultSchema}".work_unit (id),
    pm_project_id   UUID        NULL REFERENCES "${flyway:defaultSchema}".project (id),
    pm_task_id      TEXT        NULL,
    version         BIGINT      NOT NULL,
    last_changed_by UUID        NOT NULL REFERENCES "${flyway:defaultSchema}"."user" (id),
    last_changed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT work_unit_pm_classification_version_check CHECK (version > 0),
    CONSTRAINT work_unit_pm_task_id_valid CHECK (
        pm_task_id IS NULL
        OR (btrim(pm_task_id) <> '' AND char_length(pm_task_id) <= 255)
    )
);

CREATE INDEX work_unit_pm_classification_project_idx
    ON "${flyway:defaultSchema}".work_unit_pm_classification (pm_project_id);

CREATE TABLE "${flyway:defaultSchema}".work_unit_pm_classification_history
(
    id                     UUID        NOT NULL PRIMARY KEY,
    work_unit_id           UUID        NOT NULL REFERENCES "${flyway:defaultSchema}".work_unit (id),
    version                BIGINT      NOT NULL,
    operation              TEXT        NOT NULL,
    actor_id               UUID        NOT NULL REFERENCES "${flyway:defaultSchema}"."user" (id),
    actor_display_name     TEXT        NOT NULL,
    changed_at             TIMESTAMPTZ NOT NULL,
    before_pm_project_id   UUID        NULL,
    before_pm_project_name TEXT        NULL,
    before_pm_client_id    UUID        NULL,
    before_pm_client_name  TEXT        NULL,
    before_pm_task_id      TEXT        NULL,
    after_pm_project_id    UUID        NULL,
    after_pm_project_name  TEXT        NULL,
    after_pm_client_id     UUID        NULL,
    after_pm_client_name   TEXT        NULL,
    after_pm_task_id       TEXT        NULL,
    CONSTRAINT work_unit_pm_classification_history_work_unit_id_version_key UNIQUE (work_unit_id, version),
    CONSTRAINT work_unit_pm_classification_history_version_check CHECK (version > 0),
    CONSTRAINT work_unit_pm_classification_history_operation_check CHECK (operation IN ('Replace', 'Clear')),
    CONSTRAINT work_unit_pm_classification_history_before_pm_task_id_valid CHECK (
        before_pm_task_id IS NULL
        OR (btrim(before_pm_task_id) <> '' AND char_length(before_pm_task_id) <= 255)
    ),
    CONSTRAINT work_unit_pm_classification_history_after_pm_task_id_valid CHECK (
        after_pm_task_id IS NULL
        OR (btrim(after_pm_task_id) <> '' AND char_length(after_pm_task_id) <= 255)
    )
);

CREATE INDEX work_unit_pm_classification_history_version_idx
    ON "${flyway:defaultSchema}".work_unit_pm_classification_history (work_unit_id, version DESC);

INSERT INTO "${flyway:defaultSchema}".work_unit_pm_classification (
    work_unit_id,
    pm_project_id,
    pm_task_id,
    version,
    last_changed_by,
    last_changed_at
)
SELECT
    work_unit.id,
    work_unit.corrected_project_id,
    CASE
        WHEN work_unit.task_id_override_set THEN work_unit.corrected_task_id
        ELSE NULL
    END,
    work_unit.correction_version,
    work_unit.last_corrected_by,
    work_unit.last_corrected_at
FROM "${flyway:defaultSchema}".work_unit
WHERE work_unit.correction_version > 0;

INSERT INTO "${flyway:defaultSchema}".work_unit_pm_classification_history (
    id,
    work_unit_id,
    version,
    operation,
    actor_id,
    actor_display_name,
    changed_at,
    before_pm_project_id,
    before_pm_project_name,
    before_pm_client_id,
    before_pm_client_name,
    before_pm_task_id,
    after_pm_project_id,
    after_pm_project_name,
    after_pm_client_id,
    after_pm_client_name,
    after_pm_task_id
)
SELECT
    history.id,
    history.work_unit_id,
    history.version,
    history.operation,
    history.actor_id,
    actor.first_name || ' ' || actor.last_name,
    history.changed_at,
    history.before_corrected_project_id,
    before_project.name,
    before_client.id,
    before_client.name,
    CASE
        WHEN history.before_task_id_override_set THEN history.before_corrected_task_id
        ELSE NULL
    END,
    history.after_corrected_project_id,
    after_project.name,
    after_client.id,
    after_client.name,
    CASE
        WHEN history.after_task_id_override_set THEN history.after_corrected_task_id
        ELSE NULL
    END
FROM "${flyway:defaultSchema}".work_unit_correction_history history
JOIN "${flyway:defaultSchema}"."user" actor ON actor.id = history.actor_id
LEFT JOIN "${flyway:defaultSchema}".project before_project ON before_project.id = history.before_corrected_project_id
LEFT JOIN "${flyway:defaultSchema}".client before_client ON before_client.id = before_project.client_id
LEFT JOIN "${flyway:defaultSchema}".project after_project ON after_project.id = history.after_corrected_project_id
LEFT JOIN "${flyway:defaultSchema}".client after_client ON after_client.id = after_project.client_id;

DO
$$
BEGIN
    IF (SELECT count(*) FROM "${flyway:defaultSchema}".work_unit_pm_classification)
        <> (SELECT count(*) FROM "${flyway:defaultSchema}".work_unit WHERE correction_version > 0) THEN
        RAISE EXCEPTION 'PM classification current-row count verification failed';
    END IF;

    IF (SELECT max(version) FROM "${flyway:defaultSchema}".work_unit_pm_classification)
        IS DISTINCT FROM (SELECT max(correction_version) FROM "${flyway:defaultSchema}".work_unit WHERE correction_version > 0) THEN
        RAISE EXCEPTION 'PM classification current max-version verification failed';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM "${flyway:defaultSchema}".work_unit source
        FULL JOIN "${flyway:defaultSchema}".work_unit_pm_classification migrated
            ON migrated.work_unit_id = source.id
           AND source.correction_version > 0
        WHERE (source.correction_version > 0 OR migrated.work_unit_id IS NOT NULL)
          AND (
              migrated.work_unit_id IS DISTINCT FROM source.id
              OR migrated.pm_project_id IS DISTINCT FROM source.corrected_project_id
              OR migrated.pm_task_id IS DISTINCT FROM (
                  CASE WHEN source.task_id_override_set THEN source.corrected_task_id ELSE NULL END
              )
              OR migrated.version IS DISTINCT FROM source.correction_version
              OR migrated.last_changed_by IS DISTINCT FROM source.last_corrected_by
              OR migrated.last_changed_at IS DISTINCT FROM source.last_corrected_at
          )
    ) THEN
        RAISE EXCEPTION 'PM classification current-value verification failed';
    END IF;

    IF (SELECT count(*) FROM "${flyway:defaultSchema}".work_unit_pm_classification_history)
        <> (SELECT count(*) FROM "${flyway:defaultSchema}".work_unit_correction_history) THEN
        RAISE EXCEPTION 'PM classification history-row count verification failed';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM (
            SELECT work_unit_id, max(version) AS version
            FROM "${flyway:defaultSchema}".work_unit_correction_history
            GROUP BY work_unit_id
        ) source
        FULL JOIN (
            SELECT work_unit_id, max(version) AS version
            FROM "${flyway:defaultSchema}".work_unit_pm_classification_history
            GROUP BY work_unit_id
        ) migrated USING (work_unit_id)
        WHERE source.work_unit_id IS NULL
           OR migrated.work_unit_id IS NULL
           OR source.version IS DISTINCT FROM migrated.version
    ) THEN
        RAISE EXCEPTION 'PM classification history max-version verification failed';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM "${flyway:defaultSchema}".work_unit_correction_history source
        JOIN "${flyway:defaultSchema}"."user" actor ON actor.id = source.actor_id
        LEFT JOIN "${flyway:defaultSchema}".project before_project ON before_project.id = source.before_corrected_project_id
        LEFT JOIN "${flyway:defaultSchema}".client before_client ON before_client.id = before_project.client_id
        LEFT JOIN "${flyway:defaultSchema}".project after_project ON after_project.id = source.after_corrected_project_id
        LEFT JOIN "${flyway:defaultSchema}".client after_client ON after_client.id = after_project.client_id
        FULL JOIN "${flyway:defaultSchema}".work_unit_pm_classification_history migrated ON migrated.id = source.id
        WHERE migrated.id IS NULL
           OR migrated.work_unit_id IS DISTINCT FROM source.work_unit_id
           OR migrated.version IS DISTINCT FROM source.version
           OR migrated.operation IS DISTINCT FROM source.operation
           OR migrated.actor_id IS DISTINCT FROM source.actor_id
           OR migrated.actor_display_name IS DISTINCT FROM (actor.first_name || ' ' || actor.last_name)
           OR migrated.changed_at IS DISTINCT FROM source.changed_at
           OR migrated.before_pm_project_id IS DISTINCT FROM source.before_corrected_project_id
           OR migrated.before_pm_project_name IS DISTINCT FROM before_project.name
           OR migrated.before_pm_client_id IS DISTINCT FROM before_client.id
           OR migrated.before_pm_client_name IS DISTINCT FROM before_client.name
           OR migrated.before_pm_task_id IS DISTINCT FROM (
               CASE WHEN source.before_task_id_override_set THEN source.before_corrected_task_id ELSE NULL END
           )
           OR migrated.after_pm_project_id IS DISTINCT FROM source.after_corrected_project_id
           OR migrated.after_pm_project_name IS DISTINCT FROM after_project.name
           OR migrated.after_pm_client_id IS DISTINCT FROM after_client.id
           OR migrated.after_pm_client_name IS DISTINCT FROM after_client.name
           OR migrated.after_pm_task_id IS DISTINCT FROM (
               CASE WHEN source.after_task_id_override_set THEN source.after_corrected_task_id ELSE NULL END
           )
    ) THEN
        RAISE EXCEPTION 'PM classification history-value verification failed';
    END IF;
END;
$$;

DROP INDEX IF EXISTS "${flyway:defaultSchema}".work_unit_effective_project_idx;

DROP TABLE "${flyway:defaultSchema}".work_unit_correction_history;

ALTER TABLE "${flyway:defaultSchema}".work_unit
    DROP CONSTRAINT work_unit_corrected_project_id_fkey,
    DROP CONSTRAINT work_unit_last_corrected_by_fkey,
    DROP CONSTRAINT work_unit_correction_revision_check,
    DROP CONSTRAINT work_unit_correction_task_id_check,
    DROP CONSTRAINT work_unit_correction_metadata_check,
    DROP COLUMN corrected_project_id,
    DROP COLUMN task_id_override_set,
    DROP COLUMN corrected_task_id,
    DROP COLUMN work_unit_revision,
    DROP COLUMN correction_version,
    DROP COLUMN last_corrected_by,
    DROP COLUMN last_corrected_at;
