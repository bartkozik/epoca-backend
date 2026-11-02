CREATE TABLE work_unit_idempotency
(
    claim_id             UUID        NOT NULL PRIMARY KEY,
    organization_id      UUID        NOT NULL REFERENCES organization (id),
    actor_id             UUID        NOT NULL REFERENCES "user" (id),
    idempotency_key      UUID        NOT NULL,
    operation            TEXT        NOT NULL,
    target_work_unit_id  UUID        NULL,
    fingerprint_version  INTEGER     NOT NULL,
    canonical_request    JSONB       NULL,
    fingerprint          TEXT        NOT NULL,
    state                TEXT        NOT NULL,
    outcome_status       INTEGER     NULL,
    outcome_body         JSONB       NULL,
    result_work_unit_id  UUID        NULL,
    created_at           TIMESTAMPTZ NOT NULL,
    response_expires_at  TIMESTAMPTZ NULL,
    CONSTRAINT work_unit_idempotency_scope_key_unique
        UNIQUE (organization_id, actor_id, idempotency_key),
    CONSTRAINT work_unit_idempotency_operation_check
        CHECK (operation IN ('Create', 'Update')),
    CONSTRAINT work_unit_idempotency_command_target_check
        CHECK (
            (operation = 'Create' AND target_work_unit_id IS NULL)
            OR
            (operation = 'Update' AND target_work_unit_id IS NOT NULL)
        ),
    CONSTRAINT work_unit_idempotency_state_check
        CHECK (state IN ('Processing', 'Completed', 'Tombstone')),
    CONSTRAINT work_unit_idempotency_terminal_check
        CHECK (
            (state = 'Processing' AND outcome_status IS NULL AND outcome_body IS NULL AND result_work_unit_id IS NULL AND response_expires_at IS NULL)
            OR
            (state = 'Completed' AND outcome_status IS NOT NULL AND outcome_body IS NOT NULL AND response_expires_at IS NOT NULL)
            OR
            (state = 'Tombstone' AND outcome_status IS NOT NULL AND outcome_body IS NULL AND canonical_request IS NULL AND response_expires_at IS NOT NULL)
        )
);

CREATE INDEX work_unit_idempotency_expired_response_idx
    ON work_unit_idempotency (response_expires_at)
    WHERE state = 'Completed';
