\set ON_ERROR_STOP on
\if :{?test_force_verification_failure}
\else
\set test_force_verification_failure false
\endif

SET search_path = pg_temp, pg_catalog, public;
\if :standalone_verify
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '5min';

CREATE TEMP TABLE demo_seed_parameters
(
    target                       TEXT    NOT NULL,
    operation                    TEXT    NOT NULL,
    organization_slug            TEXT    NOT NULL,
    seed_today                   DATE    NOT NULL,
    demo_viewer_email            TEXT    NOT NULL,
    expected_organization_id     UUID    NULL,
    expected_database_name       TEXT    NOT NULL,
    expected_system_identifier   TEXT    NOT NULL,
    expected_server_address      TEXT    NOT NULL,
    expected_server_port         INTEGER NOT NULL,
    sql_test_mode                BOOLEAN NOT NULL,
    test_force_verification_failure BOOLEAN NOT NULL,
    standalone_verify            BOOLEAN NOT NULL
) ON COMMIT PRESERVE ROWS;

INSERT INTO demo_seed_parameters (
    target,
    operation,
    organization_slug,
    seed_today,
    demo_viewer_email,
    expected_organization_id,
    expected_database_name,
    expected_system_identifier,
    expected_server_address,
    expected_server_port,
    sql_test_mode,
    test_force_verification_failure,
    standalone_verify
)
VALUES (
    :'target'::TEXT,
    :'operation'::TEXT,
    :'organization_slug'::TEXT,
    :'seed_today'::DATE,
    :'demo_viewer_email'::TEXT,
    NULLIF(:'expected_organization_id', '')::UUID,
    :'expected_database_name'::TEXT,
    :'expected_system_identifier'::TEXT,
    :'expected_server_address'::TEXT,
    :'expected_server_port'::INTEGER,
    :'sql_test_mode'::BOOLEAN,
    :'test_force_verification_failure'::BOOLEAN,
    :'standalone_verify'::BOOLEAN
);

-- This key serializes seed/dry-run/verify tooling only; ordinary application writers do not acquire it.
SELECT pg_catalog.pg_advisory_lock(
           pg_catalog.hashtextextended('CT-532:' || organization_slug, 0)
       )
FROM demo_seed_parameters;

DO
$$
DECLARE
    parameters demo_seed_parameters%ROWTYPE;
    missing_columns TEXT;
    trigger_definition TEXT;
    trigger_enabled "char";
    trigger_function OID;
    trigger_is_internal BOOLEAN;
    valid_work_unit_constraint_count INTEGER;
BEGIN
    SELECT * INTO STRICT parameters FROM demo_seed_parameters;

    IF parameters.target NOT IN ('local', 'staging') THEN
        RAISE EXCEPTION 'demo seed verification rejects unsupported target';
    END IF;
    IF parameters.operation <> 'verify' THEN
        RAISE EXCEPTION 'standalone demo seed verification requires verify operation';
    END IF;
    IF parameters.organization_slug <> 'iteratorstesting' THEN
        RAISE EXCEPTION 'demo seed verification rejects unexpected organization slug';
    END IF;
    IF parameters.test_force_verification_failure
       AND NOT (
           parameters.target = 'local'
           AND parameters.sql_test_mode
           AND pg_catalog.current_database() ~ '^epoca_ct532_case_[0-9]+_[0-9]+$'
       ) THEN
        RAISE EXCEPTION 'test verification failure flag is restricted to disposable local SQL test databases';
    END IF;
    IF pg_catalog.current_database() <> parameters.expected_database_name THEN
        RAISE EXCEPTION 'demo seed verification database identity mismatch';
    END IF;
    IF (pg_catalog.pg_control_system()).system_identifier::TEXT <> parameters.expected_system_identifier THEN
        RAISE EXCEPTION 'demo seed verification system identifier mismatch';
    END IF;
    IF pg_catalog.host(pg_catalog.inet_server_addr()) <> parameters.expected_server_address THEN
        RAISE EXCEPTION 'demo seed verification server address mismatch';
    END IF;
    IF pg_catalog.inet_server_port() <> parameters.expected_server_port THEN
        RAISE EXCEPTION 'demo seed verification server port mismatch';
    END IF;

    IF parameters.target = 'local' THEN
        IF parameters.expected_organization_id IS NOT NULL OR parameters.demo_viewer_email <> '' THEN
            RAISE EXCEPTION 'local demo seed verification rejects staging parameters';
        END IF;
        IF parameters.sql_test_mode THEN
            IF pg_catalog.current_database() !~ '^epoca_ct532_case_[0-9]+_[0-9]+$' THEN
                RAISE EXCEPTION 'SQL test mode requires an exact disposable database name';
            END IF;
        ELSIF pg_catalog.host(pg_catalog.inet_server_addr()) NOT IN ('127.0.0.1', '::1') THEN
            RAISE EXCEPTION 'local demo seed verification requires a loopback PostgreSQL server';
        END IF;
    ELSE
        IF parameters.sql_test_mode OR parameters.expected_organization_id IS NULL OR parameters.demo_viewer_email = '' THEN
            RAISE EXCEPTION 'staging demo seed verification parameters are incomplete';
        END IF;
    END IF;

    SELECT pg_catalog.string_agg(expected.table_name || '.' || expected.column_name, ', ' ORDER BY expected.table_name, expected.column_name)
      INTO missing_columns
      FROM (VALUES
          ('user', 'id'), ('user', 'is_super_admin'), ('user', 'archived_at'),
          ('user_identity', 'id'), ('user_identity', 'user_id'), ('user_identity', 'type'),
          ('user_identity', 'value'), ('user_identity', 'main'), ('user_identity', 'device_id'),
          ('user_identity', 'confirmed'), ('user_identity', 'confirmation_code'),
          ('user_password', 'user_identity_id'), ('user_password', 'password'),
          ('user_login_token', 'user_identity_id'),
          ('refresh_token', 'user_id'), ('invitation', 'identity_value'),
          ('organization', 'id'), ('organization', 'slug'), ('organization', 'archived_at'),
          ('organization_user', 'id'), ('organization_user', 'organization_id'),
          ('organization_user', 'user_id'), ('organization_user', 'roles'), ('organization_user', 'archived_at'),
          ('client', 'id'), ('client', 'name'), ('client', 'color'),
          ('client', 'organization_id'), ('client', 'created_at'), ('client', 'created_by'),
          ('client', 'modified_at'), ('client', 'modified_by'), ('client', 'archived_at'),
          ('client', 'archived_by'), ('client', 'logo'), ('client', 'type'), ('client', 'texture'),
          ('project', 'id'), ('project', 'name'), ('project', 'description'),
          ('project', 'organization_id'), ('project', 'client_id'), ('project', 'created_at'),
          ('project', 'created_by'), ('project', 'modified_at'), ('project', 'modified_by'),
          ('project', 'archived_at'), ('project', 'archived_by'),
          ('project_user', 'id'), ('project_user', 'project_id'), ('project_user', 'user_id'),
          ('project_user', 'role'), ('project_user', 'created_at'), ('project_user', 'archived_at'),
          ('work_unit', 'id'), ('work_unit', 'organization_id'), ('work_unit', 'user_id'),
          ('work_unit', 'project_id'), ('work_unit', 'client_id'), ('work_unit', 'date'),
          ('work_unit', 'duration'), ('work_unit', 'description'), ('work_unit', 'task_id'),
          ('work_unit', 'state'), ('work_unit', 'entry_type'), ('work_unit', 'created_at'),
          ('work_unit', 'modified_at'), ('work_unit', 'archived_at'),
          ('work_unit_idempotency', 'claim_id'),
          ('work_unit_idempotency', 'target_work_unit_id'),
          ('work_unit_idempotency', 'result_work_unit_id'),
          ('saml_request', 'user_id')
      ) AS expected(table_name, column_name)
      LEFT JOIN information_schema.columns actual
        ON actual.table_schema = 'public'
       AND actual.table_name = expected.table_name
       AND actual.column_name = expected.column_name
     WHERE actual.column_name IS NULL;

    IF missing_columns IS NOT NULL THEN
        RAISE EXCEPTION 'demo seed verification schema is incomplete: %', missing_columns;
    END IF;

    SELECT pg_catalog.count(*)
      INTO valid_work_unit_constraint_count
      FROM pg_catalog.pg_constraint constraint_row
     WHERE constraint_row.conrelid = pg_catalog.to_regclass('public.work_unit')
       AND constraint_row.convalidated
       AND (
           (constraint_row.conname = 'work_unit_pkey'
            AND pg_catalog.pg_get_constraintdef(constraint_row.oid, true) = 'PRIMARY KEY (id)')
           OR (constraint_row.conname = 'work_unit_organization_id_fkey'
               AND pg_catalog.pg_get_constraintdef(constraint_row.oid, true) = 'FOREIGN KEY (organization_id) REFERENCES organization(id)')
           OR (constraint_row.conname = 'work_unit_user_id_fkey'
               AND pg_catalog.pg_get_constraintdef(constraint_row.oid, true) = 'FOREIGN KEY (user_id) REFERENCES "user"(id)')
           OR (constraint_row.conname = 'work_unit_project_id_fkey'
               AND pg_catalog.pg_get_constraintdef(constraint_row.oid, true) = 'FOREIGN KEY (project_id) REFERENCES project(id)')
           OR (constraint_row.conname = 'work_unit_client_id_fkey'
               AND pg_catalog.pg_get_constraintdef(constraint_row.oid, true) = 'FOREIGN KEY (client_id) REFERENCES client(id)')
           OR (constraint_row.conname = 'work_unit_entry_type_check'
               AND pg_catalog.pg_get_constraintdef(constraint_row.oid, true) =
                   'CHECK (entry_type = ANY (ARRAY[''Worklog''::text, ''Note''::text]))')
           OR (constraint_row.conname = 'work_unit_entry_type_duration_check'
               AND pg_catalog.pg_get_constraintdef(constraint_row.oid, true) =
                   'CHECK (entry_type = ''Worklog''::text AND duration >= ''00:00:01''::interval OR entry_type = ''Note''::text AND duration = ''00:00:00''::interval)')
       );

    IF valid_work_unit_constraint_count <> 7 THEN
        RAISE EXCEPTION 'demo seed verification schema preflight found unexpected work unit constraints';
    END IF;

    SELECT pg_catalog.pg_get_triggerdef(trigger.oid, true),
           trigger.tgenabled,
           trigger.tgfoid,
           trigger.tgisinternal
      INTO trigger_definition, trigger_enabled, trigger_function, trigger_is_internal
      FROM pg_catalog.pg_trigger trigger
      JOIN pg_catalog.pg_class relation ON relation.oid = trigger.tgrelid
      JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
     WHERE namespace.nspname = 'public'
       AND relation.relname = 'work_unit'
       AND trigger.tgname = 'work_unit_client_id_from_project';

    IF trigger_definition IS DISTINCT FROM
       'CREATE TRIGGER work_unit_client_id_from_project BEFORE INSERT OR UPDATE ON work_unit FOR EACH ROW EXECUTE FUNCTION set_work_unit_client_id_from_project()'
       OR trigger_enabled IS DISTINCT FROM 'O'
       OR trigger_function IS DISTINCT FROM pg_catalog.to_regprocedure('public.set_work_unit_client_id_from_project()')
       OR trigger_is_internal IS DISTINCT FROM false THEN
        RAISE EXCEPTION 'demo seed verification found an unexpected work unit client trigger';
    END IF;
END;
$$;

\ir catalog.sql

CREATE TEMP TABLE demo_seed_verify_catalog_context
ON COMMIT PRESERVE ROWS
AS TABLE demo_seed_catalog_context;

CREATE TEMP TABLE demo_seed_verify_user_catalog
ON COMMIT PRESERVE ROWS
AS TABLE demo_seed_user_catalog;

CREATE TEMP TABLE demo_seed_verify_client_catalog
ON COMMIT PRESERVE ROWS
AS TABLE demo_seed_client_catalog;

CREATE TEMP TABLE demo_seed_verify_project_catalog
ON COMMIT PRESERVE ROWS
AS TABLE demo_seed_project_catalog;

CREATE TEMP TABLE demo_seed_verify_project_cleanup_catalog
ON COMMIT PRESERVE ROWS
AS TABLE demo_seed_project_cleanup_catalog;

CREATE TEMP TABLE demo_seed_verify_project_user_catalog
ON COMMIT PRESERVE ROWS
AS TABLE demo_seed_project_user_catalog;

CREATE TEMP TABLE demo_seed_verify_generator_catalog
ON COMMIT PRESERVE ROWS
AS TABLE demo_seed_generator_catalog;

CREATE TEMP TABLE demo_seed_organization_context
(
    organization_id UUID NOT NULL PRIMARY KEY,
    organization_existed BOOLEAN NOT NULL,
    organization_was_archived BOOLEAN NOT NULL
) ON COMMIT PRESERVE ROWS;

DO
$$
DECLARE
    parameters demo_seed_parameters%ROWTYPE;
    deterministic_organization_id UUID;
    target_organization_id UUID;
    target_archived_at TIMESTAMPTZ;
    target_count INTEGER;
BEGIN
    SELECT * INTO STRICT parameters FROM demo_seed_parameters;
    SELECT organization_id INTO STRICT deterministic_organization_id FROM demo_seed_catalog_context;

    IF EXISTS (
        SELECT 1 FROM public.organization
        WHERE id = deterministic_organization_id
          AND slug <> parameters.organization_slug
    ) THEN
        RAISE EXCEPTION 'deterministic seed organization id is owned by another slug';
    END IF;

    SELECT pg_catalog.count(*)
      INTO target_count
      FROM public.organization
     WHERE slug = parameters.organization_slug;
    IF target_count = 1 THEN
        SELECT id, archived_at
          INTO STRICT target_organization_id, target_archived_at
          FROM public.organization
         WHERE slug = parameters.organization_slug;
    END IF;

    IF target_count <> 1 OR target_archived_at IS NOT NULL THEN
        RAISE EXCEPTION 'demo seed verification requires exactly one active target organization';
    END IF;
    IF parameters.target = 'staging' AND target_organization_id <> parameters.expected_organization_id THEN
        RAISE EXCEPTION 'staging verification organization fingerprint mismatch';
    END IF;

    INSERT INTO demo_seed_organization_context VALUES (target_organization_id, true, false);
END;
$$;

CREATE TEMP TABLE demo_seed_nonseed_active_member
ON COMMIT DROP
AS
SELECT membership.id AS membership_id,
       membership.user_id,
       membership.roles[1] AS role,
       users.is_super_admin
FROM demo_seed_organization_context context
JOIN public.organization_user membership ON membership.organization_id = context.organization_id
JOIN public."user" users ON users.id = membership.user_id
WHERE membership.archived_at IS NULL
  AND users.archived_at IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM demo_seed_user_catalog catalog WHERE catalog.user_id = membership.user_id
  );

\ir member-target.sql
\ir project-team-target.sql
\ir work-unit-target.sql

CREATE TEMP TABLE demo_seed_verify_target_member
ON COMMIT PRESERVE ROWS
AS TABLE demo_seed_target_member;

CREATE TEMP TABLE demo_seed_verify_target_work_unit
ON COMMIT PRESERVE ROWS
AS TABLE demo_seed_target_work_unit;

COMMIT;
ALTER TABLE demo_seed_verify_catalog_context RENAME TO demo_seed_catalog_context;
ALTER TABLE demo_seed_verify_user_catalog RENAME TO demo_seed_user_catalog;
ALTER TABLE demo_seed_verify_client_catalog RENAME TO demo_seed_client_catalog;
ALTER TABLE demo_seed_verify_project_catalog RENAME TO demo_seed_project_catalog;
ALTER TABLE demo_seed_verify_project_cleanup_catalog RENAME TO demo_seed_project_cleanup_catalog;
ALTER TABLE demo_seed_verify_project_user_catalog RENAME TO demo_seed_project_user_catalog;
ALTER TABLE demo_seed_verify_generator_catalog RENAME TO demo_seed_generator_catalog;
ALTER TABLE demo_seed_verify_target_member RENAME TO demo_seed_target_member;
ALTER TABLE demo_seed_verify_target_work_unit RENAME TO demo_seed_target_work_unit;
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '5min';
LOCK TABLE public.project IN EXCLUSIVE MODE;
\endif

DO
$$
DECLARE
    parameters demo_seed_parameters%ROWTYPE;
    target_organization_id UUID;
    cleanup_catalog_count INTEGER;
    present_cleanup_count INTEGER;
    exact_cleanup_count INTEGER;
BEGIN
    SELECT * INTO STRICT parameters FROM demo_seed_parameters;
    SELECT organization_id INTO STRICT target_organization_id FROM demo_seed_organization_context;

    IF EXISTS (
        SELECT 1
        FROM public.project AS project_row
        JOIN demo_seed_project_cleanup_catalog AS cleanup ON cleanup.project_id = project_row.id
        WHERE project_row.organization_id <> target_organization_id
    ) THEN
        RAISE EXCEPTION 'project cleanup id belongs to another organization';
    END IF;

    SELECT pg_catalog.count(*)
      INTO cleanup_catalog_count
      FROM demo_seed_project_cleanup_catalog;

    SELECT pg_catalog.count(project_row.id),
           pg_catalog.count(project_row.id) FILTER (
               WHERE project_row.name = cleanup.new_name
                 AND project_row.description IS NOT DISTINCT FROM cleanup.new_description
           )
      INTO present_cleanup_count, exact_cleanup_count
      FROM demo_seed_project_cleanup_catalog AS cleanup
      LEFT JOIN public.project AS project_row
        ON project_row.id = cleanup.project_id
       AND project_row.organization_id = target_organization_id;

    IF parameters.target = 'staging'
       AND (cleanup_catalog_count <> 12 OR present_cleanup_count <> 12) THEN
        RAISE EXCEPTION 'staging project cleanup catalog is incomplete';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_project_cleanup_catalog AS cleanup
        JOIN public.project AS project_row
          ON project_row.organization_id = target_organization_id
         AND project_row.name = cleanup.new_name
         AND project_row.id <> cleanup.project_id
    ) THEN
        RAISE EXCEPTION 'project cleanup target name is already in use';
    END IF;

    IF exact_cleanup_count <> present_cleanup_count THEN
        RAISE EXCEPTION 'project cleanup row outside the exact target state';
    END IF;
END;
$$;

DO
$$
DECLARE
    parameters demo_seed_parameters%ROWTYPE;
    target_organization_id UUID;
    target_organization_count INTEGER;
    active_membership_count INTEGER;
    unique_active_member_count INTEGER;
    owner_count INTEGER;
    manager_count INTEGER;
    employee_count INTEGER;
    external_count INTEGER;
    active_total_superadmin_count INTEGER;
    active_nonseed_superadmin_count INTEGER;
    active_seed_superadmin_count INTEGER;
    viewer_count INTEGER;
BEGIN
    SELECT * INTO STRICT parameters FROM demo_seed_parameters;
    SELECT organization_id INTO STRICT target_organization_id FROM demo_seed_organization_context;

    IF pg_catalog.current_database() <> parameters.expected_database_name
       OR (pg_catalog.pg_control_system()).system_identifier::TEXT <> parameters.expected_system_identifier
       OR pg_catalog.host(pg_catalog.inet_server_addr()) <> parameters.expected_server_address
       OR pg_catalog.inet_server_port() <> parameters.expected_server_port THEN
        RAISE EXCEPTION 'demo seed verification environment changed during execution';
    END IF;

    IF parameters.target = 'local' AND NOT parameters.sql_test_mode
       AND pg_catalog.host(pg_catalog.inet_server_addr()) NOT IN ('127.0.0.1', '::1') THEN
        RAISE EXCEPTION 'local demo seed verification requires a loopback PostgreSQL server';
    END IF;

    SELECT pg_catalog.count(*)
      INTO target_organization_count
      FROM public.organization
     WHERE id = target_organization_id
       AND slug = parameters.organization_slug
       AND archived_at IS NULL;
    IF target_organization_count <> 1 THEN
        RAISE EXCEPTION 'demo seed verification requires the exact active target organization';
    END IF;

    IF parameters.target = 'staging' AND target_organization_id <> parameters.expected_organization_id THEN
        RAISE EXCEPTION 'staging target organization changed during demo seed';
    END IF;

    SELECT pg_catalog.count(*),
           pg_catalog.count(DISTINCT membership.user_id),
           pg_catalog.count(*) FILTER (WHERE membership.roles = ARRAY['Owner']::TEXT[]),
           pg_catalog.count(*) FILTER (WHERE membership.roles = ARRAY['Manager']::TEXT[]),
           pg_catalog.count(*) FILTER (WHERE membership.roles = ARRAY['Employee']::TEXT[]),
           pg_catalog.count(*) FILTER (WHERE membership.roles = ARRAY['External']::TEXT[])
      INTO active_membership_count, unique_active_member_count,
           owner_count, manager_count, employee_count, external_count
      FROM public.organization_user membership
      JOIN public."user" users ON users.id = membership.user_id
     WHERE membership.organization_id = target_organization_id
       AND membership.archived_at IS NULL
       AND users.archived_at IS NULL;

    IF active_membership_count <> 30 OR unique_active_member_count <> 30 THEN
        RAISE EXCEPTION 'demo seed verification expected exactly thirty unique active members';
    END IF;
    IF owner_count <> 2 OR manager_count <> 2 OR employee_count <> 22 OR external_count <> 4 THEN
        RAISE EXCEPTION 'demo seed verification found an unexpected role distribution';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.organization_user membership
        WHERE membership.organization_id = target_organization_id
          AND membership.archived_at IS NULL
        GROUP BY membership.user_id
        HAVING pg_catalog.count(*) > 1
    ) THEN
        RAISE EXCEPTION 'demo seed verification found duplicate active membership rows';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.organization_user membership
        JOIN public."user" users ON users.id = membership.user_id
        JOIN demo_seed_user_catalog catalog ON catalog.user_id = membership.user_id
        WHERE membership.organization_id = target_organization_id
          AND membership.archived_at IS NULL
          AND users.archived_at IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'demo seed verification found an active membership to an archived seed user';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.organization_user membership
        JOIN public."user" users ON users.id = membership.user_id
        WHERE membership.organization_id = target_organization_id
          AND membership.archived_at IS NULL
          AND users.archived_at IS NULL
          AND (pg_catalog.cardinality(membership.roles) <> 1 OR membership.roles[1] NOT IN ('Owner', 'Manager', 'Employee', 'External'))
    ) THEN
        RAISE EXCEPTION 'demo seed verification found a membership without exactly one supported role';
    END IF;

    SELECT pg_catalog.count(*) FILTER (WHERE users.is_super_admin),
           pg_catalog.count(*) FILTER (WHERE users.is_super_admin AND catalog.user_id IS NULL),
           pg_catalog.count(*) FILTER (WHERE users.is_super_admin AND catalog.user_id IS NOT NULL)
      INTO active_total_superadmin_count, active_nonseed_superadmin_count, active_seed_superadmin_count
      FROM public.organization_user membership
      JOIN public."user" users ON users.id = membership.user_id
      LEFT JOIN demo_seed_user_catalog catalog ON catalog.user_id = users.id
     WHERE membership.organization_id = target_organization_id
       AND membership.archived_at IS NULL
       AND users.archived_at IS NULL;

    IF active_total_superadmin_count <> 2
       OR active_nonseed_superadmin_count > 2
       OR active_seed_superadmin_count <> 2 - active_nonseed_superadmin_count THEN
        RAISE EXCEPTION 'demo seed verification expected exactly two active target-organization superadmins';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_target_member target
        JOIN demo_seed_user_catalog catalog ON catalog.profile_order = target.profile_order
        JOIN public."user" users ON users.id = catalog.user_id
        WHERE users.is_super_admin IS DISTINCT FROM target.is_super_admin
           OR (target.is_super_admin AND target.role = 'External')
    ) THEN
        RAISE EXCEPTION 'demo seed verification found an unexpected seed superadmin assignment';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_target_member target
        JOIN demo_seed_user_catalog catalog ON catalog.profile_order = target.profile_order
        LEFT JOIN public.user_password password ON password.user_identity_id = catalog.identity_id
        WHERE password.user_identity_id IS NULL
           OR password.password IS DISTINCT FROM catalog.password_hash
    ) OR EXISTS (
        SELECT 1
        FROM demo_seed_user_catalog catalog
        JOIN public.user_password password ON password.user_identity_id = catalog.identity_id
        WHERE NOT EXISTS (
            SELECT 1 FROM demo_seed_target_member target WHERE target.identity_id = catalog.identity_id
        )
    ) THEN
        RAISE EXCEPTION 'demo seed verification found an unexpected managed seed password';
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.invitation invitation_row
        JOIN demo_seed_user_catalog catalog ON pg_catalog.lower(catalog.email) = pg_catalog.lower(invitation_row.identity_value)
    ) OR EXISTS (
        SELECT 1 FROM public.saml_request request
        JOIN demo_seed_user_catalog catalog ON catalog.user_id = request.user_id
    ) OR EXISTS (
        SELECT 1 FROM public.mail_history history
        JOIN demo_seed_user_catalog catalog ON catalog.email = history.email
    ) OR EXISTS (
        SELECT 1 FROM public.sendgrid_mail_event event
        JOIN demo_seed_user_catalog catalog ON catalog.email = event.email
    ) THEN
        RAISE EXCEPTION 'demo seed verification found invitations, SAML, or mail rows for seed users';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_user_catalog catalog
        JOIN public.user_identity identity
          ON identity.value = catalog.email
          OR (
              identity.type = 'MailAddress'
              AND pg_catalog.lower(identity.value) = pg_catalog.lower(catalog.email)
          )
        WHERE identity.id <> catalog.identity_id OR identity.user_id <> catalog.user_id
    ) THEN
        RAISE EXCEPTION 'case-insensitive seed MailAddress is owned by another relation';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_user_catalog catalog
        JOIN public."user" users ON users.id = catalog.user_id
        WHERE (SELECT pg_catalog.count(*) FROM public.user_identity identity WHERE identity.user_id = catalog.user_id) <> 1
           OR NOT EXISTS (
                SELECT 1
                FROM public.user_identity identity
                WHERE identity.id = catalog.identity_id
                  AND identity.user_id = catalog.user_id
                  AND identity.type = 'MailAddress'
                  AND identity.value = catalog.email
                  AND identity.main
                  AND identity.confirmed
                  AND identity.confirmation_code IS NULL
                  AND identity.device_id IS NULL
           )
    ) THEN
        RAISE EXCEPTION 'seed user must have exactly one catalog identity';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_user_catalog catalog
        JOIN public."user" users ON users.id = catalog.user_id
        WHERE users.archived_at IS NULL
          AND NOT EXISTS (
              SELECT 1
              FROM public.organization_user membership
              WHERE membership.id = catalog.membership_id
                AND membership.organization_id = target_organization_id
                AND membership.user_id = catalog.user_id
                AND membership.archived_at IS NULL
          )
    ) THEN
        RAISE EXCEPTION 'demo seed verification found an active non-target seed user';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_user_catalog catalog
        JOIN public."user" users ON users.id = catalog.user_id
        JOIN public.organization_user membership ON membership.user_id = catalog.user_id
        WHERE users.archived_at IS NOT NULL
          AND membership.archived_at IS NULL
    ) THEN
        RAISE EXCEPTION 'demo seed verification found an archived seed user with an active membership';
    END IF;

    IF parameters.target = 'staging' THEN
        SELECT pg_catalog.count(DISTINCT users.id)
          INTO viewer_count
          FROM public."user" users
          JOIN public.organization_user membership
            ON membership.user_id = users.id
           AND membership.organization_id = target_organization_id
           AND membership.archived_at IS NULL
          JOIN public.user_identity identity
            ON identity.user_id = users.id
           AND identity.type = 'MailAddress'
           AND identity.main
           AND identity.confirmed
           AND pg_catalog.lower(identity.value) = pg_catalog.lower(parameters.demo_viewer_email)
         WHERE users.archived_at IS NULL
           AND pg_catalog.cardinality(membership.roles) = 1
           AND (users.is_super_admin OR membership.roles[1] IN ('Owner', 'Manager'))
           AND NOT EXISTS (SELECT 1 FROM demo_seed_user_catalog catalog WHERE catalog.user_id = users.id);
        IF viewer_count <> 1 THEN
            RAISE EXCEPTION 'staging viewer changed during demo seed';
        END IF;
    END IF;
END;
$$;

DO
$$
DECLARE
    parameters demo_seed_parameters%ROWTYPE;
BEGIN
    SELECT * INTO STRICT parameters FROM demo_seed_parameters;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_target_work_unit target
        LEFT JOIN public.work_unit actual ON actual.id = target.work_unit_id
        WHERE actual.id IS NULL
           OR actual.organization_id IS DISTINCT FROM target.organization_id
           OR actual.user_id IS DISTINCT FROM target.user_id
           OR actual.project_id IS DISTINCT FROM target.project_id
           OR actual.client_id IS DISTINCT FROM target.client_id
           OR actual.date IS DISTINCT FROM target.work_date
           OR actual.duration IS DISTINCT FROM target.duration
           OR actual.entry_type IS DISTINCT FROM target.entry_type
           OR actual.description IS DISTINCT FROM target.description
           OR actual.task_id IS DISTINCT FROM target.task_id
           OR actual.state IS DISTINCT FROM target.state
           OR actual.created_at IS DISTINCT FROM target.created_at
           OR actual.archived_at IS DISTINCT FROM target.archived_at
           OR actual.modified_at IS NULL
           OR actual.modified_at < actual.created_at
           OR actual.modified_at > ((parameters.seed_today::TIMESTAMP + INTERVAL '12 hours') AT TIME ZONE 'UTC')
           OR (
                actual.modified_at IS DISTINCT FROM target.modified_at
                AND (actual.modified_at AT TIME ZONE 'UTC')::TIME <> TIME '12:00:00'
              )
    ) THEN
        RAISE EXCEPTION 'demo seed verification found a row outside the exact generated work-unit target';
    END IF;

    IF EXISTS (
        WITH owned AS (
            SELECT DISTINCT work_unit_row.id,
                   work_unit_row.state,
                   work_unit_row.archived_at
            FROM public.work_unit work_unit_row
            JOIN demo_seed_user_catalog user_catalog ON user_catalog.user_id = work_unit_row.user_id
            CROSS JOIN demo_seed_catalog_context context
            JOIN demo_seed_generator_catalog generator ON generator.generator_name = 'work-unit'
            CROSS JOIN LATERAL pg_catalog.generate_series(1, generator.max_slot) AS slot(logical_slot)
            WHERE work_unit_row.id = public.uuid_generate_v5(
                context.namespace_uuid,
                'work-unit:v' || generator.version::TEXT || ':' || user_catalog.logical_key || ':' ||
                work_unit_row.date::TEXT || ':' || slot.logical_slot::TEXT
            )
        )
        SELECT 1
        FROM owned
        LEFT JOIN demo_seed_target_work_unit target ON target.work_unit_id = owned.id
        WHERE target.work_unit_id IS NULL
          AND (owned.state <> 'Archived' OR owned.archived_at IS NULL)
    ) THEN
        RAISE EXCEPTION 'demo seed verification: retired seed-owned work unit is not archived';
    END IF;
END;
$$;

DO
$$
DECLARE
    parameters demo_seed_parameters%ROWTYPE;
    target_organization_id UUID;
    seed_owner_id UUID;
    seed_client_count INTEGER;
    internal_client_count INTEGER;
    external_client_count INTEGER;
    active_client_count INTEGER;
    archived_client_count INTEGER;
    seed_project_count INTEGER;
    active_project_count INTEGER;
    archived_project_count INTEGER;
BEGIN
    SELECT * INTO STRICT parameters FROM demo_seed_parameters;
    SELECT organization_id INTO STRICT target_organization_id FROM demo_seed_organization_context;
    SELECT user_id INTO STRICT seed_owner_id FROM demo_seed_user_catalog WHERE profile_order = 1;

    SELECT pg_catalog.count(client_row.id),
           pg_catalog.count(client_row.id) FILTER (WHERE client_row.type = 'Internal'),
           pg_catalog.count(client_row.id) FILTER (WHERE client_row.type = 'External'),
           pg_catalog.count(client_row.id) FILTER (WHERE client_row.archived_at IS NULL),
           pg_catalog.count(client_row.id) FILTER (WHERE client_row.archived_at IS NOT NULL)
      INTO seed_client_count, internal_client_count, external_client_count,
           active_client_count, archived_client_count
      FROM demo_seed_client_catalog catalog
      LEFT JOIN public.client client_row ON client_row.id = catalog.client_id
     WHERE catalog.catalog_state = 'active';

    IF seed_client_count <> 10
       OR internal_client_count <> 2
       OR external_client_count <> 8
       OR active_client_count <> 8
       OR archived_client_count <> 2 THEN
        RAISE EXCEPTION 'demo seed verification found unexpected seed client counts';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_client_catalog catalog
        LEFT JOIN public.client client_row ON client_row.id = catalog.client_id
        WHERE catalog.catalog_state = 'active'
          AND (
              client_row.id IS NULL
              OR client_row.name IS DISTINCT FROM catalog.name
              OR client_row.color IS DISTINCT FROM catalog.color
              OR client_row.organization_id IS DISTINCT FROM target_organization_id
              OR client_row.type IS DISTINCT FROM catalog.client_type
              OR client_row.texture IS DISTINCT FROM catalog.texture
              OR client_row.logo IS DISTINCT FROM catalog.logo
              OR client_row.created_by IS DISTINCT FROM seed_owner_id
              OR client_row.modified_by IS DISTINCT FROM seed_owner_id
              OR (catalog.status = 'active' AND (client_row.archived_at IS NOT NULL OR client_row.archived_by IS NOT NULL))
              OR (catalog.status = 'archived' AND (
                  client_row.archived_at IS DISTINCT FROM ((parameters.seed_today - 30)::TIMESTAMP AT TIME ZONE 'UTC')
                  OR client_row.archived_by IS DISTINCT FROM seed_owner_id
              ))
          )
    ) THEN
        RAISE EXCEPTION 'demo seed verification found a client outside its catalog definition';
    END IF;

    SELECT pg_catalog.count(project_row.id),
           pg_catalog.count(project_row.id) FILTER (WHERE project_row.archived_at IS NULL),
           pg_catalog.count(project_row.id) FILTER (WHERE project_row.archived_at IS NOT NULL)
      INTO seed_project_count, active_project_count, archived_project_count
      FROM demo_seed_project_catalog catalog
      LEFT JOIN public.project project_row ON project_row.id = catalog.project_id
     WHERE catalog.catalog_state = 'active';

    IF seed_project_count <> 16 OR active_project_count <> 13 OR archived_project_count <> 3 THEN
        RAISE EXCEPTION 'demo seed verification found unexpected seed project counts';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_project_catalog catalog
        JOIN demo_seed_client_catalog client_catalog ON client_catalog.client_id = catalog.client_id
        LEFT JOIN public.project project_row ON project_row.id = catalog.project_id
        LEFT JOIN public.client client_row ON client_row.id = project_row.client_id
        WHERE catalog.catalog_state = 'active'
          AND (
              project_row.id IS NULL
              OR project_row.name IS DISTINCT FROM catalog.name
              OR project_row.description IS DISTINCT FROM catalog.description
              OR project_row.organization_id IS DISTINCT FROM target_organization_id
              OR project_row.client_id IS DISTINCT FROM catalog.client_id
              OR client_row.organization_id IS DISTINCT FROM target_organization_id
              OR project_row.created_by IS DISTINCT FROM seed_owner_id
              OR project_row.modified_by IS DISTINCT FROM seed_owner_id
              OR (catalog.status = 'active' AND (
                  project_row.archived_at IS NOT NULL
                  OR project_row.archived_by IS NOT NULL
                  OR client_catalog.status <> 'active'
                  OR client_row.archived_at IS NOT NULL
              ))
              OR (catalog.status = 'archived' AND (
                  project_row.archived_at IS DISTINCT FROM ((parameters.seed_today - 30)::TIMESTAMP AT TIME ZONE 'UTC')
                  OR project_row.archived_by IS DISTINCT FROM seed_owner_id
              ))
          )
    ) THEN
        RAISE EXCEPTION 'demo seed verification found a project outside its catalog definition or organization';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.project_user project_user_row
        JOIN demo_seed_project_catalog project_catalog
          ON project_catalog.project_id = project_user_row.project_id
        WHERE project_user_row.archived_at IS NULL
          AND NOT EXISTS (
              SELECT 1
              FROM demo_seed_project_user_catalog membership_catalog
              WHERE membership_catalog.project_user_id = project_user_row.id
                AND membership_catalog.project_id = project_user_row.project_id
                AND membership_catalog.user_id = project_user_row.user_id
          )
    ) THEN
        RAISE EXCEPTION 'active noncatalog project assignment targets a catalog seed project';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_project_catalog project_catalog
        LEFT JOIN public.project_user project_user_row
          ON project_user_row.project_id = project_catalog.project_id
         AND project_user_row.archived_at IS NULL
        WHERE project_catalog.status = 'active'
        GROUP BY project_catalog.project_id, project_catalog.team_size
        HAVING pg_catalog.count(project_user_row.id) <> project_catalog.team_size
            OR pg_catalog.count(project_user_row.id) FILTER (WHERE project_user_row.role = 'Owner') < 1
    ) THEN
        RAISE EXCEPTION 'demo seed verification found an invalid active seed project team size or Owner';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.project_user project_user_row
        JOIN demo_seed_project_catalog project_catalog ON project_catalog.project_id = project_user_row.project_id
        JOIN demo_seed_client_catalog client_catalog ON client_catalog.client_id = project_catalog.client_id
        JOIN public."user" users ON users.id = project_user_row.user_id
        LEFT JOIN public.organization_user organization_membership
          ON organization_membership.organization_id = target_organization_id
         AND organization_membership.user_id = project_user_row.user_id
         AND organization_membership.archived_at IS NULL
        WHERE project_user_row.archived_at IS NULL
          AND (
              project_catalog.status <> 'active'
              OR client_catalog.status <> 'active'
              OR users.archived_at IS NOT NULL
              OR organization_membership.id IS NULL
              OR project_user_row.role NOT IN ('Owner', 'Member')
              OR (project_user_row.role = 'Owner'
                  AND organization_membership.roles[1] NOT IN ('Manager', 'Employee'))
              OR (organization_membership.roles = ARRAY['External']::TEXT[]
                  AND client_catalog.client_type <> 'External')
          )
    ) THEN
        RAISE EXCEPTION 'demo seed verification found an invalid active seed project membership';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_user_catalog user_catalog
        JOIN public."user" users ON users.id = user_catalog.user_id AND users.archived_at IS NULL
        JOIN public.organization_user organization_membership
          ON organization_membership.organization_id = target_organization_id
         AND organization_membership.user_id = users.id
         AND organization_membership.archived_at IS NULL
        WHERE NOT EXISTS (
            SELECT 1
            FROM public.project_user project_user_row
            JOIN demo_seed_project_user_catalog membership_catalog
              ON membership_catalog.project_user_id = project_user_row.id
             AND membership_catalog.project_id = project_user_row.project_id
             AND membership_catalog.user_id = project_user_row.user_id
            JOIN demo_seed_project_catalog project_catalog
              ON project_catalog.project_id = project_user_row.project_id
             AND project_catalog.status = 'active'
            WHERE project_user_row.user_id = users.id
              AND project_user_row.archived_at IS NULL
        )
    ) THEN
        RAISE EXCEPTION 'demo seed verification found an active seed user without a primary project team';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.project_user project_user_row
        JOIN demo_seed_project_catalog project_catalog
          ON project_catalog.project_id = project_user_row.project_id
         AND project_catalog.status = 'active'
        JOIN public."user" users
          ON users.id = project_user_row.user_id
         AND users.archived_at IS NULL
        JOIN public.organization_user organization_membership
          ON organization_membership.organization_id = target_organization_id
         AND organization_membership.user_id = project_user_row.user_id
         AND organization_membership.archived_at IS NULL
         AND organization_membership.roles = ARRAY['External']::TEXT[]
        WHERE project_user_row.archived_at IS NULL
        GROUP BY project_user_row.project_id
        HAVING pg_catalog.count(*) > 2
    ) THEN
        RAISE EXCEPTION 'demo seed verification found more than two active External members in one project';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.project_user project_user_row
        JOIN public.organization_user organization_membership
          ON organization_membership.organization_id = target_organization_id
         AND organization_membership.user_id = project_user_row.user_id
         AND organization_membership.archived_at IS NULL
        JOIN demo_seed_project_catalog project_catalog ON project_catalog.project_id = project_user_row.project_id
        JOIN demo_seed_client_catalog client_catalog ON client_catalog.client_id = project_catalog.client_id
        WHERE project_user_row.archived_at IS NULL
          AND organization_membership.roles = ARRAY['External']::TEXT[]
        GROUP BY project_user_row.user_id
        HAVING pg_catalog.count(*) > 2
            OR pg_catalog.bool_or(project_catalog.status <> 'active')
            OR pg_catalog.bool_or(client_catalog.status <> 'active')
            OR pg_catalog.bool_or(client_catalog.client_type <> 'External')
    ) THEN
        RAISE EXCEPTION 'demo seed verification found an External outside its allowed active projects';
    END IF;

    IF EXISTS (
        WITH active_seed_member AS (
            SELECT user_catalog.user_id,
                   organization_membership.roles[1] AS organization_role,
                   pg_catalog.row_number() OVER (
                       ORDER BY user_catalog.profile_order
                   )::INTEGER AS member_order
            FROM demo_seed_user_catalog user_catalog
            JOIN public."user" users
              ON users.id = user_catalog.user_id
             AND users.archived_at IS NULL
            JOIN public.organization_user organization_membership
              ON organization_membership.organization_id = target_organization_id
             AND organization_membership.user_id = users.id
             AND organization_membership.archived_at IS NULL
        ), active_project AS (
            SELECT project_catalog.project_id,
                   pg_catalog.row_number() OVER (
                       ORDER BY project_catalog.project_order
                   )::INTEGER AS project_order,
                   pg_catalog.count(*) OVER ()::INTEGER AS project_count
            FROM demo_seed_project_catalog project_catalog
            WHERE project_catalog.catalog_state = 'active'
              AND project_catalog.status = 'active'
        )
        SELECT 1
        FROM public.project_user project_user_row
        JOIN demo_seed_project_user_catalog membership_catalog
          ON membership_catalog.project_user_id = project_user_row.id
         AND membership_catalog.project_id = project_user_row.project_id
         AND membership_catalog.user_id = project_user_row.user_id
        JOIN active_seed_member member ON member.user_id = project_user_row.user_id
        JOIN active_project project ON project.project_id = project_user_row.project_id
        WHERE project_user_row.archived_at IS NULL
          AND project_user_row.role = 'Member'
          AND member.organization_role IN ('Owner', 'Manager')
          AND project.project_order <> ((member.member_order - 1) % project.project_count) + 1
    ) THEN
        RAISE EXCEPTION 'demo seed verification found a non-primary Owner or Manager project Member';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.project_user project_user_row
        JOIN demo_seed_project_user_catalog membership_catalog
          ON membership_catalog.project_user_id = project_user_row.id
         AND membership_catalog.project_id = project_user_row.project_id
         AND membership_catalog.user_id = project_user_row.user_id
        JOIN demo_seed_project_catalog project_catalog ON project_catalog.project_id = project_user_row.project_id
        WHERE project_catalog.status = 'archived'
          AND (
              project_user_row.archived_at IS NULL
              OR project_user_row.archived_at < ((parameters.seed_today - 30)::TIMESTAMP AT TIME ZONE 'UTC')
          )
    ) THEN
        RAISE EXCEPTION 'demo seed verification found a membership inconsistent with an archived project';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_project_catalog project_catalog
        LEFT JOIN public.project_user project_user_row
          ON project_user_row.project_id = project_catalog.project_id
        LEFT JOIN demo_seed_project_user_catalog membership_catalog
          ON membership_catalog.project_user_id = project_user_row.id
         AND membership_catalog.project_id = project_user_row.project_id
         AND membership_catalog.user_id = project_user_row.user_id
        WHERE project_catalog.status = 'archived'
        GROUP BY project_catalog.project_id, project_catalog.team_size
        HAVING pg_catalog.count(project_user_row.id) FILTER (
                   WHERE membership_catalog.project_user_id IS NOT NULL
                     AND project_user_row.archived_at IS NOT NULL
               ) < project_catalog.team_size
    ) THEN
        RAISE EXCEPTION 'demo seed verification found an incomplete archived seed project team';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.project_user project_user_row
        JOIN demo_seed_project_user_catalog membership_catalog
          ON membership_catalog.project_user_id = project_user_row.id
        WHERE project_user_row.project_id <> membership_catalog.project_id
           OR project_user_row.user_id <> membership_catalog.user_id
    ) THEN
        RAISE EXCEPTION 'demo seed verification found a project membership ownership mismatch';
    END IF;
END;
$$;

DO
$$
DECLARE
    parameters demo_seed_parameters%ROWTYPE;
    target_organization_id UUID;
    active_worklog_count INTEGER;
    active_note_count INTEGER;
    current_history_count INTEGER;
    active_author_count INTEGER;
    expected_active_seed_author_count INTEGER;
    active_project_count INTEGER;
    active_client_count INTEGER;
    active_month_count INTEGER;
    active_week_count INTEGER;
    first_active_date DATE;
    last_active_date DATE;
BEGIN
    SELECT * INTO STRICT parameters FROM demo_seed_parameters;
    SELECT organization_id INTO STRICT target_organization_id FROM demo_seed_organization_context;

    WITH owned AS (
        SELECT DISTINCT ON (work_unit_row.id)
               work_unit_row.*,
               user_catalog.logical_key AS user_key,
               generator.version AS generator_version,
               slot.logical_slot
        FROM public.work_unit work_unit_row
        JOIN demo_seed_user_catalog user_catalog ON user_catalog.user_id = work_unit_row.user_id
        CROSS JOIN demo_seed_catalog_context context
        JOIN demo_seed_generator_catalog generator
          ON generator.generator_name = 'work-unit'
        CROSS JOIN LATERAL pg_catalog.generate_series(1, generator.max_slot) AS slot(logical_slot)
        WHERE work_unit_row.id = public.uuid_generate_v5(
            context.namespace_uuid,
            'work-unit:v' || generator.version::TEXT || ':' || user_catalog.logical_key || ':' ||
            work_unit_row.date::TEXT || ':' || slot.logical_slot::TEXT
        )
        ORDER BY work_unit_row.id, generator.version, slot.logical_slot
    ), active_owned AS (
        SELECT * FROM owned WHERE state = 'Active' AND archived_at IS NULL
    ), archived_project AS (
        SELECT project_catalog.project_id,
               project_catalog.client_id,
               project_catalog.name AS project_name,
               project_catalog.task_prefix,
               pg_catalog.row_number() OVER (ORDER BY project_catalog.project_order)::INTEGER AS archived_project_order
        FROM demo_seed_project_catalog project_catalog
        WHERE project_catalog.catalog_state = 'active'
          AND project_catalog.status = 'archived'
    ), history_number AS (
        SELECT source_number,
               (source_number % 3) + 1 AS archived_project_order
        FROM pg_catalog.generate_series(0, 59) AS generated(source_number)
    ), expected_history AS (
        SELECT number.source_number,
               project.project_id,
               project.client_id,
               (parameters.seed_today - 45 + (number.source_number % 15))::DATE AS work_date,
               pg_catalog.make_interval(mins => 15 * (1 + (((1200 + number.source_number) * 7) % 16))) AS duration,
               'Historical delivery wrap-up — ' || project.project_name AS description,
               project.task_prefix || '-' || (900 + (number.source_number % 73))::TEXT AS task_id
        FROM history_number number
        JOIN archived_project project ON project.archived_project_order = number.archived_project_order
    ), current_history AS (
        SELECT expected.*
        FROM expected_history expected
        WHERE EXISTS (
            SELECT 1
            FROM owned
            WHERE owned.project_id = expected.project_id
              AND owned.client_id = expected.client_id
              AND owned.date = expected.work_date
              AND owned.duration = expected.duration
              AND owned.description = expected.description
              AND owned.task_id = expected.task_id
              AND owned.state = 'Archived'
              AND owned.archived_at IS NOT NULL
              AND owned.entry_type = 'Worklog'
        )
    )
    SELECT pg_catalog.count(*) FILTER (WHERE entry_type = 'Worklog'),
           pg_catalog.count(*) FILTER (WHERE entry_type = 'Note'),
           (SELECT pg_catalog.count(*) FROM current_history),
           pg_catalog.count(DISTINCT user_id),
           pg_catalog.count(DISTINCT project_id),
           pg_catalog.count(DISTINCT client_id),
           pg_catalog.count(DISTINCT pg_catalog.date_trunc('month', date)),
           pg_catalog.count(DISTINCT ((date - (parameters.seed_today - 84)) / 7)),
           pg_catalog.min(date),
           pg_catalog.max(date)
      INTO active_worklog_count,
           active_note_count,
           current_history_count,
           active_author_count,
           active_project_count,
           active_client_count,
           active_month_count,
           active_week_count,
           first_active_date,
           last_active_date
      FROM active_owned;

    IF active_worklog_count <> 1200 OR active_note_count <> 100 OR current_history_count <> 60 THEN
        RAISE EXCEPTION 'demo seed verification found unexpected Worklog, Note, or historical counts (Worklog=%, Note=%, history=%)',
            active_worklog_count, active_note_count, current_history_count;
    END IF;

    SELECT pg_catalog.count(*)
      INTO expected_active_seed_author_count
      FROM demo_seed_user_catalog user_catalog
      JOIN public."user" users
        ON users.id = user_catalog.user_id
       AND users.archived_at IS NULL
      JOIN public.organization_user membership
        ON membership.organization_id = target_organization_id
       AND membership.user_id = users.id
       AND membership.archived_at IS NULL;

    IF expected_active_seed_author_count < 22
       OR active_author_count <> expected_active_seed_author_count
       OR active_project_count <> 13
       OR active_client_count <> 8
       OR active_month_count < 2
       OR active_week_count <> 12
       OR first_active_date < parameters.seed_today - 84
       OR last_active_date >= parameters.seed_today THEN
        RAISE EXCEPTION 'demo seed verification found incomplete report coverage or invalid date boundaries';
    END IF;

    IF EXISTS (
        WITH calendar AS (
            SELECT day::DATE AS reporting_date,
                   EXTRACT(isodow FROM day)::INTEGER AS iso_day,
                   pg_catalog.row_number() OVER (
                       PARTITION BY (EXTRACT(isodow FROM day)::INTEGER IN (6, 7))
                       ORDER BY day
                   )::INTEGER AS day_kind_order
            FROM pg_catalog.generate_series(
                parameters.seed_today - 84,
                parameters.seed_today - 1,
                INTERVAL '1 day'
            ) AS generated(day)
        ), reporting_date AS (
            SELECT calendar.reporting_date
            FROM calendar
            WHERE calendar.iso_day BETWEEN 1 AND 5
               OR (calendar.iso_day IN (6, 7) AND calendar.day_kind_order % 4 = 0)
        ), owned AS (
            SELECT DISTINCT ON (work_unit_row.id)
                   work_unit_row.*,
                   user_catalog.logical_key,
                   slot.logical_slot
            FROM public.work_unit work_unit_row
            JOIN demo_seed_user_catalog user_catalog ON user_catalog.user_id = work_unit_row.user_id
            CROSS JOIN demo_seed_catalog_context context
            JOIN demo_seed_generator_catalog generator ON generator.generator_name = 'work-unit'
            CROSS JOIN LATERAL pg_catalog.generate_series(1, generator.max_slot) AS slot(logical_slot)
            WHERE work_unit_row.id = public.uuid_generate_v5(
                context.namespace_uuid,
                'work-unit:v' || generator.version::TEXT || ':' || user_catalog.logical_key || ':' ||
                work_unit_row.date::TEXT || ':' || slot.logical_slot::TEXT
            )
            ORDER BY work_unit_row.id, generator.version, slot.logical_slot
        )
        SELECT 1
        FROM owned work_unit_row
        JOIN public.project project_row ON project_row.id = work_unit_row.project_id
        JOIN public.client client_row ON client_row.id = work_unit_row.client_id
        JOIN public."user" users ON users.id = work_unit_row.user_id
        LEFT JOIN public.organization_user organization_membership
          ON organization_membership.organization_id = target_organization_id
         AND organization_membership.user_id = work_unit_row.user_id
         AND organization_membership.archived_at IS NULL
        LEFT JOIN public.project_user project_membership
          ON project_membership.project_id = work_unit_row.project_id
         AND project_membership.user_id = work_unit_row.user_id
         AND project_membership.archived_at IS NULL
        LEFT JOIN demo_seed_project_catalog project_catalog ON project_catalog.project_id = work_unit_row.project_id
        WHERE work_unit_row.state = 'Active'
          AND work_unit_row.archived_at IS NULL
          AND (
              work_unit_row.organization_id <> target_organization_id
              OR project_row.organization_id <> target_organization_id
              OR client_row.organization_id <> target_organization_id
              OR project_row.client_id <> work_unit_row.client_id
              OR project_row.archived_at IS NOT NULL
              OR client_row.archived_at IS NOT NULL
              OR users.archived_at IS NOT NULL
              OR organization_membership.id IS NULL
              OR project_membership.id IS NULL
              OR project_catalog.status <> 'active'
              OR work_unit_row.date < parameters.seed_today - 84
              OR work_unit_row.date >= parameters.seed_today
              OR NOT EXISTS (
                    SELECT 1 FROM reporting_date allowed_date
                    WHERE allowed_date.reporting_date = work_unit_row.date
                 )
              OR (work_unit_row.entry_type = 'Worklog' AND (
                    work_unit_row.duration < INTERVAL '15 minutes'
                    OR work_unit_row.duration > INTERVAL '4 hours'
                    OR pg_catalog.mod(EXTRACT(epoch FROM work_unit_row.duration)::INTEGER, 900) <> 0
                    OR work_unit_row.task_id IS NULL
                    OR work_unit_row.task_id !~ ('^' || project_catalog.task_prefix || '-[0-9]+$')
                    OR work_unit_row.description !~ '^(Discovery workshop|Implementation and pairing|Code review|Regression testing|Data analysis|Client sync|Release preparation|Architecture notes|Accessibility review|Performance improvements) — '
                 ))
              OR (work_unit_row.entry_type = 'Note' AND (
                    work_unit_row.duration <> INTERVAL '0 seconds'
                    OR work_unit_row.task_id IS NOT NULL
                    OR work_unit_row.description !~ '^(Decyzja zakresowa|Blocker integracji|Planowana nieobecność|Client feedback|Ryzyko release|Discovery follow-up)'
                 ))
              OR work_unit_row.entry_type NOT IN ('Worklog', 'Note')
          )
    ) THEN
        RAISE EXCEPTION 'demo seed verification found invalid active work-unit content';
    END IF;

    IF EXISTS (
        WITH owned AS (
            SELECT DISTINCT ON (work_unit_row.id)
                   work_unit_row.*,
                   user_catalog.logical_key,
                   slot.logical_slot
            FROM public.work_unit work_unit_row
            JOIN demo_seed_user_catalog user_catalog ON user_catalog.user_id = work_unit_row.user_id
            CROSS JOIN demo_seed_catalog_context context
            JOIN demo_seed_generator_catalog generator ON generator.generator_name = 'work-unit'
            CROSS JOIN LATERAL pg_catalog.generate_series(1, generator.max_slot) AS slot(logical_slot)
            WHERE work_unit_row.id = public.uuid_generate_v5(
                context.namespace_uuid,
                'work-unit:v' || generator.version::TEXT || ':' || user_catalog.logical_key || ':' ||
                work_unit_row.date::TEXT || ':' || slot.logical_slot::TEXT
            )
            ORDER BY work_unit_row.id, generator.version, slot.logical_slot
        )
        SELECT 1
        FROM owned
        WHERE (state = 'Active') <> (archived_at IS NULL)
           OR (state = 'Active' AND date NOT BETWEEN parameters.seed_today - 84 AND parameters.seed_today - 1)
           OR logical_slot > 64
    ) THEN
        RAISE EXCEPTION 'demo seed verification found invalid work-unit lifecycle or ownership slot';
    END IF;

    IF EXISTS (
        WITH owned AS (
            SELECT DISTINCT ON (work_unit_row.id) work_unit_row.*
            FROM public.work_unit work_unit_row
            JOIN demo_seed_user_catalog user_catalog ON user_catalog.user_id = work_unit_row.user_id
            CROSS JOIN demo_seed_catalog_context context
            JOIN demo_seed_generator_catalog generator ON generator.generator_name = 'work-unit'
            CROSS JOIN LATERAL pg_catalog.generate_series(1, generator.max_slot) AS slot(logical_slot)
            WHERE work_unit_row.id = public.uuid_generate_v5(
                context.namespace_uuid,
                'work-unit:v' || generator.version::TEXT || ':' || user_catalog.logical_key || ':' ||
                work_unit_row.date::TEXT || ':' || slot.logical_slot::TEXT
            )
            ORDER BY work_unit_row.id
        )
        SELECT 1
        FROM owned
        WHERE state = 'Active' AND archived_at IS NULL AND entry_type = 'Worklog'
        GROUP BY user_id, date
        HAVING pg_catalog.sum(duration) > INTERVAL '12 hours'
    ) THEN
        RAISE EXCEPTION 'demo seed verification found excessive daily Worklog duration';
    END IF;

    IF EXISTS (
        WITH owned AS (
            SELECT DISTINCT ON (work_unit_row.id) work_unit_row.*
            FROM public.work_unit work_unit_row
            JOIN demo_seed_user_catalog user_catalog ON user_catalog.user_id = work_unit_row.user_id
            CROSS JOIN demo_seed_catalog_context context
            JOIN demo_seed_generator_catalog generator ON generator.generator_name = 'work-unit'
            CROSS JOIN LATERAL pg_catalog.generate_series(1, generator.max_slot) AS slot(logical_slot)
            WHERE work_unit_row.id = public.uuid_generate_v5(
                context.namespace_uuid,
                'work-unit:v' || generator.version::TEXT || ':' || user_catalog.logical_key || ':' ||
                work_unit_row.date::TEXT || ':' || slot.logical_slot::TEXT
            )
            ORDER BY work_unit_row.id
        )
        SELECT 1
        FROM owned history
        JOIN public.project project_row ON project_row.id = history.project_id
        WHERE history.state = 'Archived'
          AND history.archived_at IS NOT NULL
          AND project_row.archived_at IS NOT NULL
          AND history.date BETWEEN parameters.seed_today - 45 AND parameters.seed_today - 31
          AND (
              history.entry_type <> 'Worklog'
              OR history.duration < INTERVAL '15 minutes'
              OR history.duration > INTERVAL '4 hours'
              OR pg_catalog.mod(EXTRACT(epoch FROM history.duration)::INTEGER, 900) <> 0
              OR history.date >= project_row.archived_at::DATE
              OR history.client_id <> project_row.client_id
              OR history.description !~ '^Historical delivery wrap-up — '
          )
    ) THEN
        RAISE EXCEPTION 'demo seed verification found invalid archived historical Worklog';
    END IF;

    IF EXISTS (
        WITH owned_id AS (
            SELECT DISTINCT work_unit_row.id
            FROM public.work_unit work_unit_row
            JOIN demo_seed_user_catalog user_catalog ON user_catalog.user_id = work_unit_row.user_id
            CROSS JOIN demo_seed_catalog_context context
            JOIN demo_seed_generator_catalog generator ON generator.generator_name = 'work-unit'
            CROSS JOIN LATERAL pg_catalog.generate_series(1, generator.max_slot) AS slot(logical_slot)
            WHERE work_unit_row.id = public.uuid_generate_v5(
                context.namespace_uuid,
                'work-unit:v' || generator.version::TEXT || ':' || user_catalog.logical_key || ':' ||
                work_unit_row.date::TEXT || ':' || slot.logical_slot::TEXT
            )
        )
        SELECT 1
        FROM public.work_unit_idempotency claim
        WHERE claim.target_work_unit_id IN (SELECT id FROM owned_id)
           OR claim.result_work_unit_id IN (SELECT id FROM owned_id)
    ) THEN
        RAISE EXCEPTION 'demo seed verification found an idempotency claim for a seed-owned work unit';
    END IF;

    IF NOT EXISTS (
        WITH owned AS (
            SELECT DISTINCT ON (work_unit_row.id) work_unit_row.*
            FROM public.work_unit work_unit_row
            JOIN demo_seed_user_catalog user_catalog ON user_catalog.user_id = work_unit_row.user_id
            CROSS JOIN demo_seed_catalog_context context
            JOIN demo_seed_generator_catalog generator ON generator.generator_name = 'work-unit'
            CROSS JOIN LATERAL pg_catalog.generate_series(1, generator.max_slot) AS slot(logical_slot)
            WHERE work_unit_row.id = public.uuid_generate_v5(
                context.namespace_uuid,
                'work-unit:v' || generator.version::TEXT || ':' || user_catalog.logical_key || ':' ||
                work_unit_row.date::TEXT || ':' || slot.logical_slot::TEXT
            )
            ORDER BY work_unit_row.id
        ), active_owned AS (
            SELECT * FROM owned WHERE state = 'Active' AND archived_at IS NULL
        )
        SELECT 1
        WHERE (SELECT pg_catalog.count(*) FROM active_owned) = 1300
          AND (SELECT pg_catalog.count(*) FROM active_owned
               WHERE date >= pg_catalog.date_trunc('month', parameters.seed_today)::DATE) > 0
          AND (SELECT pg_catalog.count(*) FROM active_owned
               WHERE date >= (pg_catalog.date_trunc('month', parameters.seed_today) - INTERVAL '1 month')::DATE
                 AND date < pg_catalog.date_trunc('month', parameters.seed_today)::DATE) > 0
          AND (SELECT pg_catalog.count(DISTINCT entry_type) FROM active_owned) = 2
          AND (SELECT pg_catalog.count(DISTINCT date) FROM active_owned) >= 30
          AND (SELECT pg_catalog.count(*) FROM active_owned WHERE EXTRACT(isodow FROM date) IN (6, 7)) > 0
          AND (SELECT pg_catalog.count(*) FROM active_owned WHERE EXTRACT(isodow FROM date) BETWEEN 1 AND 5) > 0
          AND (SELECT pg_catalog.count(*) FROM active_owned work_unit_row
               JOIN public.project project_row ON project_row.id = work_unit_row.project_id
               JOIN public.client client_row ON client_row.id = work_unit_row.client_id
               JOIN public."user" users ON users.id = work_unit_row.user_id) = 1300
    ) THEN
        RAISE EXCEPTION 'demo seed verification report, calendar, pivot, or export probe is empty';
    END IF;
END;
$$;

SELECT context.organization_id,
       parameters.seed_today,
       pg_catalog.count(DISTINCT membership.user_id) AS active_member_count,
       pg_catalog.count(*) FILTER (WHERE membership.roles = ARRAY['Owner']::TEXT[]) AS owner_count,
       pg_catalog.count(*) FILTER (WHERE membership.roles = ARRAY['Manager']::TEXT[]) AS manager_count,
       pg_catalog.count(*) FILTER (WHERE membership.roles = ARRAY['Employee']::TEXT[]) AS employee_count,
       pg_catalog.count(*) FILTER (WHERE membership.roles = ARRAY['External']::TEXT[]) AS external_count,
       pg_catalog.count(*) FILTER (WHERE catalog.user_id IS NOT NULL) AS active_seed_member_count,
       pg_catalog.count(*) FILTER (WHERE catalog.user_id IS NOT NULL AND users.is_super_admin) AS active_seed_superadmin_count,
       (SELECT pg_catalog.count(*) FROM demo_seed_client_catalog client_catalog
         JOIN public.client client_row ON client_row.id = client_catalog.client_id
        WHERE client_catalog.catalog_state = 'active') AS seed_client_count,
       (SELECT pg_catalog.count(*) FROM demo_seed_project_catalog project_catalog
         JOIN public.project project_row ON project_row.id = project_catalog.project_id
        WHERE project_catalog.catalog_state = 'active') AS seed_project_count,
       (SELECT pg_catalog.count(*)
          FROM demo_seed_project_cleanup_catalog cleanup
          JOIN public.project project_row
            ON project_row.id = cleanup.project_id
           AND project_row.organization_id = context.organization_id
         WHERE project_row.name = cleanup.new_name
           AND project_row.description IS NOT DISTINCT FROM cleanup.new_description) AS cleaned_project_count,
       (SELECT pg_catalog.count(*) FROM public.project_user project_user_row
         JOIN demo_seed_project_user_catalog membership_catalog
           ON membership_catalog.project_user_id = project_user_row.id
          AND membership_catalog.project_id = project_user_row.project_id
          AND membership_catalog.user_id = project_user_row.user_id
        WHERE project_user_row.archived_at IS NULL) AS active_seed_project_membership_count,
       (SELECT pg_catalog.count(*) FROM public.work_unit work_unit_row
         JOIN demo_seed_user_catalog user_catalog ON user_catalog.user_id = work_unit_row.user_id
         CROSS JOIN demo_seed_catalog_context catalog_context
         JOIN demo_seed_generator_catalog generator ON generator.generator_name = 'work-unit'
         CROSS JOIN LATERAL pg_catalog.generate_series(1, generator.max_slot) AS slot(logical_slot)
        WHERE work_unit_row.state = 'Active'
          AND work_unit_row.archived_at IS NULL
          AND work_unit_row.entry_type = 'Worklog'
          AND work_unit_row.id = public.uuid_generate_v5(
              catalog_context.namespace_uuid,
              'work-unit:v' || generator.version::TEXT || ':' || user_catalog.logical_key || ':' ||
              work_unit_row.date::TEXT || ':' || slot.logical_slot::TEXT
          )) AS active_seed_worklog_count,
       (SELECT pg_catalog.count(*) FROM public.work_unit work_unit_row
         JOIN demo_seed_user_catalog user_catalog ON user_catalog.user_id = work_unit_row.user_id
         CROSS JOIN demo_seed_catalog_context catalog_context
         JOIN demo_seed_generator_catalog generator ON generator.generator_name = 'work-unit'
         CROSS JOIN LATERAL pg_catalog.generate_series(1, generator.max_slot) AS slot(logical_slot)
        WHERE work_unit_row.state = 'Active'
          AND work_unit_row.archived_at IS NULL
          AND work_unit_row.entry_type = 'Note'
          AND work_unit_row.id = public.uuid_generate_v5(
              catalog_context.namespace_uuid,
              'work-unit:v' || generator.version::TEXT || ':' || user_catalog.logical_key || ':' ||
              work_unit_row.date::TEXT || ':' || slot.logical_slot::TEXT
          )) AS active_seed_note_count,
       (SELECT pg_catalog.min(work_unit_row.date) FROM public.work_unit work_unit_row
         JOIN demo_seed_user_catalog user_catalog ON user_catalog.user_id = work_unit_row.user_id
         CROSS JOIN demo_seed_catalog_context catalog_context
         JOIN demo_seed_generator_catalog generator ON generator.generator_name = 'work-unit'
         CROSS JOIN LATERAL pg_catalog.generate_series(1, generator.max_slot) AS slot(logical_slot)
        WHERE work_unit_row.state = 'Active'
          AND work_unit_row.archived_at IS NULL
          AND work_unit_row.id = public.uuid_generate_v5(
              catalog_context.namespace_uuid,
              'work-unit:v' || generator.version::TEXT || ':' || user_catalog.logical_key || ':' ||
              work_unit_row.date::TEXT || ':' || slot.logical_slot::TEXT
          )) AS active_seed_first_date,
       (SELECT pg_catalog.max(work_unit_row.date) FROM public.work_unit work_unit_row
         JOIN demo_seed_user_catalog user_catalog ON user_catalog.user_id = work_unit_row.user_id
         CROSS JOIN demo_seed_catalog_context catalog_context
         JOIN demo_seed_generator_catalog generator ON generator.generator_name = 'work-unit'
         CROSS JOIN LATERAL pg_catalog.generate_series(1, generator.max_slot) AS slot(logical_slot)
        WHERE work_unit_row.state = 'Active'
          AND work_unit_row.archived_at IS NULL
          AND work_unit_row.id = public.uuid_generate_v5(
              catalog_context.namespace_uuid,
              'work-unit:v' || generator.version::TEXT || ':' || user_catalog.logical_key || ':' ||
              work_unit_row.date::TEXT || ':' || slot.logical_slot::TEXT
          )) AS active_seed_last_date
FROM demo_seed_organization_context context
CROSS JOIN demo_seed_parameters parameters
JOIN public.organization_user membership
  ON membership.organization_id = context.organization_id
 AND membership.archived_at IS NULL
JOIN public."user" users
  ON users.id = membership.user_id
 AND users.archived_at IS NULL
LEFT JOIN demo_seed_user_catalog catalog ON catalog.user_id = users.id
GROUP BY context.organization_id, parameters.seed_today;

DO
$$
DECLARE
    force_verification_failure BOOLEAN;
BEGIN
    SELECT test_force_verification_failure
      INTO STRICT force_verification_failure
      FROM demo_seed_parameters;

    IF force_verification_failure THEN
        RAISE EXCEPTION 'demo seed test-only forced final verification failure';
    END IF;
END;
$$;

\if :standalone_verify
ROLLBACK;

DO
$$
DECLARE
    lock_released BOOLEAN;
BEGIN
    SELECT pg_catalog.pg_advisory_unlock(
               pg_catalog.hashtextextended('CT-532:' || organization_slug, 0)
           )
      INTO STRICT lock_released
      FROM demo_seed_parameters;

    IF lock_released IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'standalone demo seed verification failed to release its advisory lock';
    END IF;
END;
$$;
\endif
