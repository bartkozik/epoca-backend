\set ON_ERROR_STOP on
\if :{?test_force_verification_failure}
\else
\set test_force_verification_failure false
\endif

SET search_path = pg_temp, pg_catalog, public;
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
    standalone_verify            BOOLEAN NOT NULL,
    apply                        BOOLEAN NOT NULL
) ON COMMIT DROP;

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
    standalone_verify,
    apply
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
    :'standalone_verify'::BOOLEAN,
    :'apply'::BOOLEAN
);

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
        RAISE EXCEPTION 'demo seed rejects unsupported target';
    END IF;
    IF parameters.operation NOT IN ('apply', 'dry-run') THEN
        RAISE EXCEPTION 'demo seed rejects unsupported operation';
    END IF;
    IF parameters.organization_slug <> 'iteratorstesting' THEN
        RAISE EXCEPTION 'demo seed rejects unexpected organization slug';
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
        RAISE EXCEPTION 'demo seed database identity mismatch';
    END IF;
    IF (pg_catalog.pg_control_system()).system_identifier::TEXT <> parameters.expected_system_identifier THEN
        RAISE EXCEPTION 'demo seed system identifier mismatch';
    END IF;
    IF pg_catalog.host(pg_catalog.inet_server_addr()) <> parameters.expected_server_address THEN
        RAISE EXCEPTION 'demo seed server address mismatch';
    END IF;
    IF pg_catalog.inet_server_port() <> parameters.expected_server_port THEN
        RAISE EXCEPTION 'demo seed server port mismatch';
    END IF;

    IF parameters.target = 'local' THEN
        IF parameters.expected_organization_id IS NOT NULL THEN
            RAISE EXCEPTION 'local demo seed rejects an expected organization id';
        END IF;
        IF parameters.demo_viewer_email <> '' THEN
            RAISE EXCEPTION 'local demo seed rejects a staging viewer';
        END IF;
        IF parameters.sql_test_mode THEN
            IF pg_catalog.current_database() !~ '^epoca_ct532_case_[0-9]+_[0-9]+$' THEN
                RAISE EXCEPTION 'SQL test mode requires an exact disposable database name';
            END IF;
        ELSIF pg_catalog.host(pg_catalog.inet_server_addr()) NOT IN ('127.0.0.1', '::1') THEN
            RAISE EXCEPTION 'local demo seed requires a loopback PostgreSQL server';
        END IF;
    ELSE
        IF parameters.sql_test_mode THEN
            RAISE EXCEPTION 'staging rejects SQL test mode';
        END IF;
        IF parameters.expected_organization_id IS NULL THEN
            RAISE EXCEPTION 'staging requires the expected organization id';
        END IF;
        IF parameters.demo_viewer_email = '' THEN
            RAISE EXCEPTION 'staging requires the demo viewer email';
        END IF;
    END IF;

    SELECT pg_catalog.string_agg(expected.table_name || '.' || expected.column_name, ', ' ORDER BY expected.table_name, expected.column_name)
      INTO missing_columns
      FROM (VALUES
          ('user', 'id'), ('user', 'first_name'), ('user', 'last_name'),
          ('user', 'avatar'), ('user', 'color'), ('user', 'is_super_admin'),
          ('user', 'created_at'), ('user', 'modified_at'), ('user', 'archived_at'),
          ('user_identity', 'id'), ('user_identity', 'user_id'), ('user_identity', 'type'),
          ('user_identity', 'value'), ('user_identity', 'main'), ('user_identity', 'device_id'),
          ('user_identity', 'confirmed'), ('user_identity', 'confirmation_code'), ('user_identity', 'created_at'),
          ('user_password', 'user_identity_id'), ('user_password', 'password'),
          ('user_login_token', 'user_identity_id'),
          ('refresh_token', 'user_id'), ('invitation', 'identity_value'),
          ('organization', 'id'), ('organization', 'name'), ('organization', 'slug'),
          ('organization', 'created_at'), ('organization', 'created_by'),
          ('organization', 'modified_at'), ('organization', 'modified_by'), ('organization', 'archived_at'),
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
        RAISE EXCEPTION 'demo seed schema preflight missing required columns: %', missing_columns;
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
        RAISE EXCEPTION 'demo seed schema preflight found unexpected work unit constraints';
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
        RAISE EXCEPTION 'demo seed schema preflight found an unexpected work unit client trigger';
    END IF;
END;
$$;

\ir catalog.sql

SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('CT-532:' || organization_slug, 0))
FROM demo_seed_parameters;

LOCK TABLE public.project IN EXCLUSIVE MODE;

CREATE TEMP TABLE demo_seed_organization_context
(
    organization_id UUID NOT NULL PRIMARY KEY,
    organization_existed BOOLEAN NOT NULL,
    organization_was_archived BOOLEAN NOT NULL
) ON COMMIT DROP;

DO
$$
DECLARE
    parameters demo_seed_parameters%ROWTYPE;
    deterministic_organization_id UUID;
    slug_organization_id UUID;
    slug_archived_at TIMESTAMPTZ;
    slug_count INTEGER;
BEGIN
    SELECT * INTO STRICT parameters FROM demo_seed_parameters;
    SELECT organization_id INTO STRICT deterministic_organization_id FROM demo_seed_catalog_context;

    IF EXISTS (
        SELECT 1
        FROM public.organization
        WHERE id = deterministic_organization_id
          AND slug <> parameters.organization_slug
    ) THEN
        RAISE EXCEPTION 'deterministic seed organization id is owned by another slug';
    END IF;

    SELECT pg_catalog.count(*)
      INTO slug_count
      FROM public.organization
     WHERE slug = parameters.organization_slug;
    IF slug_count = 1 THEN
        SELECT id, archived_at
          INTO STRICT slug_organization_id, slug_archived_at
          FROM public.organization
         WHERE slug = parameters.organization_slug;
    END IF;

    IF parameters.target = 'staging' THEN
        IF slug_count <> 1 OR slug_organization_id <> parameters.expected_organization_id OR slug_archived_at IS NOT NULL THEN
            RAISE EXCEPTION 'staging target organization does not match the active fingerprint';
        END IF;
        INSERT INTO demo_seed_organization_context VALUES (slug_organization_id, true, false);
    ELSIF slug_count = 0 THEN
        INSERT INTO demo_seed_organization_context VALUES (deterministic_organization_id, false, false);
    ELSIF slug_archived_at IS NULL THEN
        INSERT INTO demo_seed_organization_context VALUES (slug_organization_id, true, false);
    ELSIF slug_organization_id = deterministic_organization_id THEN
        INSERT INTO demo_seed_organization_context VALUES (slug_organization_id, true, true);
    ELSE
        RAISE EXCEPTION 'foreign archived organization owns the target slug';
    END IF;
END;
$$;

DO
$$
DECLARE
    parameters demo_seed_parameters%ROWTYPE;
    target_organization_id UUID;
BEGIN
    SELECT * INTO STRICT parameters FROM demo_seed_parameters;
    SELECT organization_id INTO STRICT target_organization_id FROM demo_seed_organization_context;

    PERFORM project_row.id
    FROM public.project AS project_row
    JOIN demo_seed_project_cleanup_catalog AS cleanup ON cleanup.project_id = project_row.id
    ORDER BY project_row.id
    FOR UPDATE OF project_row;

    IF EXISTS (
        SELECT 1
        FROM public.project AS project_row
        JOIN demo_seed_project_cleanup_catalog AS cleanup ON cleanup.project_id = project_row.id
        WHERE project_row.organization_id <> target_organization_id
    ) THEN
        RAISE EXCEPTION 'project cleanup id belongs to another organization';
    END IF;

    IF parameters.target = 'staging'
       AND (
           SELECT pg_catalog.count(*)
           FROM public.project AS project_row
           JOIN demo_seed_project_cleanup_catalog AS cleanup ON cleanup.project_id = project_row.id
       ) <> (SELECT pg_catalog.count(*) FROM demo_seed_project_cleanup_catalog) THEN
        RAISE EXCEPTION 'staging project cleanup catalog is incomplete';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.project AS project_row
        JOIN demo_seed_project_cleanup_catalog AS cleanup ON cleanup.project_id = project_row.id
        WHERE NOT (
            (
                project_row.name = cleanup.old_name
                AND (
                    (
                        cleanup.old_description_mode = 'exact'
                        AND project_row.description IS NOT DISTINCT FROM cleanup.old_description
                    )
                    OR (
                        cleanup.old_description_mode = 'blank'
                        AND COALESCE(project_row.description, '') = ''
                    )
                )
            )
            OR (
                project_row.name = cleanup.new_name
                AND project_row.description IS NOT DISTINCT FROM cleanup.new_description
            )
        )
    ) THEN
        RAISE EXCEPTION 'project cleanup row has an unapproved state';
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
END;
$$;

DO
$$
DECLARE
    parameters demo_seed_parameters%ROWTYPE;
    target_organization_id UUID;
    viewer_count INTEGER;
BEGIN
    SELECT * INTO STRICT parameters FROM demo_seed_parameters;
    SELECT organization_id INTO STRICT target_organization_id FROM demo_seed_organization_context;

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
            RAISE EXCEPTION 'staging viewer must resolve to exactly one active nonseed superadmin, Owner, or Manager';
        END IF;
    END IF;
END;
$$;

DO
$$
DECLARE
    target_organization_id UUID;
BEGIN
    SELECT organization_id INTO STRICT target_organization_id FROM demo_seed_organization_context;

    IF EXISTS (
        SELECT 1
        FROM public.invitation invitation_row
        JOIN demo_seed_user_catalog catalog ON pg_catalog.lower(catalog.email) = pg_catalog.lower(invitation_row.identity_value)
    ) THEN
        RAISE EXCEPTION 'seed invitation row is not allowed';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_user_catalog catalog
        JOIN public."user" users ON users.id = catalog.user_id
        WHERE NOT EXISTS (
            SELECT 1
            FROM public.user_identity identity
            WHERE identity.id = catalog.identity_id
              AND identity.user_id = catalog.user_id
              AND identity.value = catalog.email
        )
    ) THEN
        RAISE EXCEPTION 'deterministic seed user id is not accompanied by its exact catalog identity';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_user_catalog catalog
        JOIN public."user" users ON users.id = catalog.user_id
        WHERE (SELECT pg_catalog.count(*) FROM public.user_identity identity WHERE identity.user_id = catalog.user_id) <> 1
    ) THEN
        RAISE EXCEPTION 'seed user must have exactly one catalog identity';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_user_catalog catalog
        JOIN public.user_identity identity ON identity.id = catalog.identity_id
        WHERE identity.user_id <> catalog.user_id OR identity.value <> catalog.email
    ) THEN
        RAISE EXCEPTION 'deterministic seed identity id is owned by another relation';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_user_catalog catalog
        JOIN public.user_identity identity ON identity.value = catalog.email
        WHERE identity.id <> catalog.identity_id OR identity.user_id <> catalog.user_id
    ) THEN
        RAISE EXCEPTION 'deterministic seed identity value is owned by another relation';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_user_catalog catalog
        JOIN public.user_identity identity
          ON identity.type = 'MailAddress'
         AND identity.value <> catalog.email
         AND pg_catalog.lower(identity.value) = pg_catalog.lower(catalog.email)
        WHERE identity.id <> catalog.identity_id OR identity.user_id <> catalog.user_id
    ) THEN
        RAISE EXCEPTION 'case-insensitive seed MailAddress is owned by another relation';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_user_catalog catalog
        JOIN public.organization_user membership ON membership.id = catalog.membership_id
        WHERE membership.organization_id <> target_organization_id OR membership.user_id <> catalog.user_id
    ) THEN
        RAISE EXCEPTION 'deterministic seed membership id is owned by another relation';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_client_catalog catalog
        JOIN public.client client_row ON client_row.id = catalog.client_id
        WHERE client_row.organization_id <> target_organization_id
    ) THEN
        RAISE EXCEPTION 'catalog client belongs to another organization';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_project_catalog catalog
        JOIN public.project project_row ON project_row.id = catalog.project_id
        WHERE project_row.organization_id <> target_organization_id
    ) THEN
        RAISE EXCEPTION 'catalog project belongs to another organization';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_project_catalog catalog
        JOIN public.project project_row ON project_row.id = catalog.project_id
        WHERE project_row.client_id <> catalog.client_id
    ) THEN
        RAISE EXCEPTION 'catalog project has an unexpected client parent';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_project_user_catalog catalog
        JOIN public.project_user project_user_row ON project_user_row.id = catalog.project_user_id
        LEFT JOIN public.project project_row ON project_row.id = project_user_row.project_id
        WHERE project_user_row.project_id <> catalog.project_id
           OR project_user_row.user_id <> catalog.user_id
           OR project_row.organization_id IS DISTINCT FROM target_organization_id
    ) THEN
        RAISE EXCEPTION 'catalog project user has an unexpected project/user parent pair';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.project_user project_user_row
        JOIN demo_seed_project_user_catalog membership_catalog
          ON membership_catalog.project_id = project_user_row.project_id
         AND membership_catalog.user_id = project_user_row.user_id
        WHERE project_user_row.archived_at IS NULL
          AND project_user_row.id <> membership_catalog.project_user_id
    ) THEN
        RAISE EXCEPTION 'target seed project/user pair is owned by a noncatalog project membership';
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
        FROM public.organization_user membership
        WHERE membership.organization_id = target_organization_id
          AND membership.archived_at IS NULL
        GROUP BY membership.user_id
        HAVING pg_catalog.count(*) > 1
    ) THEN
        RAISE EXCEPTION 'duplicate active organization membership rows are not supported';
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
        RAISE EXCEPTION 'active organization membership references an archived seed user';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.organization_user membership
        JOIN demo_seed_user_catalog catalog ON catalog.user_id = membership.user_id
        WHERE membership.organization_id = target_organization_id
          AND membership.archived_at IS NULL
          AND membership.id <> catalog.membership_id
    ) THEN
        RAISE EXCEPTION 'seed user is actively joined through a noncatalog membership id';
    END IF;
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

DO
$$
DECLARE
    nonseed_count INTEGER;
    owner_count INTEGER;
    manager_count INTEGER;
    employee_count INTEGER;
    external_count INTEGER;
    superadmin_count INTEGER;
BEGIN
    IF EXISTS (
        SELECT 1
        FROM demo_seed_nonseed_active_member
        WHERE role NOT IN ('Owner', 'Manager', 'Employee', 'External')
           OR role IS NULL
    ) OR EXISTS (
        SELECT 1
        FROM demo_seed_organization_context context
        JOIN public.organization_user membership ON membership.organization_id = context.organization_id
        JOIN public."user" users ON users.id = membership.user_id
        WHERE membership.archived_at IS NULL
          AND users.archived_at IS NULL
          AND NOT EXISTS (SELECT 1 FROM demo_seed_user_catalog catalog WHERE catalog.user_id = membership.user_id)
          AND pg_catalog.cardinality(membership.roles) <> 1
    ) THEN
        RAISE EXCEPTION 'nonseed active members must have exactly one supported role';
    END IF;

    SELECT pg_catalog.count(*),
           pg_catalog.count(*) FILTER (WHERE role = 'Owner'),
           pg_catalog.count(*) FILTER (WHERE role = 'Manager'),
           pg_catalog.count(*) FILTER (WHERE role = 'Employee'),
           pg_catalog.count(*) FILTER (WHERE role = 'External'),
           pg_catalog.count(*) FILTER (WHERE is_super_admin)
      INTO nonseed_count, owner_count, manager_count, employee_count, external_count, superadmin_count
      FROM demo_seed_nonseed_active_member;

    IF nonseed_count > 8 THEN
        RAISE EXCEPTION 'target organization has more than eight nonseed active members';
    END IF;
    IF owner_count > 2 OR manager_count > 2 OR employee_count > 22 OR external_count > 4 THEN
        RAISE EXCEPTION 'nonseed role capacity cannot reach the required distribution';
    END IF;
    IF superadmin_count > 2 THEN
        RAISE EXCEPTION 'target organization has more than two active nonseed superadmins';
    END IF;
END;
$$;

\ir member-target.sql

DO
$$
DECLARE
    target_organization_id UUID;
    expected_seed_count INTEGER;
BEGIN
    SELECT organization_id INTO STRICT target_organization_id FROM demo_seed_organization_context;
    SELECT 30 - pg_catalog.count(*) INTO expected_seed_count FROM demo_seed_nonseed_active_member;

    IF expected_seed_count < 22 OR (SELECT pg_catalog.count(*) FROM demo_seed_target_member) <> expected_seed_count THEN
        RAISE EXCEPTION 'target seed membership count cannot reach exactly thirty active members';
    END IF;

    IF EXISTS (SELECT 1 FROM demo_seed_target_member WHERE is_super_admin AND role = 'External') THEN
        RAISE EXCEPTION 'seed superadmin cannot occupy an External role';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_target_member target
        JOIN public.organization_user membership ON membership.user_id = target.user_id
        WHERE membership.organization_id <> target_organization_id
          AND membership.archived_at IS NULL
    ) THEN
        RAISE EXCEPTION 'target seed user has an active membership in another organization';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_user_catalog catalog
        JOIN public."user" users ON users.id = catalog.user_id
        JOIN public.organization_user membership ON membership.user_id = catalog.user_id
        WHERE NOT EXISTS (
                  SELECT 1 FROM demo_seed_target_member target WHERE target.user_id = catalog.user_id
              )
          AND membership.archived_at IS NULL
          AND NOT (
              membership.id = catalog.membership_id
              AND membership.organization_id = target_organization_id
          )
    ) THEN
        RAISE EXCEPTION 'non-target seed user has another active membership';
    END IF;
END;
$$;

\ir project-team-target.sql

DO
$$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM demo_seed_project_catalog project
        LEFT JOIN demo_seed_target_project_user target ON target.project_id = project.project_id
        WHERE project.catalog_state = 'active'
        GROUP BY project.project_id, project.team_size
        HAVING pg_catalog.count(target.project_user_id) <> project.team_size
    ) THEN
        RAISE EXCEPTION 'deterministic seed project team does not match catalog team size';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_target_member member
        WHERE NOT EXISTS (
            SELECT 1
            FROM demo_seed_target_project_user target
            WHERE target.user_id = member.user_id
              AND target.project_status = 'active'
              AND target.is_primary
        )
    ) THEN
        RAISE EXCEPTION 'every active seed user requires a primary project';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_project_catalog project
        WHERE project.status = 'active'
          AND NOT EXISTS (
              SELECT 1
              FROM demo_seed_target_project_user target
              WHERE target.project_id = project.project_id
                AND target.project_role = 'Owner'
                AND target.organization_role IN ('Manager', 'Employee')
          )
    ) THEN
        RAISE EXCEPTION 'every active seed project requires an eligible Project Owner';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_target_project_user target
        JOIN demo_seed_project_catalog project ON project.project_id = target.project_id
        JOIN demo_seed_client_catalog client ON client.client_id = project.client_id
        WHERE target.organization_role = 'External'
          AND (project.status <> 'active' OR client.status <> 'active' OR client.client_type <> 'External')
    ) OR EXISTS (
        SELECT 1
        FROM demo_seed_target_project_user target
        WHERE target.organization_role = 'External'
          AND target.project_status = 'active'
        GROUP BY target.user_id
        HAVING pg_catalog.count(*) > 2
    ) OR EXISTS (
        SELECT 1
        FROM demo_seed_target_project_user target
        WHERE target.organization_role = 'External'
          AND target.project_status = 'active'
        GROUP BY target.project_id
        HAVING pg_catalog.count(*) > 2
    ) THEN
        RAISE EXCEPTION 'External seed assignments require active external-client projects and at most two active assignments per user and project';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_target_project_user target
        WHERE target.organization_role IN ('Owner', 'Manager')
          AND target.project_role = 'Member'
          AND NOT target.is_primary
    ) THEN
        RAISE EXCEPTION 'organization Owners and Managers may be project Members only through their primary assignment';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.project_user existing
        JOIN demo_seed_target_project_user target
          ON target.project_id = existing.project_id
         AND target.user_id = existing.user_id
        WHERE existing.id <> target.project_user_id
    ) THEN
        RAISE EXCEPTION 'target seed project/user pair is owned by a noncatalog project membership';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.project_user existing
        JOIN demo_seed_project_catalog project ON project.project_id = existing.project_id
        WHERE project.status = 'archived'
          AND existing.archived_at IS NULL
          AND NOT EXISTS (
              SELECT 1
              FROM demo_seed_project_user_catalog catalog
              WHERE catalog.project_user_id = existing.id
                AND catalog.project_id = existing.project_id
                AND catalog.user_id = existing.user_id
          )
    ) THEN
        RAISE EXCEPTION 'foreign active membership conflicts with an archived seed project';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.project foreign_project
        JOIN demo_seed_client_catalog client ON client.client_id = foreign_project.client_id
        WHERE client.status = 'archived'
          AND foreign_project.archived_at IS NULL
          AND NOT EXISTS (
              SELECT 1
              FROM demo_seed_project_catalog project
              WHERE project.project_id = foreign_project.id
                AND project.client_id = foreign_project.client_id
          )
    ) THEN
        RAISE EXCEPTION 'foreign active project conflicts with an archived seed client';
    END IF;
END;
$$;

\ir work-unit-target.sql

CREATE TEMP TABLE demo_seed_owned_work_unit
(
    work_unit_id UUID PRIMARY KEY
) ON COMMIT DROP;

INSERT INTO demo_seed_owned_work_unit (work_unit_id)
SELECT DISTINCT work_unit_row.id
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
);

DO
$$
DECLARE
    parameters demo_seed_parameters%ROWTYPE;
    target_organization_id UUID;
BEGIN
    SELECT * INTO STRICT parameters FROM demo_seed_parameters;
    SELECT organization_id INTO STRICT target_organization_id FROM demo_seed_organization_context;

    IF (SELECT pg_catalog.count(*) FROM demo_seed_reporting_dates) <> 66
       OR (SELECT pg_catalog.count(*) FROM demo_seed_reporting_dates WHERE NOT is_weekend) <> 60
       OR (SELECT pg_catalog.count(*) FROM demo_seed_reporting_dates WHERE is_weekend) <> 6 THEN
        RAISE EXCEPTION 'work-unit generator requires exactly sixty business days and six weekend days';
    END IF;

    IF (SELECT pg_catalog.count(*) FROM demo_seed_target_work_unit
        WHERE source_kind = 'active-worklog') <> 1200
       OR (SELECT pg_catalog.count(*) FROM demo_seed_target_work_unit
           WHERE source_kind = 'active-note') <> 100
       OR (SELECT pg_catalog.count(*) FROM demo_seed_target_work_unit
           WHERE source_kind = 'archived-history') <> 60 THEN
        RAISE EXCEPTION 'work-unit generator produced unexpected target counts';
    END IF;

    IF EXISTS (SELECT 1 FROM demo_seed_target_work_unit WHERE logical_slot > 64)
       OR EXISTS (
           SELECT 1
           FROM demo_seed_target_work_unit target
           WHERE target.organization_id <> target_organization_id
              OR (target.source_kind IN ('active-worklog', 'active-note') AND (
                    target.state <> 'Active'
                    OR target.archived_at IS NOT NULL
                    OR target.work_date < parameters.seed_today - 84
                    OR target.work_date >= parameters.seed_today
                 ))
              OR (target.source_kind = 'archived-history' AND (
                    target.state <> 'Archived'
                    OR target.archived_at IS NULL
                    OR target.work_date NOT BETWEEN parameters.seed_today - 45 AND parameters.seed_today - 31
                 ))
              OR (target.entry_type = 'Worklog' AND (
                    target.duration < INTERVAL '15 minutes'
                    OR target.duration > INTERVAL '4 hours'
                    OR pg_catalog.mod(EXTRACT(epoch FROM target.duration)::INTEGER, 900) <> 0
                    OR target.task_id IS NULL
                 ))
              OR (target.entry_type = 'Note' AND (
                    target.duration <> INTERVAL '0 seconds'
                    OR target.task_id IS NOT NULL
                 ))
       ) THEN
        RAISE EXCEPTION 'work-unit generator produced an invalid target row';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM demo_seed_target_work_unit target
        JOIN public.work_unit existing ON existing.id = target.work_unit_id
        LEFT JOIN demo_seed_owned_work_unit owned ON owned.work_unit_id = existing.id
        WHERE owned.work_unit_id IS NULL
           OR existing.organization_id <> target.organization_id
           OR existing.user_id <> target.user_id
           OR existing.date <> target.work_date
    ) THEN
        RAISE EXCEPTION 'deterministic work-unit id is owned by another row';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.work_unit existing
        JOIN demo_seed_owned_work_unit owned ON owned.work_unit_id = existing.id
        LEFT JOIN public.project project_row ON project_row.id = existing.project_id
        LEFT JOIN demo_seed_project_user_catalog membership
          ON membership.project_id = existing.project_id
         AND membership.user_id = existing.user_id
        WHERE existing.organization_id <> target_organization_id
           OR project_row.organization_id IS DISTINCT FROM target_organization_id
           OR existing.client_id IS DISTINCT FROM project_row.client_id
           OR membership.project_user_id IS NULL
           OR existing.entry_type NOT IN ('Worklog', 'Note')
           OR (existing.entry_type = 'Worklog' AND (
                 existing.duration < INTERVAL '15 minutes'
                 OR existing.duration > INTERVAL '4 hours'
                 OR pg_catalog.mod(EXTRACT(epoch FROM existing.duration)::INTEGER, 900) <> 0
              ))
           OR (existing.entry_type = 'Note' AND existing.duration <> INTERVAL '0 seconds')
    ) THEN
        RAISE EXCEPTION 'owned work-unit has an invalid parent, type, or duration';
    END IF;
END;
$$;

CREATE TEMP TABLE demo_seed_pending_change
(
    entity_type TEXT NOT NULL,
    operation TEXT NOT NULL CHECK (operation IN ('insert', 'update', 'activate', 'archive')),
    entity_id UUID NOT NULL,
    PRIMARY KEY (entity_type, entity_id)
) ON COMMIT DROP;

INSERT INTO demo_seed_pending_change (entity_type, operation, entity_id)
SELECT 'project_cleanup', 'update', project_row.id
FROM demo_seed_project_cleanup_catalog AS cleanup
JOIN public.project AS project_row ON project_row.id = cleanup.project_id
JOIN demo_seed_organization_context AS context ON context.organization_id = project_row.organization_id
WHERE project_row.name = cleanup.old_name
  AND (
      (
          cleanup.old_description_mode = 'exact'
          AND project_row.description IS NOT DISTINCT FROM cleanup.old_description
      )
      OR (
          cleanup.old_description_mode = 'blank'
          AND COALESCE(project_row.description, '') = ''
      )
  );

INSERT INTO demo_seed_pending_change (entity_type, operation, entity_id)
SELECT 'user',
       CASE
           WHEN users.id IS NULL THEN 'insert'
           WHEN users.archived_at IS NOT NULL THEN 'activate'
           ELSE 'update'
       END,
       catalog.user_id
FROM demo_seed_target_member target
JOIN demo_seed_user_catalog catalog ON catalog.profile_order = target.profile_order
LEFT JOIN public."user" users ON users.id = catalog.user_id
WHERE users.id IS NULL
   OR users.archived_at IS NOT NULL
   OR users.first_name IS DISTINCT FROM catalog.first_name
   OR users.last_name IS DISTINCT FROM catalog.last_name
   OR users.avatar IS DISTINCT FROM NULL
   OR users.color IS DISTINCT FROM catalog.color
   OR users.is_super_admin IS DISTINCT FROM target.is_super_admin;

INSERT INTO demo_seed_pending_change (entity_type, operation, entity_id)
SELECT 'password',
       CASE WHEN password.user_identity_id IS NULL THEN 'insert' ELSE 'update' END,
       catalog.identity_id
FROM demo_seed_target_member target
JOIN demo_seed_user_catalog catalog ON catalog.profile_order = target.profile_order
LEFT JOIN public.user_password password ON password.user_identity_id = catalog.identity_id
WHERE password.user_identity_id IS NULL
   OR password.password IS DISTINCT FROM catalog.password_hash;

INSERT INTO demo_seed_pending_change (entity_type, operation, entity_id)
SELECT 'password', 'archive', catalog.identity_id
FROM demo_seed_user_catalog catalog
JOIN public.user_password password ON password.user_identity_id = catalog.identity_id
WHERE NOT EXISTS (
    SELECT 1 FROM demo_seed_target_member target WHERE target.identity_id = catalog.identity_id
);

INSERT INTO demo_seed_pending_change (entity_type, operation, entity_id)
SELECT 'user', 'archive', catalog.user_id
FROM demo_seed_user_catalog catalog
JOIN public."user" users ON users.id = catalog.user_id
WHERE users.archived_at IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM demo_seed_target_member target WHERE target.user_id = catalog.user_id
  );

INSERT INTO demo_seed_pending_change (entity_type, operation, entity_id)
SELECT 'identity',
       CASE WHEN identity.id IS NULL THEN 'insert' ELSE 'update' END,
       catalog.identity_id
FROM demo_seed_target_member target
JOIN demo_seed_user_catalog catalog ON catalog.profile_order = target.profile_order
LEFT JOIN public.user_identity identity ON identity.id = catalog.identity_id
WHERE identity.id IS NULL
   OR identity.user_id IS DISTINCT FROM catalog.user_id
   OR identity.type IS DISTINCT FROM 'MailAddress'
   OR identity.value IS DISTINCT FROM catalog.email
   OR identity.main IS DISTINCT FROM true
   OR identity.device_id IS NOT NULL
   OR identity.confirmed IS DISTINCT FROM true
   OR identity.confirmation_code IS NOT NULL;

INSERT INTO demo_seed_pending_change (entity_type, operation, entity_id)
SELECT 'organization',
       CASE WHEN context.organization_existed THEN 'activate' ELSE 'insert' END,
       context.organization_id
FROM demo_seed_organization_context context
JOIN demo_seed_parameters parameters ON true
WHERE parameters.target = 'local'
  AND (NOT context.organization_existed OR context.organization_was_archived);

INSERT INTO demo_seed_pending_change (entity_type, operation, entity_id)
SELECT 'membership',
       CASE
           WHEN membership.id IS NULL THEN 'insert'
           WHEN membership.archived_at IS NOT NULL THEN 'activate'
           ELSE 'update'
       END,
       target.membership_id
FROM demo_seed_target_member target
LEFT JOIN public.organization_user membership ON membership.id = target.membership_id
WHERE membership.id IS NULL
   OR membership.archived_at IS NOT NULL
   OR membership.roles IS DISTINCT FROM ARRAY[target.role]::TEXT[];

INSERT INTO demo_seed_pending_change (entity_type, operation, entity_id)
SELECT 'membership', 'archive', membership.id
FROM demo_seed_organization_context context
JOIN public.organization_user membership ON membership.organization_id = context.organization_id
JOIN demo_seed_user_catalog catalog
  ON catalog.membership_id = membership.id
 AND catalog.user_id = membership.user_id
WHERE membership.archived_at IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM demo_seed_target_member target WHERE target.membership_id = membership.id
  );

INSERT INTO demo_seed_pending_change (entity_type, operation, entity_id)
SELECT 'client',
       CASE
           WHEN client_row.id IS NULL THEN 'insert'
           WHEN catalog.status = 'active' AND client_row.archived_at IS NOT NULL THEN 'activate'
           WHEN catalog.status = 'archived' AND client_row.archived_at IS NULL THEN 'archive'
           ELSE 'update'
       END,
       catalog.client_id
FROM demo_seed_client_catalog catalog
CROSS JOIN demo_seed_organization_context context
CROSS JOIN demo_seed_parameters parameters
LEFT JOIN public.client client_row ON client_row.id = catalog.client_id
WHERE catalog.catalog_state = 'active'
  AND (
      client_row.id IS NULL
      OR client_row.name IS DISTINCT FROM catalog.name
      OR client_row.color IS DISTINCT FROM catalog.color
      OR client_row.organization_id IS DISTINCT FROM context.organization_id
      OR client_row.type IS DISTINCT FROM catalog.client_type
      OR client_row.texture IS DISTINCT FROM catalog.texture
      OR client_row.logo IS DISTINCT FROM catalog.logo
      OR client_row.created_by IS DISTINCT FROM (SELECT user_id FROM demo_seed_target_member WHERE profile_order = 1)
      OR client_row.modified_by IS DISTINCT FROM (SELECT user_id FROM demo_seed_target_member WHERE profile_order = 1)
      OR (catalog.status = 'active' AND (client_row.archived_at IS NOT NULL OR client_row.archived_by IS NOT NULL))
      OR (catalog.status = 'archived' AND (
          client_row.archived_at IS DISTINCT FROM ((parameters.seed_today - 30)::TIMESTAMP AT TIME ZONE 'UTC')
          OR client_row.archived_by IS DISTINCT FROM (SELECT user_id FROM demo_seed_target_member WHERE profile_order = 1)
      ))
  );

INSERT INTO demo_seed_pending_change (entity_type, operation, entity_id)
SELECT 'project',
       CASE
           WHEN project_row.id IS NULL THEN 'insert'
           WHEN catalog.status = 'active' AND project_row.archived_at IS NOT NULL THEN 'activate'
           WHEN catalog.status = 'archived' AND project_row.archived_at IS NULL THEN 'archive'
           ELSE 'update'
       END,
       catalog.project_id
FROM demo_seed_project_catalog catalog
CROSS JOIN demo_seed_organization_context context
CROSS JOIN demo_seed_parameters parameters
LEFT JOIN public.project project_row ON project_row.id = catalog.project_id
WHERE catalog.catalog_state = 'active'
  AND (
      project_row.id IS NULL
      OR project_row.name IS DISTINCT FROM catalog.name
      OR project_row.description IS DISTINCT FROM catalog.description
      OR project_row.organization_id IS DISTINCT FROM context.organization_id
      OR project_row.client_id IS DISTINCT FROM catalog.client_id
      OR project_row.created_by IS DISTINCT FROM (SELECT user_id FROM demo_seed_target_member WHERE profile_order = 1)
      OR project_row.modified_by IS DISTINCT FROM (SELECT user_id FROM demo_seed_target_member WHERE profile_order = 1)
      OR (catalog.status = 'active' AND (project_row.archived_at IS NOT NULL OR project_row.archived_by IS NOT NULL))
      OR (catalog.status = 'archived' AND (
          project_row.archived_at IS DISTINCT FROM ((parameters.seed_today - 30)::TIMESTAMP AT TIME ZONE 'UTC')
          OR project_row.archived_by IS DISTINCT FROM (SELECT user_id FROM demo_seed_target_member WHERE profile_order = 1)
      ))
  );

INSERT INTO demo_seed_pending_change (entity_type, operation, entity_id)
SELECT 'project_user',
       CASE
           WHEN project_user_row.id IS NULL THEN 'insert'
           WHEN target.project_status = 'active' AND project_user_row.archived_at IS NOT NULL THEN 'activate'
           WHEN target.project_status = 'archived' AND project_user_row.archived_at IS NULL THEN 'archive'
           ELSE 'update'
       END,
       target.project_user_id
FROM demo_seed_target_project_user target
CROSS JOIN demo_seed_parameters parameters
LEFT JOIN public.project_user project_user_row ON project_user_row.id = target.project_user_id
WHERE project_user_row.id IS NULL
   OR project_user_row.project_id IS DISTINCT FROM target.project_id
   OR project_user_row.user_id IS DISTINCT FROM target.user_id
   OR project_user_row.role IS DISTINCT FROM target.project_role
   OR (target.project_status = 'active' AND project_user_row.archived_at IS NOT NULL)
   OR (target.project_status = 'archived' AND
       project_user_row.archived_at IS DISTINCT FROM ((parameters.seed_today - 30)::TIMESTAMP AT TIME ZONE 'UTC'));

INSERT INTO demo_seed_pending_change (entity_type, operation, entity_id)
SELECT 'project_user', 'archive', project_user_row.id
FROM public.project_user project_user_row
JOIN demo_seed_project_user_catalog catalog
  ON catalog.project_user_id = project_user_row.id
 AND catalog.project_id = project_user_row.project_id
 AND catalog.user_id = project_user_row.user_id
WHERE project_user_row.archived_at IS NULL
  AND NOT EXISTS (
      SELECT 1
      FROM demo_seed_target_project_user target
      WHERE target.project_user_id = project_user_row.id
  );

INSERT INTO demo_seed_pending_change (entity_type, operation, entity_id)
SELECT 'work_unit',
       CASE
           WHEN existing.id IS NULL THEN 'insert'
           WHEN target.state = 'Active'
                AND (existing.state <> 'Active' OR existing.archived_at IS NOT NULL) THEN 'activate'
           WHEN target.state = 'Archived'
                AND (existing.state <> 'Archived' OR existing.archived_at IS NULL) THEN 'archive'
           ELSE 'update'
       END,
       target.work_unit_id
FROM demo_seed_target_work_unit target
LEFT JOIN public.work_unit existing ON existing.id = target.work_unit_id
WHERE existing.id IS NULL
   OR existing.organization_id IS DISTINCT FROM target.organization_id
   OR existing.user_id IS DISTINCT FROM target.user_id
   OR existing.project_id IS DISTINCT FROM target.project_id
   OR existing.client_id IS DISTINCT FROM target.client_id
   OR existing.date IS DISTINCT FROM target.work_date
   OR existing.duration IS DISTINCT FROM target.duration
   OR existing.entry_type IS DISTINCT FROM target.entry_type
   OR existing.description IS DISTINCT FROM target.description
   OR existing.task_id IS DISTINCT FROM target.task_id
   OR existing.state IS DISTINCT FROM target.state
   OR existing.archived_at IS DISTINCT FROM target.archived_at;

INSERT INTO demo_seed_pending_change (entity_type, operation, entity_id)
SELECT 'work_unit', 'archive', existing.id
FROM public.work_unit existing
JOIN demo_seed_owned_work_unit owned ON owned.work_unit_id = existing.id
WHERE (existing.state <> 'Archived' OR existing.archived_at IS NULL)
  AND NOT EXISTS (
      SELECT 1
      FROM demo_seed_target_work_unit target
      WHERE target.work_unit_id = existing.id
  );

CREATE TEMP TABLE demo_seed_change_plan
(
    entity_type TEXT NOT NULL,
    operation TEXT NOT NULL,
    row_count INTEGER NOT NULL,
    PRIMARY KEY (entity_type, operation)
) ON COMMIT DROP;

INSERT INTO demo_seed_change_plan (entity_type, operation, row_count)
SELECT entity.entity_type,
       operation.operation,
       pg_catalog.count(pending.entity_id)::INTEGER
FROM (VALUES
    ('organization'), ('user'), ('identity'), ('password'), ('membership'),
    ('client'), ('project'), ('project_cleanup'), ('project_user'), ('work_unit')
) AS entity(entity_type)
CROSS JOIN (VALUES ('insert'), ('update'), ('activate'), ('archive')) AS operation(operation)
LEFT JOIN demo_seed_pending_change pending
  ON pending.entity_type = entity.entity_type
 AND pending.operation = operation.operation
GROUP BY entity.entity_type, operation.operation;

INSERT INTO demo_seed_change_plan (entity_type, operation, row_count)
SELECT source.entity_type,
       operation.operation,
       pg_catalog.count(pending.entity_id)::INTEGER
FROM (VALUES
    ('active_worklog', 'active-worklog'),
    ('active_note', 'active-note'),
    ('historical_worklog', 'archived-history')
) AS source(entity_type, source_kind)
CROSS JOIN (VALUES ('insert'), ('update'), ('activate'), ('archive')) AS operation(operation)
LEFT JOIN demo_seed_target_work_unit target ON target.source_kind = source.source_kind
LEFT JOIN demo_seed_pending_change pending
  ON pending.entity_type = 'work_unit'
 AND pending.entity_id = target.work_unit_id
 AND pending.operation = operation.operation
GROUP BY source.entity_type, operation.operation;

UPDATE public.project AS project_row
SET name = cleanup.new_name,
    description = cleanup.new_description
FROM demo_seed_project_cleanup_catalog AS cleanup
JOIN demo_seed_pending_change AS pending
  ON pending.entity_type = 'project_cleanup'
 AND pending.operation = 'update'
 AND pending.entity_id = cleanup.project_id
WHERE project_row.id = cleanup.project_id
  AND project_row.name = cleanup.old_name
  AND (
      (
          cleanup.old_description_mode = 'exact'
          AND project_row.description IS NOT DISTINCT FROM cleanup.old_description
      )
      OR (
          cleanup.old_description_mode = 'blank'
          AND COALESCE(project_row.description, '') = ''
      )
  );

INSERT INTO public."user" (
    id, first_name, last_name, avatar, color, is_super_admin, created_at, modified_at, archived_at
)
SELECT catalog.user_id,
       catalog.first_name,
       catalog.last_name,
       NULL,
       catalog.color,
       target.is_super_admin,
       ((parameters.seed_today - 180)::TIMESTAMP + pg_catalog.make_interval(mins => catalog.profile_order)) AT TIME ZONE 'UTC',
       ((parameters.seed_today - 180)::TIMESTAMP + pg_catalog.make_interval(mins => catalog.profile_order)) AT TIME ZONE 'UTC',
       NULL
FROM demo_seed_target_member target
JOIN demo_seed_user_catalog catalog ON catalog.profile_order = target.profile_order
CROSS JOIN demo_seed_parameters parameters
ON CONFLICT (id) DO UPDATE
SET first_name = EXCLUDED.first_name,
    last_name = EXCLUDED.last_name,
    avatar = EXCLUDED.avatar,
    color = EXCLUDED.color,
    is_super_admin = EXCLUDED.is_super_admin,
    modified_at = (SELECT (seed_today::TIMESTAMP + INTERVAL '12 hours') AT TIME ZONE 'UTC' FROM demo_seed_parameters),
    archived_at = NULL
WHERE "user".first_name IS DISTINCT FROM EXCLUDED.first_name
   OR "user".last_name IS DISTINCT FROM EXCLUDED.last_name
   OR "user".avatar IS DISTINCT FROM EXCLUDED.avatar
   OR "user".color IS DISTINCT FROM EXCLUDED.color
   OR "user".is_super_admin IS DISTINCT FROM EXCLUDED.is_super_admin
   OR "user".archived_at IS NOT NULL;

UPDATE public."user" users
SET modified_at = parameters.seed_today::TIMESTAMP AT TIME ZONE 'UTC',
    archived_at = parameters.seed_today::TIMESTAMP AT TIME ZONE 'UTC'
FROM demo_seed_pending_change pending
CROSS JOIN demo_seed_parameters parameters
WHERE pending.entity_type = 'user'
  AND pending.operation = 'archive'
  AND users.id = pending.entity_id
  AND users.archived_at IS NULL;

INSERT INTO public.user_identity (
    id, user_id, type, value, main, device_id, confirmed, confirmation_code, created_at
)
SELECT catalog.identity_id,
       catalog.user_id,
       'MailAddress',
       catalog.email,
       true,
       NULL,
       true,
       NULL,
       (parameters.seed_today - 180)::TIMESTAMP + pg_catalog.make_interval(mins => catalog.profile_order)
FROM demo_seed_target_member target
JOIN demo_seed_user_catalog catalog ON catalog.profile_order = target.profile_order
CROSS JOIN demo_seed_parameters parameters
ON CONFLICT (id) DO UPDATE
SET user_id = EXCLUDED.user_id,
    type = EXCLUDED.type,
    value = EXCLUDED.value,
    main = EXCLUDED.main,
    device_id = EXCLUDED.device_id,
    confirmed = EXCLUDED.confirmed,
    confirmation_code = EXCLUDED.confirmation_code
WHERE user_identity.user_id IS DISTINCT FROM EXCLUDED.user_id
   OR user_identity.type IS DISTINCT FROM EXCLUDED.type
   OR user_identity.value IS DISTINCT FROM EXCLUDED.value
   OR user_identity.main IS DISTINCT FROM EXCLUDED.main
   OR user_identity.device_id IS DISTINCT FROM EXCLUDED.device_id
   OR user_identity.confirmed IS DISTINCT FROM EXCLUDED.confirmed
   OR user_identity.confirmation_code IS DISTINCT FROM EXCLUDED.confirmation_code;

INSERT INTO public.user_password (user_identity_id, password)
SELECT catalog.identity_id, catalog.password_hash
FROM demo_seed_target_member target
JOIN demo_seed_user_catalog catalog ON catalog.profile_order = target.profile_order
ON CONFLICT (user_identity_id) DO UPDATE
SET password = EXCLUDED.password
WHERE user_password.password IS DISTINCT FROM EXCLUDED.password;

DELETE FROM public.user_password password
USING demo_seed_user_catalog catalog
WHERE password.user_identity_id = catalog.identity_id
  AND NOT EXISTS (
      SELECT 1 FROM demo_seed_target_member target WHERE target.identity_id = catalog.identity_id
  );

INSERT INTO public.organization (
    id, name, slug, created_at, created_by, modified_at, modified_by, archived_at
)
SELECT context.organization_id,
       'Iterators Testing',
       parameters.organization_slug,
       ((parameters.seed_today - 180)::TIMESTAMP + INTERVAL '1 minute') AT TIME ZONE 'UTC',
       owner.user_id,
       ((parameters.seed_today - 180)::TIMESTAMP + INTERVAL '1 minute') AT TIME ZONE 'UTC',
       owner.user_id,
       NULL
FROM demo_seed_organization_context context
CROSS JOIN demo_seed_parameters parameters
JOIN demo_seed_target_member owner ON owner.profile_order = 1
WHERE parameters.target = 'local'
  AND NOT context.organization_existed;

UPDATE public.organization organization_row
SET archived_at = NULL,
    modified_at = (parameters.seed_today::TIMESTAMP + INTERVAL '12 hours') AT TIME ZONE 'UTC',
    modified_by = owner.user_id
FROM demo_seed_organization_context context
CROSS JOIN demo_seed_parameters parameters
JOIN demo_seed_target_member owner ON owner.profile_order = 1
WHERE organization_row.id = context.organization_id
  AND parameters.target = 'local'
  AND context.organization_was_archived
  AND organization_row.archived_at IS NOT NULL;

INSERT INTO public.organization_user (id, organization_id, user_id, roles, archived_at)
SELECT target.membership_id,
       context.organization_id,
       target.user_id,
       ARRAY[target.role]::TEXT[],
       NULL
FROM demo_seed_target_member target
CROSS JOIN demo_seed_organization_context context
ON CONFLICT (id) DO UPDATE
SET roles = EXCLUDED.roles,
    archived_at = NULL
WHERE organization_user.roles IS DISTINCT FROM EXCLUDED.roles
   OR organization_user.archived_at IS NOT NULL;

UPDATE public.organization_user membership
SET archived_at = (parameters.seed_today::TIMESTAMP + INTERVAL '12 hours') AT TIME ZONE 'UTC'
FROM demo_seed_pending_change pending
CROSS JOIN demo_seed_parameters parameters
WHERE pending.entity_type = 'membership'
  AND pending.operation = 'archive'
  AND membership.id = pending.entity_id
  AND membership.archived_at IS NULL;

INSERT INTO public.client (
    id, name, color, organization_id,
    created_at, created_by, modified_at, modified_by,
    archived_at, archived_by, logo, type, texture
)
SELECT catalog.client_id,
       catalog.name,
       catalog.color,
       context.organization_id,
       ((parameters.seed_today - 180)::TIMESTAMP + pg_catalog.make_interval(mins => catalog.client_order)) AT TIME ZONE 'UTC',
       owner.user_id,
       CASE
           WHEN catalog.status = 'archived' THEN (parameters.seed_today - 30)::TIMESTAMP AT TIME ZONE 'UTC'
           ELSE ((parameters.seed_today - 180)::TIMESTAMP + pg_catalog.make_interval(mins => catalog.client_order)) AT TIME ZONE 'UTC'
       END,
       owner.user_id,
       CASE WHEN catalog.status = 'archived' THEN (parameters.seed_today - 30)::TIMESTAMP AT TIME ZONE 'UTC' END,
       CASE WHEN catalog.status = 'archived' THEN owner.user_id END,
       catalog.logo,
       catalog.client_type,
       catalog.texture
FROM demo_seed_client_catalog catalog
CROSS JOIN demo_seed_organization_context context
CROSS JOIN demo_seed_parameters parameters
JOIN demo_seed_target_member owner ON owner.profile_order = 1
WHERE catalog.catalog_state = 'active'
ON CONFLICT (id) DO UPDATE
SET name = EXCLUDED.name,
    color = EXCLUDED.color,
    created_by = EXCLUDED.created_by,
    modified_at = (SELECT (seed_today::TIMESTAMP + INTERVAL '12 hours') AT TIME ZONE 'UTC' FROM demo_seed_parameters),
    modified_by = EXCLUDED.modified_by,
    archived_at = EXCLUDED.archived_at,
    archived_by = EXCLUDED.archived_by,
    logo = EXCLUDED.logo,
    type = EXCLUDED.type,
    texture = EXCLUDED.texture
WHERE client.organization_id = EXCLUDED.organization_id
  AND (
      client.name IS DISTINCT FROM EXCLUDED.name
      OR client.color IS DISTINCT FROM EXCLUDED.color
      OR client.created_by IS DISTINCT FROM EXCLUDED.created_by
      OR client.modified_by IS DISTINCT FROM EXCLUDED.modified_by
      OR client.archived_at IS DISTINCT FROM EXCLUDED.archived_at
      OR client.archived_by IS DISTINCT FROM EXCLUDED.archived_by
      OR client.logo IS DISTINCT FROM EXCLUDED.logo
      OR client.type IS DISTINCT FROM EXCLUDED.type
      OR client.texture IS DISTINCT FROM EXCLUDED.texture
  );

INSERT INTO public.project (
    id, name, description, organization_id, client_id,
    created_at, created_by, modified_at, modified_by,
    archived_at, archived_by
)
SELECT catalog.project_id,
       catalog.name,
       catalog.description,
       context.organization_id,
       catalog.client_id,
       ((parameters.seed_today - 170)::TIMESTAMP + pg_catalog.make_interval(mins => catalog.project_order)) AT TIME ZONE 'UTC',
       owner.user_id,
       CASE
           WHEN catalog.status = 'archived' THEN (parameters.seed_today - 30)::TIMESTAMP AT TIME ZONE 'UTC'
           ELSE ((parameters.seed_today - 170)::TIMESTAMP + pg_catalog.make_interval(mins => catalog.project_order)) AT TIME ZONE 'UTC'
       END,
       owner.user_id,
       CASE WHEN catalog.status = 'archived' THEN (parameters.seed_today - 30)::TIMESTAMP AT TIME ZONE 'UTC' END,
       CASE WHEN catalog.status = 'archived' THEN owner.user_id END
FROM demo_seed_project_catalog catalog
CROSS JOIN demo_seed_organization_context context
CROSS JOIN demo_seed_parameters parameters
JOIN demo_seed_target_member owner ON owner.profile_order = 1
WHERE catalog.catalog_state = 'active'
ON CONFLICT (id) DO UPDATE
SET name = EXCLUDED.name,
    description = EXCLUDED.description,
    created_by = EXCLUDED.created_by,
    modified_at = (SELECT (seed_today::TIMESTAMP + INTERVAL '12 hours') AT TIME ZONE 'UTC' FROM demo_seed_parameters),
    modified_by = EXCLUDED.modified_by,
    archived_at = EXCLUDED.archived_at,
    archived_by = EXCLUDED.archived_by
WHERE project.organization_id = EXCLUDED.organization_id
  AND project.client_id = EXCLUDED.client_id
  AND (
      project.name IS DISTINCT FROM EXCLUDED.name
      OR project.description IS DISTINCT FROM EXCLUDED.description
      OR project.created_by IS DISTINCT FROM EXCLUDED.created_by
      OR project.modified_by IS DISTINCT FROM EXCLUDED.modified_by
      OR project.archived_at IS DISTINCT FROM EXCLUDED.archived_at
      OR project.archived_by IS DISTINCT FROM EXCLUDED.archived_by
  );

INSERT INTO public.project_user (
    id, project_id, user_id, role, created_at, archived_at
)
SELECT target.project_user_id,
       target.project_id,
       target.user_id,
       target.project_role,
       ((parameters.seed_today - 160)::TIMESTAMP
           + pg_catalog.make_interval(hours => target.project_order, mins => target.profile_order)) AT TIME ZONE 'UTC',
       CASE
           WHEN target.project_status = 'archived'
               THEN (parameters.seed_today - 30)::TIMESTAMP AT TIME ZONE 'UTC'
       END
FROM demo_seed_target_project_user target
CROSS JOIN demo_seed_parameters parameters
ON CONFLICT (id) DO UPDATE
SET role = EXCLUDED.role,
    archived_at = EXCLUDED.archived_at
WHERE project_user.project_id = EXCLUDED.project_id
  AND project_user.user_id = EXCLUDED.user_id
  AND (
      project_user.role IS DISTINCT FROM EXCLUDED.role
      OR project_user.archived_at IS DISTINCT FROM EXCLUDED.archived_at
  );

UPDATE public.project_user project_user_row
SET archived_at = parameters.seed_today::TIMESTAMP AT TIME ZONE 'UTC'
FROM demo_seed_pending_change pending
CROSS JOIN demo_seed_parameters parameters
WHERE pending.entity_type = 'project_user'
  AND pending.operation = 'archive'
  AND project_user_row.id = pending.entity_id
  AND NOT EXISTS (
      SELECT 1
      FROM demo_seed_target_project_user target
      WHERE target.project_user_id = project_user_row.id
  )
  AND project_user_row.archived_at IS NULL;

INSERT INTO public.work_unit (
    id,
    organization_id,
    user_id,
    project_id,
    date,
    duration,
    description,
    created_at,
    modified_at,
    archived_at,
    task_id,
    state,
    client_id,
    entry_type
)
SELECT target.work_unit_id,
       target.organization_id,
       target.user_id,
       target.project_id,
       target.work_date,
       target.duration,
       target.description,
       target.created_at,
       target.modified_at,
       target.archived_at,
       target.task_id,
       target.state,
       target.client_id,
       target.entry_type
FROM demo_seed_target_work_unit target
ON CONFLICT (id) DO UPDATE
SET organization_id = EXCLUDED.organization_id,
    user_id = EXCLUDED.user_id,
    project_id = EXCLUDED.project_id,
    date = EXCLUDED.date,
    duration = EXCLUDED.duration,
    description = EXCLUDED.description,
    modified_at = (SELECT (seed_today::TIMESTAMP + INTERVAL '12 hours') AT TIME ZONE 'UTC'
                   FROM demo_seed_parameters),
    archived_at = EXCLUDED.archived_at,
    task_id = EXCLUDED.task_id,
    state = EXCLUDED.state,
    entry_type = EXCLUDED.entry_type
WHERE work_unit.organization_id IS DISTINCT FROM EXCLUDED.organization_id
   OR work_unit.user_id IS DISTINCT FROM EXCLUDED.user_id
   OR work_unit.project_id IS DISTINCT FROM EXCLUDED.project_id
   OR work_unit.client_id IS DISTINCT FROM EXCLUDED.client_id
   OR work_unit.date IS DISTINCT FROM EXCLUDED.date
   OR work_unit.duration IS DISTINCT FROM EXCLUDED.duration
   OR work_unit.description IS DISTINCT FROM EXCLUDED.description
   OR work_unit.archived_at IS DISTINCT FROM EXCLUDED.archived_at
   OR work_unit.task_id IS DISTINCT FROM EXCLUDED.task_id
   OR work_unit.state IS DISTINCT FROM EXCLUDED.state
   OR work_unit.entry_type IS DISTINCT FROM EXCLUDED.entry_type;

UPDATE public.work_unit work_unit_row
SET state = 'Archived',
    modified_at = parameters.seed_today::TIMESTAMP AT TIME ZONE 'UTC',
    archived_at = parameters.seed_today::TIMESTAMP AT TIME ZONE 'UTC'
FROM demo_seed_pending_change pending
CROSS JOIN demo_seed_parameters parameters
WHERE pending.entity_type = 'work_unit'
  AND pending.operation = 'archive'
  AND work_unit_row.id = pending.entity_id
  AND NOT EXISTS (
      SELECT 1
      FROM demo_seed_target_work_unit target
      WHERE target.work_unit_id = work_unit_row.id
  )
  AND (work_unit_row.state <> 'Archived' OR work_unit_row.archived_at IS NULL);

SELECT entity_type, operation, row_count
FROM demo_seed_change_plan
ORDER BY entity_type, operation;

\ir verify.sql

\if :apply
COMMIT;
\else
ROLLBACK;
\echo 'ROLLBACK: dry-run completed'
\endif
