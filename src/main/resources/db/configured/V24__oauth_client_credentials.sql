ALTER TABLE "${flyway:defaultSchema}"."user"
    ADD COLUMN account_type TEXT NOT NULL DEFAULT 'Human',
    ADD CONSTRAINT user_account_type_check
        CHECK (account_type IN ('Human', 'ServiceAccount'));

CREATE INDEX work_unit_oauth_report_project_idx
    ON "${flyway:defaultSchema}".work_unit (organization_id, project_id);

CREATE TABLE "${flyway:defaultSchema}".oauth_client
(
    id                      UUID        NOT NULL PRIMARY KEY,
    client_id               TEXT        NOT NULL,
    organization_id         UUID        NOT NULL REFERENCES "${flyway:defaultSchema}".organization (id) ON DELETE NO ACTION,
    service_account_user_id UUID        NOT NULL REFERENCES "${flyway:defaultSchema}"."user" (id) ON DELETE NO ACTION,
    name                    TEXT        NOT NULL,
    status                  TEXT        NOT NULL,
    project_access_mode     TEXT        NOT NULL,
    secret_hash             TEXT        NOT NULL,
    secret_version          BIGINT      NOT NULL DEFAULT 1,
    version                 BIGINT      NOT NULL DEFAULT 1,
    policy_version          BIGINT      NOT NULL DEFAULT 1,
    created_at              TIMESTAMPTZ NOT NULL,
    created_by              UUID        NOT NULL REFERENCES "${flyway:defaultSchema}"."user" (id) ON DELETE NO ACTION,
    modified_at             TIMESTAMPTZ NOT NULL,
    modified_by             UUID        NOT NULL REFERENCES "${flyway:defaultSchema}"."user" (id) ON DELETE NO ACTION,
    secret_rotated_at       TIMESTAMPTZ NOT NULL,
    secret_rotated_by       UUID        NOT NULL REFERENCES "${flyway:defaultSchema}"."user" (id) ON DELETE NO ACTION,
    revoked_at              TIMESTAMPTZ NULL,
    revoked_by              UUID        NULL REFERENCES "${flyway:defaultSchema}"."user" (id) ON DELETE NO ACTION,
    CONSTRAINT oauth_client_client_id_key UNIQUE (client_id),
    CONSTRAINT oauth_client_organization_id_name_key UNIQUE (organization_id, name),
    CONSTRAINT oauth_client_service_account_user_id_key
        UNIQUE (service_account_user_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT oauth_client_name_check CHECK (btrim(name) <> ''),
    CONSTRAINT oauth_client_client_id_check CHECK (btrim(client_id) <> ''),
    CONSTRAINT oauth_client_secret_hash_check CHECK (btrim(secret_hash) <> ''),
    CONSTRAINT oauth_client_status_check CHECK (status IN ('Active', 'Revoked')),
    CONSTRAINT oauth_client_project_access_mode_check
        CHECK (project_access_mode IN ('AllProjects', 'SelectedProjects')),
    CONSTRAINT oauth_client_positive_versions_check
        CHECK (secret_version > 0 AND version > 0 AND policy_version > 0),
    CONSTRAINT oauth_client_status_revocation_check CHECK (
        (status = 'Active' AND revoked_at IS NULL AND revoked_by IS NULL)
        OR (status = 'Revoked' AND revoked_at IS NOT NULL AND revoked_by IS NOT NULL)
    )
);

CREATE INDEX oauth_client_organization_id_idx ON "${flyway:defaultSchema}".oauth_client (organization_id);
CREATE INDEX oauth_client_created_by_idx ON "${flyway:defaultSchema}".oauth_client (created_by);
CREATE INDEX oauth_client_modified_by_idx ON "${flyway:defaultSchema}".oauth_client (modified_by);
CREATE INDEX oauth_client_secret_rotated_by_idx ON "${flyway:defaultSchema}".oauth_client (secret_rotated_by);
CREATE INDEX oauth_client_revoked_by_idx ON "${flyway:defaultSchema}".oauth_client (revoked_by) WHERE revoked_by IS NOT NULL;

CREATE TABLE "${flyway:defaultSchema}".oauth_client_scope
(
    oauth_client_id UUID NOT NULL REFERENCES "${flyway:defaultSchema}".oauth_client (id) ON DELETE NO ACTION,
    scope           TEXT NOT NULL,
    PRIMARY KEY (oauth_client_id, scope),
    CONSTRAINT oauth_client_scope_scope_check CHECK (scope IN (
        'organization:read',
        'users:read',
        'clients:read',
        'projects:read',
        'work-units:read',
        'work-units:write',
        'reports:read',
        'reports:export'
    ))
);

CREATE TABLE "${flyway:defaultSchema}".oauth_client_project
(
    oauth_client_id UUID NOT NULL REFERENCES "${flyway:defaultSchema}".oauth_client (id) ON DELETE NO ACTION,
    project_id      UUID NOT NULL REFERENCES "${flyway:defaultSchema}".project (id) ON DELETE NO ACTION,
    PRIMARY KEY (oauth_client_id, project_id)
);

CREATE INDEX oauth_client_project_project_id_idx ON "${flyway:defaultSchema}".oauth_client_project (project_id);

CREATE FUNCTION "${flyway:defaultSchema}".oauth_audit_metadata_has_keys(
    input_value JSONB,
    allowed_keys TEXT[],
    required_keys TEXT[]
)
RETURNS BOOLEAN
LANGUAGE sql
IMMUTABLE
STRICT
AS
$$
    SELECT input_value ?& required_keys
       AND NOT EXISTS (
           SELECT 1
           FROM jsonb_object_keys(input_value) AS metadata_key
           WHERE NOT metadata_key = ANY (allowed_keys)
       );
$$;

CREATE FUNCTION "${flyway:defaultSchema}".oauth_audit_metadata_text_array_is_valid(
    input_value JSONB,
    metadata_key TEXT,
    allowed_values TEXT[],
    allow_empty BOOLEAN
)
RETURNS BOOLEAN
LANGUAGE sql
IMMUTABLE
STRICT
AS
$$
    SELECT CASE
        WHEN NOT input_value ? metadata_key THEN TRUE
        WHEN jsonb_typeof(input_value -> metadata_key) <> 'array' THEN FALSE
        ELSE (allow_empty OR jsonb_array_length(input_value -> metadata_key) > 0)
            AND NOT EXISTS (
                SELECT 1
                FROM jsonb_array_elements(input_value -> metadata_key) AS array_item(value)
                WHERE jsonb_typeof(array_item.value) <> 'string'
                   OR array_item.value #>> '{}' <> ALL (allowed_values)
            )
        END;
$$;

CREATE FUNCTION "${flyway:defaultSchema}".oauth_audit_metadata_uuid_array_is_valid(
    input_value JSONB,
    metadata_key TEXT
)
RETURNS BOOLEAN
LANGUAGE sql
IMMUTABLE
STRICT
AS
$$
    SELECT CASE
        WHEN NOT input_value ? metadata_key THEN TRUE
        WHEN jsonb_typeof(input_value -> metadata_key) <> 'array' THEN FALSE
        ELSE NOT EXISTS (
                SELECT 1
                FROM jsonb_array_elements(input_value -> metadata_key) AS array_item(value)
                WHERE jsonb_typeof(array_item.value) <> 'string'
                   OR array_item.value #>> '{}' !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            )
        END;
$$;

CREATE FUNCTION "${flyway:defaultSchema}".oauth_audit_metadata_positive_integer_is_valid(
    input_value JSONB,
    metadata_key TEXT
)
RETURNS BOOLEAN
LANGUAGE sql
IMMUTABLE
STRICT
AS
$$
    SELECT CASE
        WHEN NOT input_value ? metadata_key THEN TRUE
        WHEN jsonb_typeof(input_value -> metadata_key) <> 'number' THEN FALSE
        ELSE input_value ->> metadata_key ~ '^[1-9][0-9]*$'
        END;
$$;

CREATE FUNCTION "${flyway:defaultSchema}".oauth_audit_metadata_project_policy_is_valid(
    input_value JSONB,
    mode_key TEXT,
    project_ids_key TEXT
)
RETURNS BOOLEAN
LANGUAGE sql
IMMUTABLE
STRICT
AS
$$
    SELECT CASE
        WHEN NOT input_value ? mode_key THEN TRUE
        WHEN jsonb_typeof(input_value -> mode_key) <> 'string' THEN FALSE
        WHEN input_value ->> mode_key NOT IN ('AllProjects', 'SelectedProjects') THEN FALSE
        WHEN NOT input_value ? project_ids_key THEN FALSE
        WHEN jsonb_typeof(input_value -> project_ids_key) <> 'array' THEN FALSE
        WHEN NOT "${flyway:defaultSchema}".oauth_audit_metadata_uuid_array_is_valid(input_value, project_ids_key) THEN FALSE
        ELSE (
                (input_value ->> mode_key = 'AllProjects' AND jsonb_array_length(input_value -> project_ids_key) = 0)
                OR (input_value ->> mode_key = 'SelectedProjects' AND jsonb_array_length(input_value -> project_ids_key) > 0)
            )
        END;
$$;

CREATE FUNCTION "${flyway:defaultSchema}".oauth_audit_metadata_is_safe(
    input_value JSONB,
    audit_category TEXT,
    audit_event_type TEXT
)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS
$$
DECLARE
    fixed_scopes CONSTANT TEXT[] := ARRAY[
        'organization:read', 'users:read', 'clients:read', 'projects:read',
        'work-units:read', 'work-units:write', 'reports:read', 'reports:export'
    ];
    metadata_key TEXT;
BEGIN
    IF jsonb_typeof(input_value) <> 'object' THEN
        RETURN FALSE;
    END IF;

    FOREACH metadata_key IN ARRAY ARRAY['scopes', 'beforeScopes', 'afterScopes'] LOOP
        IF NOT "${flyway:defaultSchema}".oauth_audit_metadata_text_array_is_valid(input_value, metadata_key, fixed_scopes, FALSE) THEN
            RETURN FALSE;
        END IF;
    END LOOP;

    FOREACH metadata_key IN ARRAY ARRAY['projectIds', 'beforeProjectIds', 'afterProjectIds'] LOOP
        IF NOT "${flyway:defaultSchema}".oauth_audit_metadata_uuid_array_is_valid(input_value, metadata_key) THEN
            RETURN FALSE;
        END IF;
    END LOOP;

    FOREACH metadata_key IN ARRAY ARRAY[
        'version', 'policyVersion', 'secretVersion',
        'beforeVersion', 'afterVersion',
        'beforePolicyVersion', 'afterPolicyVersion',
        'beforeSecretVersion', 'afterSecretVersion'
    ] LOOP
        IF NOT "${flyway:defaultSchema}".oauth_audit_metadata_positive_integer_is_valid(input_value, metadata_key) THEN
            RETURN FALSE;
        END IF;
    END LOOP;

    IF NOT "${flyway:defaultSchema}".oauth_audit_metadata_project_policy_is_valid(input_value, 'projectMode', 'projectIds')
        OR NOT "${flyway:defaultSchema}".oauth_audit_metadata_project_policy_is_valid(input_value, 'beforeProjectMode', 'beforeProjectIds')
        OR NOT "${flyway:defaultSchema}".oauth_audit_metadata_project_policy_is_valid(input_value, 'afterProjectMode', 'afterProjectIds') THEN
        RETURN FALSE;
    END IF;

    IF audit_category = 'Management' AND audit_event_type = 'Create' THEN
        RETURN "${flyway:defaultSchema}".oauth_audit_metadata_has_keys(
            input_value,
            ARRAY['scopes', 'projectMode', 'projectIds', 'version', 'policyVersion', 'secretVersion'],
            ARRAY['scopes', 'projectMode', 'projectIds', 'version', 'policyVersion', 'secretVersion']
        );
    ELSIF audit_category = 'Management' AND audit_event_type = 'Update' THEN
        RETURN "${flyway:defaultSchema}".oauth_audit_metadata_has_keys(
            input_value,
            ARRAY[
                'beforeScopes', 'afterScopes', 'beforeProjectMode', 'afterProjectMode',
                'beforeProjectIds', 'afterProjectIds', 'beforeVersion', 'afterVersion',
                'beforePolicyVersion', 'afterPolicyVersion'
            ],
            ARRAY[
                'beforeScopes', 'afterScopes', 'beforeProjectMode', 'afterProjectMode',
                'beforeProjectIds', 'afterProjectIds', 'beforeVersion', 'afterVersion',
                'beforePolicyVersion', 'afterPolicyVersion'
            ]
        );
    ELSIF audit_category = 'Management' AND audit_event_type = 'Rotate' THEN
        RETURN "${flyway:defaultSchema}".oauth_audit_metadata_has_keys(
            input_value,
            ARRAY['beforeVersion', 'afterVersion', 'beforeSecretVersion', 'afterSecretVersion'],
            ARRAY['beforeVersion', 'afterVersion', 'beforeSecretVersion', 'afterSecretVersion']
        );
    ELSIF audit_category = 'Management' AND audit_event_type = 'Revoke' THEN
        RETURN "${flyway:defaultSchema}".oauth_audit_metadata_has_keys(
            input_value,
            ARRAY['beforeVersion', 'afterVersion', 'beforePolicyVersion', 'afterPolicyVersion'],
            ARRAY['beforeVersion', 'afterVersion', 'beforePolicyVersion', 'afterPolicyVersion']
        );
    ELSIF audit_category = 'Authentication' AND audit_event_type = 'TokenIssued' THEN
        RETURN "${flyway:defaultSchema}".oauth_audit_metadata_has_keys(
            input_value,
            ARRAY['scopes', 'projectMode', 'projectIds', 'policyVersion', 'secretVersion'],
            ARRAY['scopes', 'projectMode', 'projectIds', 'policyVersion', 'secretVersion']
        );
    ELSIF audit_category = 'Authentication' AND audit_event_type = 'InvalidClient' THEN
        RETURN "${flyway:defaultSchema}".oauth_audit_metadata_has_keys(input_value, ARRAY['failureCategory'], ARRAY['failureCategory'])
           AND jsonb_typeof(input_value -> 'failureCategory') = 'string'
           AND input_value ->> 'failureCategory' IN ('malformed-credentials', 'unknown-client', 'invalid-secret', 'revoked-client');
    ELSIF audit_category = 'Authentication' AND audit_event_type = 'InvalidScope' THEN
        RETURN "${flyway:defaultSchema}".oauth_audit_metadata_has_keys(input_value, ARRAY['failureCategory'], ARRAY['failureCategory'])
           AND jsonb_typeof(input_value -> 'failureCategory') = 'string'
           AND input_value ->> 'failureCategory' IN ('malformed-scope', 'unknown-scope', 'ungranted-scope');
    ELSIF audit_category = 'Authentication' AND audit_event_type = 'RateLimit' THEN
        RETURN "${flyway:defaultSchema}".oauth_audit_metadata_has_keys(input_value, ARRAY['failureCategory'], ARRAY['failureCategory'])
           AND jsonb_typeof(input_value -> 'failureCategory') = 'string'
           AND input_value ->> 'failureCategory' IN ('ip-rate-limit', 'client-rate-limit');
    END IF;

    RETURN FALSE;
END;
$$;

CREATE TABLE "${flyway:defaultSchema}".oauth_audit_event
(
    id              UUID        NOT NULL PRIMARY KEY,
    occurred_at     TIMESTAMPTZ NOT NULL,
    category        TEXT        NOT NULL,
    organization_id UUID        NULL REFERENCES "${flyway:defaultSchema}".organization (id) ON DELETE NO ACTION,
    oauth_client_id UUID        NULL REFERENCES "${flyway:defaultSchema}".oauth_client (id) ON DELETE NO ACTION,
    actor_type      TEXT        NOT NULL,
    actor_user_id   UUID        NULL REFERENCES "${flyway:defaultSchema}"."user" (id) ON DELETE NO ACTION,
    event_type      TEXT        NOT NULL,
    outcome         TEXT        NOT NULL,
    correlation_id  TEXT        NULL,
    token_jti       UUID        NULL,
    source_ip       INET        NULL,
    metadata        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT oauth_audit_event_category_check CHECK (category IN ('Management', 'Authentication')),
    CONSTRAINT oauth_audit_event_actor_type_check CHECK (actor_type IN ('Human', 'ServiceAccount', 'Anonymous', 'System')),
    CONSTRAINT oauth_audit_event_outcome_check CHECK (outcome IN ('Success', 'Failure')),
    CONSTRAINT oauth_audit_event_category_type_check CHECK (
        (category = 'Management' AND event_type IN ('Create', 'Update', 'Rotate', 'Revoke') AND outcome = 'Success')
        OR (category = 'Authentication' AND event_type = 'TokenIssued' AND outcome = 'Success')
        OR (category = 'Authentication' AND event_type IN ('InvalidClient', 'InvalidScope', 'RateLimit') AND outcome = 'Failure')
    ),
    CONSTRAINT oauth_audit_event_correlation_id_check CHECK (correlation_id IS NULL OR btrim(correlation_id) <> ''),
    CONSTRAINT oauth_audit_event_metadata_object_check CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT oauth_audit_event_metadata_schema_check
        CHECK ("${flyway:defaultSchema}".oauth_audit_metadata_is_safe(metadata, category, event_type))
);

CREATE INDEX oauth_audit_event_retention_idx
    ON "${flyway:defaultSchema}".oauth_audit_event (occurred_at, id) WHERE category = 'Authentication';
CREATE INDEX oauth_audit_event_organization_idx
    ON "${flyway:defaultSchema}".oauth_audit_event (organization_id, occurred_at DESC, id DESC) WHERE organization_id IS NOT NULL;
CREATE INDEX oauth_audit_event_client_idx
    ON "${flyway:defaultSchema}".oauth_audit_event (oauth_client_id, occurred_at DESC, id DESC) WHERE oauth_client_id IS NOT NULL;
CREATE INDEX oauth_audit_event_actor_user_id_idx
    ON "${flyway:defaultSchema}".oauth_audit_event (actor_user_id) WHERE actor_user_id IS NOT NULL;

CREATE TABLE "${flyway:defaultSchema}".oauth_rate_limit_bucket
(
    dimension        TEXT             NOT NULL,
    bucket_key       TEXT             NOT NULL,
    available_tokens DOUBLE PRECISION NOT NULL,
    last_refilled_at TIMESTAMPTZ      NOT NULL,
    expires_at       TIMESTAMPTZ      NOT NULL,
    PRIMARY KEY (dimension, bucket_key),
    CONSTRAINT oauth_rate_limit_bucket_dimension_check CHECK (dimension IN ('Ip', 'ClientFailure')),
    CONSTRAINT oauth_rate_limit_bucket_key_check CHECK (btrim(bucket_key) <> ''),
    CONSTRAINT oauth_rate_limit_bucket_tokens_check CHECK (available_tokens >= 0),
    CONSTRAINT oauth_rate_limit_bucket_expiry_check CHECK (expires_at >= last_refilled_at)
);

CREATE INDEX oauth_rate_limit_bucket_expiry_idx ON "${flyway:defaultSchema}".oauth_rate_limit_bucket (expires_at, dimension, bucket_key);
CREATE INDEX organization_user_user_id_idx ON "${flyway:defaultSchema}".organization_user (user_id);

CREATE FUNCTION "${flyway:defaultSchema}".oauth_protect_audit_event_row()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'OAuth audit events are immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'oauth_audit_event_immutable_check';
    END IF;

    IF OLD.category <> 'Authentication'
        OR OLD.occurred_at >= statement_timestamp() - INTERVAL '90 days' THEN
        RAISE EXCEPTION 'Only Authentication audit events older than 90 days may be deleted'
            USING ERRCODE = '23514', CONSTRAINT = 'oauth_audit_event_retention_check';
    END IF;

    RETURN OLD;
END;
$$;

CREATE FUNCTION "${flyway:defaultSchema}".oauth_validate_audit_delete_batch()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
BEGIN
    IF (SELECT count(*) FROM deleted_oauth_audit_events) > 1000 THEN
        RAISE EXCEPTION 'OAuth audit cleanup may delete at most 1000 events per statement'
            USING ERRCODE = '23514', CONSTRAINT = 'oauth_audit_event_cleanup_batch_check';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION "${flyway:defaultSchema}".oauth_protect_revoked_client()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'OAuth client records must be retained'
            USING ERRCODE = '23514', CONSTRAINT = 'oauth_client_revoked_terminal_check';
    END IF;
    IF OLD.status = 'Revoked' THEN
        RAISE EXCEPTION 'A Revoked OAuth client is terminal'
            USING ERRCODE = '23514', CONSTRAINT = 'oauth_client_revoked_terminal_check';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION "${flyway:defaultSchema}".oauth_validate_service_account_shape(target_user_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS
$$
DECLARE
    target_account_type TEXT;
    target_is_super_admin BOOLEAN;
    client_count BIGINT;
    membership_count BIGINT;
    matching_membership_count BIGINT;
BEGIN
    SELECT account_type, is_super_admin
    INTO target_account_type, target_is_super_admin
    FROM "${flyway:defaultSchema}"."user"
    WHERE id = target_user_id;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    SELECT count(*) INTO client_count
    FROM "${flyway:defaultSchema}".oauth_client
    WHERE service_account_user_id = target_user_id;

    IF target_account_type = 'Human' THEN
        IF client_count <> 0 THEN
            RAISE EXCEPTION 'A Human user cannot be attached to an OAuth client'
                USING ERRCODE = '23514', CONSTRAINT = 'oauth_service_account_shape_check';
        END IF;
        RETURN;
    END IF;

    -- Human membership changes must not take a user-row lock: existing report
    -- transactions lock Human users before their membership rows. Technical
    -- identities use the user row as the common serialization point instead.
    PERFORM 1
    FROM "${flyway:defaultSchema}"."user"
    WHERE id = target_user_id
    FOR UPDATE;

    SELECT count(*) INTO membership_count
    FROM "${flyway:defaultSchema}".organization_user
    WHERE user_id = target_user_id;

    SELECT count(*) INTO matching_membership_count
    FROM "${flyway:defaultSchema}".oauth_client client_row
    JOIN "${flyway:defaultSchema}".organization_user membership
      ON membership.user_id = client_row.service_account_user_id
     AND membership.organization_id = client_row.organization_id
    WHERE client_row.service_account_user_id = target_user_id
      AND cardinality(membership.roles) = 0;

    IF target_is_super_admin OR client_count <> 1 OR membership_count <> 1 OR matching_membership_count <> 1 THEN
        RAISE EXCEPTION 'A ServiceAccount must have one OAuth client and one empty-role membership in the same organization'
            USING ERRCODE = '23514', CONSTRAINT = 'oauth_service_account_shape_check';
    END IF;
END;
$$;

CREATE FUNCTION "${flyway:defaultSchema}".oauth_validate_service_account_trigger()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
BEGIN
    IF TG_TABLE_NAME = 'user' THEN
        PERFORM "${flyway:defaultSchema}".oauth_validate_service_account_shape(COALESCE(NEW.id, OLD.id));
    ELSIF TG_TABLE_NAME = 'oauth_client' THEN
        IF TG_OP <> 'INSERT' AND OLD.service_account_user_id IS DISTINCT FROM NEW.service_account_user_id THEN
            PERFORM "${flyway:defaultSchema}".oauth_validate_service_account_shape(OLD.service_account_user_id);
        END IF;
        PERFORM "${flyway:defaultSchema}".oauth_validate_service_account_shape(COALESCE(NEW.service_account_user_id, OLD.service_account_user_id));
    ELSE
        IF TG_OP <> 'INSERT' AND OLD.user_id IS DISTINCT FROM NEW.user_id THEN
            PERFORM "${flyway:defaultSchema}".oauth_validate_service_account_shape(OLD.user_id);
        END IF;
        PERFORM "${flyway:defaultSchema}".oauth_validate_service_account_shape(COALESCE(NEW.user_id, OLD.user_id));
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION "${flyway:defaultSchema}".oauth_reject_account_type_swap()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
BEGIN
    IF OLD.account_type IS DISTINCT FROM NEW.account_type THEN
        RAISE EXCEPTION 'User account type is immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'oauth_user_account_type_immutable_check';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION "${flyway:defaultSchema}".oauth_reject_service_account_login_artifact()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
DECLARE
    target_account_type TEXT;
BEGIN
    SELECT account_type INTO target_account_type
    FROM "${flyway:defaultSchema}"."user"
    WHERE id = NEW.user_id
    FOR UPDATE;

    IF target_account_type = 'ServiceAccount' THEN
        IF TG_TABLE_NAME = 'user_identity' THEN
            RAISE EXCEPTION 'A ServiceAccount cannot have an identity'
                USING ERRCODE = '23514', CONSTRAINT = 'oauth_service_account_identity_check';
        ELSE
            RAISE EXCEPTION 'A ServiceAccount cannot have a refresh token'
                USING ERRCODE = '23514', CONSTRAINT = 'oauth_service_account_refresh_token_check';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION "${flyway:defaultSchema}".oauth_validate_client_policy(target_client_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS
$$
DECLARE
    target_mode TEXT;
    target_organization_id UUID;
    scope_count BIGINT;
    project_count BIGINT;
    foreign_project_count BIGINT;
BEGIN
    SELECT project_access_mode, organization_id
    INTO target_mode, target_organization_id
    FROM "${flyway:defaultSchema}".oauth_client
    WHERE id = target_client_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    SELECT count(*) INTO scope_count
    FROM "${flyway:defaultSchema}".oauth_client_scope
    WHERE oauth_client_id = target_client_id;

    IF scope_count = 0 THEN
        RAISE EXCEPTION 'An OAuth client must have at least one scope'
            USING ERRCODE = '23514', CONSTRAINT = 'oauth_client_scope_required_check';
    END IF;

    SELECT count(*), count(*) FILTER (WHERE project.organization_id <> target_organization_id)
    INTO project_count, foreign_project_count
    FROM "${flyway:defaultSchema}".oauth_client_project client_project
    JOIN "${flyway:defaultSchema}".project project ON project.id = client_project.project_id
    WHERE client_project.oauth_client_id = target_client_id;

    IF (target_mode = 'AllProjects' AND project_count <> 0)
        OR (target_mode = 'SelectedProjects' AND project_count = 0)
        OR foreign_project_count <> 0 THEN
        RAISE EXCEPTION 'OAuth client project rows must match its access mode and organization'
            USING ERRCODE = '23514', CONSTRAINT = 'oauth_client_project_policy_check';
    END IF;
END;
$$;

CREATE FUNCTION "${flyway:defaultSchema}".oauth_validate_client_policy_trigger()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
BEGIN
    IF TG_TABLE_NAME = 'oauth_client' THEN
        PERFORM "${flyway:defaultSchema}".oauth_validate_client_policy(COALESCE(NEW.id, OLD.id));
    ELSE
        IF TG_OP <> 'INSERT' AND OLD.oauth_client_id IS DISTINCT FROM NEW.oauth_client_id THEN
            PERFORM "${flyway:defaultSchema}".oauth_validate_client_policy(OLD.oauth_client_id);
        END IF;
        PERFORM "${flyway:defaultSchema}".oauth_validate_client_policy(COALESCE(NEW.oauth_client_id, OLD.oauth_client_id));
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION "${flyway:defaultSchema}".oauth_validate_project_organization_trigger()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
DECLARE
    referenced_client_id UUID;
BEGIN
    FOR referenced_client_id IN
        SELECT oauth_client_id
        FROM "${flyway:defaultSchema}".oauth_client_project
        WHERE project_id = NEW.id
    LOOP
        PERFORM "${flyway:defaultSchema}".oauth_validate_client_policy(referenced_client_id);
    END LOOP;
    RETURN NULL;
END;
$$;

CREATE FUNCTION "${flyway:defaultSchema}".oauth_validate_human_client_actors_trigger()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM "${flyway:defaultSchema}"."user" actor
        WHERE actor.id IN (NEW.created_by, NEW.modified_by, NEW.secret_rotated_by, NEW.revoked_by)
          AND actor.account_type <> 'Human'
    ) THEN
        RAISE EXCEPTION 'OAuth client audit actors must be Human users'
            USING ERRCODE = '23514', CONSTRAINT = 'oauth_client_human_actor_check';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER oauth_user_account_type_immutable_trigger
AFTER UPDATE OF account_type ON "${flyway:defaultSchema}"."user"
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".oauth_reject_account_type_swap();

CREATE CONSTRAINT TRIGGER oauth_user_service_account_shape_trigger
AFTER INSERT OR UPDATE ON "${flyway:defaultSchema}"."user"
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".oauth_validate_service_account_trigger();

CREATE CONSTRAINT TRIGGER oauth_client_service_account_shape_trigger
AFTER INSERT OR UPDATE OR DELETE ON "${flyway:defaultSchema}".oauth_client
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".oauth_validate_service_account_trigger();

CREATE CONSTRAINT TRIGGER oauth_organization_user_service_account_shape_trigger
AFTER INSERT OR UPDATE OR DELETE ON "${flyway:defaultSchema}".organization_user
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".oauth_validate_service_account_trigger();

CREATE CONSTRAINT TRIGGER oauth_user_identity_service_account_trigger
AFTER INSERT OR UPDATE ON "${flyway:defaultSchema}".user_identity
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".oauth_reject_service_account_login_artifact();

CREATE CONSTRAINT TRIGGER oauth_refresh_token_service_account_trigger
AFTER INSERT OR UPDATE ON "${flyway:defaultSchema}".refresh_token
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".oauth_reject_service_account_login_artifact();

CREATE CONSTRAINT TRIGGER oauth_client_policy_trigger
AFTER INSERT OR UPDATE ON "${flyway:defaultSchema}".oauth_client
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".oauth_validate_client_policy_trigger();

CREATE CONSTRAINT TRIGGER oauth_client_scope_policy_trigger
AFTER INSERT OR UPDATE OR DELETE ON "${flyway:defaultSchema}".oauth_client_scope
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".oauth_validate_client_policy_trigger();

CREATE CONSTRAINT TRIGGER oauth_client_project_policy_trigger
AFTER INSERT OR UPDATE OR DELETE ON "${flyway:defaultSchema}".oauth_client_project
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".oauth_validate_client_policy_trigger();

CREATE CONSTRAINT TRIGGER oauth_project_organization_policy_trigger
AFTER UPDATE OF organization_id ON "${flyway:defaultSchema}".project
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".oauth_validate_project_organization_trigger();

CREATE CONSTRAINT TRIGGER oauth_client_human_actor_trigger
AFTER INSERT OR UPDATE ON "${flyway:defaultSchema}".oauth_client
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".oauth_validate_human_client_actors_trigger();

CREATE TRIGGER oauth_audit_event_row_protection_trigger
BEFORE UPDATE OR DELETE ON "${flyway:defaultSchema}".oauth_audit_event
FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".oauth_protect_audit_event_row();

CREATE TRIGGER oauth_audit_event_cleanup_batch_trigger
AFTER DELETE ON "${flyway:defaultSchema}".oauth_audit_event
REFERENCING OLD TABLE AS deleted_oauth_audit_events
FOR EACH STATEMENT EXECUTE FUNCTION "${flyway:defaultSchema}".oauth_validate_audit_delete_batch();

CREATE TRIGGER oauth_client_revoked_terminal_trigger
BEFORE UPDATE OR DELETE ON "${flyway:defaultSchema}".oauth_client
FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".oauth_protect_revoked_client();
