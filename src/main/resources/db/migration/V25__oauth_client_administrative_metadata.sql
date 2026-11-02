ALTER TABLE public.oauth_client
    ADD COLUMN expires_at TIMESTAMPTZ NULL,
    ADD COLUMN last_used_at TIMESTAMPTZ NULL,
    ADD CONSTRAINT oauth_client_expires_at_after_created_at_check
        CHECK (expires_at IS NULL OR expires_at > created_at),
    ADD CONSTRAINT oauth_client_last_used_at_not_before_created_at_check
        CHECK (last_used_at IS NULL OR last_used_at >= created_at);

CREATE INDEX oauth_client_active_expires_at_idx
    ON public.oauth_client (expires_at)
    WHERE status = 'Active' AND revoked_at IS NULL AND expires_at IS NOT NULL;

CREATE FUNCTION public.oauth_audit_metadata_nullable_timestamp_is_valid(
    input_value JSONB,
    metadata_key TEXT
)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS
$$
BEGIN
    IF NOT input_value ? metadata_key OR jsonb_typeof(input_value -> metadata_key) = 'null' THEN
        RETURN TRUE;
    END IF;
    IF jsonb_typeof(input_value -> metadata_key) <> 'string'
        OR input_value ->> metadata_key !~ '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?(Z|[+-]\d{2}:\d{2})$' THEN
        RETURN FALSE;
    END IF;

    PERFORM (input_value ->> metadata_key)::TIMESTAMPTZ;
    RETURN TRUE;
EXCEPTION WHEN datetime_field_overflow OR invalid_datetime_format OR invalid_time_zone_displacement_value THEN
    RETURN FALSE;
END;
$$;

CREATE OR REPLACE FUNCTION public.oauth_audit_metadata_is_safe(
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
        IF NOT public.oauth_audit_metadata_text_array_is_valid(input_value, metadata_key, fixed_scopes, FALSE) THEN
            RETURN FALSE;
        END IF;
    END LOOP;

    FOREACH metadata_key IN ARRAY ARRAY['projectIds', 'beforeProjectIds', 'afterProjectIds'] LOOP
        IF NOT public.oauth_audit_metadata_uuid_array_is_valid(input_value, metadata_key) THEN
            RETURN FALSE;
        END IF;
    END LOOP;

    FOREACH metadata_key IN ARRAY ARRAY[
        'version', 'policyVersion', 'secretVersion',
        'beforeVersion', 'afterVersion',
        'beforePolicyVersion', 'afterPolicyVersion',
        'beforeSecretVersion', 'afterSecretVersion'
    ] LOOP
        IF NOT public.oauth_audit_metadata_positive_integer_is_valid(input_value, metadata_key) THEN
            RETURN FALSE;
        END IF;
    END LOOP;

    FOREACH metadata_key IN ARRAY ARRAY['expiresAt', 'beforeExpiresAt', 'afterExpiresAt'] LOOP
        IF NOT public.oauth_audit_metadata_nullable_timestamp_is_valid(input_value, metadata_key) THEN
            RETURN FALSE;
        END IF;
    END LOOP;

    IF NOT public.oauth_audit_metadata_project_policy_is_valid(input_value, 'projectMode', 'projectIds')
        OR NOT public.oauth_audit_metadata_project_policy_is_valid(input_value, 'beforeProjectMode', 'beforeProjectIds')
        OR NOT public.oauth_audit_metadata_project_policy_is_valid(input_value, 'afterProjectMode', 'afterProjectIds') THEN
        RETURN FALSE;
    END IF;

    IF audit_category = 'Management' AND audit_event_type = 'Create' THEN
        RETURN public.oauth_audit_metadata_has_keys(
            input_value,
            ARRAY['scopes', 'projectMode', 'projectIds', 'version', 'policyVersion', 'secretVersion', 'expiresAt'],
            ARRAY['scopes', 'projectMode', 'projectIds', 'version', 'policyVersion', 'secretVersion']
        );
    ELSIF audit_category = 'Management' AND audit_event_type = 'Update' THEN
        RETURN public.oauth_audit_metadata_has_keys(
            input_value,
            ARRAY[
                'beforeScopes', 'afterScopes', 'beforeProjectMode', 'afterProjectMode',
                'beforeProjectIds', 'afterProjectIds', 'beforeVersion', 'afterVersion',
                'beforePolicyVersion', 'afterPolicyVersion', 'beforeExpiresAt', 'afterExpiresAt'
            ],
            ARRAY[
                'beforeScopes', 'afterScopes', 'beforeProjectMode', 'afterProjectMode',
                'beforeProjectIds', 'afterProjectIds', 'beforeVersion', 'afterVersion',
                'beforePolicyVersion', 'afterPolicyVersion'
            ]
        );
    ELSIF audit_category = 'Management' AND audit_event_type = 'Rotate' THEN
        RETURN public.oauth_audit_metadata_has_keys(
            input_value,
            ARRAY['beforeVersion', 'afterVersion', 'beforeSecretVersion', 'afterSecretVersion'],
            ARRAY['beforeVersion', 'afterVersion', 'beforeSecretVersion', 'afterSecretVersion']
        );
    ELSIF audit_category = 'Management' AND audit_event_type = 'Revoke' THEN
        RETURN public.oauth_audit_metadata_has_keys(
            input_value,
            ARRAY['beforeVersion', 'afterVersion', 'beforePolicyVersion', 'afterPolicyVersion'],
            ARRAY['beforeVersion', 'afterVersion', 'beforePolicyVersion', 'afterPolicyVersion']
        );
    ELSIF audit_category = 'Authentication' AND audit_event_type = 'TokenIssued' THEN
        RETURN public.oauth_audit_metadata_has_keys(
            input_value,
            ARRAY['scopes', 'projectMode', 'projectIds', 'policyVersion', 'secretVersion'],
            ARRAY['scopes', 'projectMode', 'projectIds', 'policyVersion', 'secretVersion']
        );
    ELSIF audit_category = 'Authentication' AND audit_event_type = 'InvalidClient' THEN
        RETURN public.oauth_audit_metadata_has_keys(input_value, ARRAY['failureCategory'], ARRAY['failureCategory'])
           AND jsonb_typeof(input_value -> 'failureCategory') = 'string'
           AND input_value ->> 'failureCategory' IN (
               'malformed-credentials', 'unknown-client', 'invalid-secret', 'revoked-client', 'expired-client'
           );
    ELSIF audit_category = 'Authentication' AND audit_event_type = 'InvalidScope' THEN
        RETURN public.oauth_audit_metadata_has_keys(input_value, ARRAY['failureCategory'], ARRAY['failureCategory'])
           AND jsonb_typeof(input_value -> 'failureCategory') = 'string'
           AND input_value ->> 'failureCategory' IN ('malformed-scope', 'unknown-scope', 'ungranted-scope');
    ELSIF audit_category = 'Authentication' AND audit_event_type = 'RateLimit' THEN
        RETURN public.oauth_audit_metadata_has_keys(input_value, ARRAY['failureCategory'], ARRAY['failureCategory'])
           AND jsonb_typeof(input_value -> 'failureCategory') = 'string'
           AND input_value ->> 'failureCategory' IN ('ip-rate-limit', 'client-rate-limit');
    END IF;

    RETURN FALSE;
END;
$$;
