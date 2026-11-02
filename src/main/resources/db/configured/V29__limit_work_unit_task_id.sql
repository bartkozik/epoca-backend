-- Drain writers for this maintenance migration. The lock protects preflight,
-- audit and cleanup from concurrent inserts/updates throughout the transaction.
LOCK TABLE "${flyway:defaultSchema}".work_unit IN ACCESS EXCLUSIVE MODE;

DO $$
DECLARE
    collision_groups BIGINT;
    collision_rows BIGINT;
BEGIN
    SELECT count(*), coalesce(sum(affected_rows), 0)
    INTO collision_groups, collision_rows
    FROM (
        SELECT count(*) AS affected_rows
        FROM "${flyway:defaultSchema}".work_unit
        WHERE task_id IS NOT NULL
        GROUP BY left(task_id, 255)
        HAVING count(DISTINCT task_id) > 1
            AND bool_or(char_length(task_id) > 255)
    ) collisions;

    IF collision_groups > 0 THEN
        -- Source identifiers are sensitive: report aggregate counts only.
        RAISE EXCEPTION 'Source task ID limit: collision groups=%, affected rows=%',
            collision_groups, collision_rows USING ERRCODE = '23514';
    END IF;
END $$;

-- Protected migration evidence, separate from product correction history.
-- No foreign key: source deletion must not be blocked by this audit.
CREATE TABLE "${flyway:defaultSchema}".work_unit_task_id_trunc_audit (
    work_unit_id UUID PRIMARY KEY,
    old_task_id TEXT NOT NULL,
    new_task_id TEXT NOT NULL,
    truncated_at TIMESTAMPTZ NOT NULL
);

INSERT INTO "${flyway:defaultSchema}".work_unit_task_id_trunc_audit
    (work_unit_id, old_task_id, new_task_id, truncated_at)
SELECT id, task_id, left(task_id, 255), CURRENT_TIMESTAMP
FROM "${flyway:defaultSchema}".work_unit
WHERE char_length(task_id) > 255;

DO $$
DECLARE
    client_trigger_mode "char";
BEGIN
    SELECT tgenabled INTO STRICT client_trigger_mode
    FROM pg_catalog.pg_trigger
    WHERE tgrelid = '"${flyway:defaultSchema}".work_unit'::regclass
        AND tgname = 'work_unit_client_id_from_project'
        AND NOT tgisinternal;

    -- V19 would otherwise reconcile stale client_id values as a side effect.
    -- Leave V26's revision trigger active so each affected row advances once.
    ALTER TABLE "${flyway:defaultSchema}".work_unit
        DISABLE TRIGGER work_unit_client_id_from_project;

    UPDATE "${flyway:defaultSchema}".work_unit
    SET task_id = left(task_id, 255)
    WHERE char_length(task_id) > 255;

    CASE client_trigger_mode
        WHEN 'O' THEN
            ALTER TABLE "${flyway:defaultSchema}".work_unit
                ENABLE TRIGGER work_unit_client_id_from_project;
        WHEN 'A' THEN
            ALTER TABLE "${flyway:defaultSchema}".work_unit
                ENABLE ALWAYS TRIGGER work_unit_client_id_from_project;
        WHEN 'R' THEN
            ALTER TABLE "${flyway:defaultSchema}".work_unit
                ENABLE REPLICA TRIGGER work_unit_client_id_from_project;
        WHEN 'D' THEN
            ALTER TABLE "${flyway:defaultSchema}".work_unit
                DISABLE TRIGGER work_unit_client_id_from_project;
        ELSE
            RAISE EXCEPTION 'Source task ID limit: unexpected client trigger mode';
    END CASE;
END $$;

ALTER TABLE "${flyway:defaultSchema}".work_unit
    ADD CONSTRAINT work_unit_task_id_maxlen_chk CHECK (char_length(task_id) <= 255);
