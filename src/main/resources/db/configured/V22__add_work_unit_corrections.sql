ALTER TABLE work_unit
    ADD COLUMN corrected_project_id UUID NULL,
    ADD COLUMN task_id_override_set BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN corrected_task_id TEXT NULL,
    ADD COLUMN work_unit_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN correction_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN last_corrected_by UUID NULL,
    ADD COLUMN last_corrected_at TIMESTAMPTZ NULL,
    ADD CONSTRAINT work_unit_corrected_project_id_fkey
        FOREIGN KEY (corrected_project_id) REFERENCES project(id),
    ADD CONSTRAINT work_unit_last_corrected_by_fkey
        FOREIGN KEY (last_corrected_by) REFERENCES "user"(id),
    ADD CONSTRAINT work_unit_correction_revision_check
        CHECK (
            work_unit_revision >= 0
            AND correction_version >= 0
            AND work_unit_revision >= correction_version
        ),
    ADD CONSTRAINT work_unit_correction_task_id_check
        CHECK (
            (task_id_override_set OR corrected_task_id IS NULL)
            AND (corrected_task_id IS NULL OR btrim(corrected_task_id) <> '')
        ),
    ADD CONSTRAINT work_unit_correction_metadata_check
        CHECK (
            (
                correction_version = 0
                AND corrected_project_id IS NULL
                AND task_id_override_set = FALSE
                AND corrected_task_id IS NULL
                AND last_corrected_by IS NULL
                AND last_corrected_at IS NULL
            )
            OR
            (
                correction_version > 0
                AND last_corrected_by IS NOT NULL
                AND last_corrected_at IS NOT NULL
            )
        );

CREATE TABLE work_unit_correction_history (
    id UUID PRIMARY KEY,
    work_unit_id UUID NOT NULL REFERENCES work_unit(id),
    version BIGINT NOT NULL CHECK (version > 0),
    actor_id UUID NOT NULL REFERENCES "user"(id),
    changed_at TIMESTAMPTZ NOT NULL,
    source_project_id UUID NOT NULL REFERENCES project(id),
    source_task_id TEXT NULL,
    before_corrected_project_id UUID NULL REFERENCES project(id),
    before_task_id_override_set BOOLEAN NOT NULL,
    before_corrected_task_id TEXT NULL,
    before_effective_project_id UUID NOT NULL REFERENCES project(id),
    before_effective_task_id TEXT NULL,
    after_corrected_project_id UUID NULL REFERENCES project(id),
    after_task_id_override_set BOOLEAN NOT NULL,
    after_corrected_task_id TEXT NULL,
    after_effective_project_id UUID NOT NULL REFERENCES project(id),
    after_effective_task_id TEXT NULL,
    operation TEXT NOT NULL CHECK (operation IN ('Replace', 'Clear')),
    CONSTRAINT work_unit_correction_history_before_task_check CHECK (
        (before_task_id_override_set OR before_corrected_task_id IS NULL)
        AND (before_corrected_task_id IS NULL OR btrim(before_corrected_task_id) <> '')
    ),
    CONSTRAINT work_unit_correction_history_after_task_check CHECK (
        (after_task_id_override_set OR after_corrected_task_id IS NULL)
        AND (after_corrected_task_id IS NULL OR btrim(after_corrected_task_id) <> '')
    ),
    UNIQUE (work_unit_id, version)
);

CREATE INDEX work_unit_correction_history_work_unit_version_idx
    ON work_unit_correction_history (work_unit_id, version DESC);
