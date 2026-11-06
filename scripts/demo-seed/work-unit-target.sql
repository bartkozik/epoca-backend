CREATE TEMP TABLE demo_seed_reporting_dates
(
    date_order INTEGER PRIMARY KEY,
    reporting_date DATE NOT NULL UNIQUE,
    is_weekend BOOLEAN NOT NULL
) ON COMMIT DROP;

WITH calendar AS (
    SELECT day::DATE AS reporting_date,
           EXTRACT(isodow FROM day)::INTEGER AS iso_day,
           pg_catalog.row_number() OVER (
               PARTITION BY (EXTRACT(isodow FROM day)::INTEGER IN (6, 7))
               ORDER BY day
           )::INTEGER AS day_kind_order
    FROM demo_seed_parameters parameters
    CROSS JOIN pg_catalog.generate_series(
        parameters.seed_today - 84,
        parameters.seed_today - 1,
        INTERVAL '1 day'
    ) AS generated(day)
), selected AS (
    SELECT reporting_date,
           iso_day IN (6, 7) AS is_weekend
    FROM calendar
    WHERE iso_day BETWEEN 1 AND 5
       OR (iso_day IN (6, 7) AND day_kind_order % 4 = 0)
)
INSERT INTO demo_seed_reporting_dates (date_order, reporting_date, is_weekend)
SELECT pg_catalog.row_number() OVER (ORDER BY reporting_date)::INTEGER,
       reporting_date,
       is_weekend
FROM selected;

CREATE TEMP TABLE demo_seed_active_work_user
ON COMMIT DROP
AS
SELECT pg_catalog.row_number() OVER (ORDER BY target.profile_order)::INTEGER AS user_order,
       pg_catalog.count(*) OVER ()::INTEGER AS user_count,
       target.profile_order,
       catalog.logical_key AS user_key,
       target.user_id
FROM demo_seed_target_member target
JOIN demo_seed_user_catalog catalog ON catalog.profile_order = target.profile_order;

CREATE TEMP TABLE demo_seed_active_work_membership
ON COMMIT DROP
AS
SELECT target.*,
       project.client_id,
       project.name AS project_name,
       project.task_prefix,
       pg_catalog.row_number() OVER (
           PARTITION BY target.user_id
           ORDER BY target.project_order
       )::INTEGER AS membership_order,
       pg_catalog.count(*) OVER (
           PARTITION BY target.user_id
       )::INTEGER AS membership_count
FROM demo_seed_target_project_user target
JOIN demo_seed_project_catalog project ON project.project_id = target.project_id
WHERE target.project_status = 'active';

CREATE TEMP TABLE demo_seed_archived_work_membership
ON COMMIT DROP
AS
SELECT target.*,
       project.client_id,
       project.name AS project_name,
       project.task_prefix,
       pg_catalog.row_number() OVER (
           PARTITION BY target.project_id
           ORDER BY target.profile_order
       )::INTEGER AS membership_order,
       pg_catalog.count(*) OVER (
           PARTITION BY target.project_id
       )::INTEGER AS membership_count
FROM demo_seed_target_project_user target
JOIN demo_seed_project_catalog project ON project.project_id = target.project_id
WHERE target.project_status = 'archived';

CREATE TEMP TABLE demo_seed_target_work_unit
(
    work_unit_id UUID PRIMARY KEY,
    generator_version INTEGER NOT NULL,
    logical_slot INTEGER NOT NULL CHECK (logical_slot BETWEEN 1 AND 64),
    source_kind TEXT NOT NULL CHECK (source_kind IN ('active-worklog', 'active-note', 'archived-history')),
    source_number INTEGER NOT NULL,
    user_key TEXT NOT NULL,
    user_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    project_id UUID NOT NULL,
    client_id UUID NOT NULL,
    work_date DATE NOT NULL,
    duration INTERVAL NOT NULL,
    entry_type TEXT NOT NULL CHECK (entry_type IN ('Worklog', 'Note')),
    description TEXT NOT NULL,
    task_id TEXT NULL,
    state TEXT NOT NULL CHECK (state IN ('Active', 'Archived')),
    created_at TIMESTAMPTZ NOT NULL,
    modified_at TIMESTAMPTZ NOT NULL,
    archived_at TIMESTAMPTZ NULL,
    UNIQUE (user_id, work_date, logical_slot)
) ON COMMIT DROP;

WITH worklog_number AS (
    SELECT source_number,
           (source_number % work_user.user_count) + 1 AS user_order,
           (source_number / work_user.user_count)::INTEGER AS user_occurrence
    FROM pg_catalog.generate_series(0, 1199) AS generated(source_number)
    CROSS JOIN (SELECT user_count FROM demo_seed_active_work_user LIMIT 1) work_user
), worklog_source AS (
    SELECT 'active-worklog'::TEXT AS source_kind,
           number.source_number,
           work_user.user_key,
           work_user.user_id,
           membership.project_id,
           membership.client_id,
           membership.project_name,
           membership.task_prefix,
           reporting_date.reporting_date AS work_date,
           pg_catalog.make_interval(mins => 15 * (1 + ((number.source_number * 7) % 16))) AS duration,
           'Worklog'::TEXT AS entry_type,
           (ARRAY[
               'Discovery workshop',
               'Implementation and pairing',
               'Code review',
               'Regression testing',
               'Data analysis',
               'Client sync',
               'Release preparation',
               'Architecture notes',
               'Accessibility review',
               'Performance improvements'
           ]::TEXT[])[(number.source_number % 10) + 1] || ' — ' || membership.project_name AS description,
           membership.task_prefix || '-' || (100 + (number.source_number % 73))::TEXT AS task_id,
           'Active'::TEXT AS state,
           NULL::TIMESTAMPTZ AS archived_at
    FROM worklog_number number
    JOIN demo_seed_active_work_user work_user ON work_user.user_order = number.user_order
    JOIN demo_seed_reporting_dates reporting_date
      ON reporting_date.date_order = ((number.user_occurrence * 17) % 66) + 1
    JOIN demo_seed_active_work_membership membership
      ON membership.user_id = work_user.user_id
     AND membership.membership_order =
         ((number.user_occurrence + work_user.user_order - 1) % membership.membership_count) + 1
), note_number AS (
    SELECT source_number,
           ((source_number + 7) % work_user.user_count) + 1 AS user_order,
           (source_number / work_user.user_count)::INTEGER AS user_occurrence
    FROM pg_catalog.generate_series(0, 99) AS generated(source_number)
    CROSS JOIN (SELECT user_count FROM demo_seed_active_work_user LIMIT 1) work_user
), note_source AS (
    SELECT 'active-note'::TEXT AS source_kind,
           number.source_number,
           work_user.user_key,
           work_user.user_id,
           membership.project_id,
           membership.client_id,
           membership.project_name,
           membership.task_prefix,
           reporting_date.reporting_date AS work_date,
           INTERVAL '0 seconds' AS duration,
           'Note'::TEXT AS entry_type,
           (ARRAY[
               'Decyzja zakresowa potwierdzona z zespołem',
               'Blocker integracji wymaga odpowiedzi partnera',
               'Planowana nieobecność uwzględniona w harmonogramie',
               'Client feedback added to the next iteration',
               'Ryzyko release omówione i przypisane',
               'Discovery follow-up zaplanowany na kolejny tydzień'
           ]::TEXT[])[(number.source_number % 6) + 1] || ' — ' || membership.project_name AS description,
           NULL::TEXT AS task_id,
           'Active'::TEXT AS state,
           NULL::TIMESTAMPTZ AS archived_at
    FROM note_number number
    JOIN demo_seed_active_work_user work_user ON work_user.user_order = number.user_order
    JOIN demo_seed_reporting_dates reporting_date
      ON reporting_date.date_order = (((number.user_occurrence * 17) + 11) % 66) + 1
    JOIN demo_seed_active_work_membership membership
      ON membership.user_id = work_user.user_id
     AND membership.membership_order =
         ((number.user_occurrence + work_user.user_order + 10) % membership.membership_count) + 1
), archived_project AS (
    SELECT project_id,
           pg_catalog.row_number() OVER (ORDER BY project_order)::INTEGER AS archived_project_order
    FROM demo_seed_project_catalog
    WHERE catalog_state = 'active'
      AND status = 'archived'
), history_number AS (
    SELECT source_number,
           (source_number % 3) + 1 AS archived_project_order,
           (source_number / 3)::INTEGER AS project_occurrence
    FROM pg_catalog.generate_series(0, 59) AS generated(source_number)
), history_source AS (
    SELECT 'archived-history'::TEXT AS source_kind,
           1200 + number.source_number AS source_number,
           membership.user_key,
           membership.user_id,
           membership.project_id,
           membership.client_id,
           membership.project_name,
           membership.task_prefix,
           (parameters.seed_today - 45 + (number.source_number % 15))::DATE AS work_date,
           pg_catalog.make_interval(mins => 15 * (1 + (((1200 + number.source_number) * 7) % 16))) AS duration,
           'Worklog'::TEXT AS entry_type,
           'Historical delivery wrap-up — ' || membership.project_name AS description,
           membership.task_prefix || '-' || (900 + (number.source_number % 73))::TEXT AS task_id,
           'Archived'::TEXT AS state,
           (parameters.seed_today - 30)::TIMESTAMP AT TIME ZONE 'UTC' AS archived_at
    FROM history_number number
    JOIN archived_project project ON project.archived_project_order = number.archived_project_order
    JOIN demo_seed_archived_work_membership membership
      ON membership.project_id = project.project_id
     AND membership.membership_order = (number.project_occurrence % membership.membership_count) + 1
    CROSS JOIN demo_seed_parameters parameters
), combined AS (
    SELECT * FROM worklog_source
    UNION ALL
    SELECT * FROM note_source
    UNION ALL
    SELECT * FROM history_source
), slotted AS (
    SELECT combined.*,
           pg_catalog.row_number() OVER (
               PARTITION BY combined.user_key, combined.work_date
               ORDER BY combined.entry_type, combined.source_number, combined.source_kind
           )::INTEGER AS logical_slot
    FROM combined
)
INSERT INTO demo_seed_target_work_unit (
    work_unit_id,
    generator_version,
    logical_slot,
    source_kind,
    source_number,
    user_key,
    user_id,
    organization_id,
    project_id,
    client_id,
    work_date,
    duration,
    entry_type,
    description,
    task_id,
    state,
    created_at,
    modified_at,
    archived_at
)
SELECT public.uuid_generate_v5(
           context.namespace_uuid,
           'work-unit:v1:' || slotted.user_key || ':' || slotted.work_date::TEXT || ':' || slotted.logical_slot::TEXT
       ),
       1,
       slotted.logical_slot,
       slotted.source_kind,
       slotted.source_number,
       slotted.user_key,
       slotted.user_id,
       organization.organization_id,
       slotted.project_id,
       slotted.client_id,
       slotted.work_date,
       slotted.duration,
       slotted.entry_type,
       slotted.description,
       slotted.task_id,
       slotted.state,
       (slotted.work_date::TIMESTAMP + INTERVAL '9 hours'
           + pg_catalog.make_interval(mins => slotted.logical_slot)) AT TIME ZONE 'UTC',
       (slotted.work_date::TIMESTAMP + INTERVAL '9 hours'
           + pg_catalog.make_interval(mins => slotted.logical_slot)) AT TIME ZONE 'UTC',
       slotted.archived_at
FROM slotted
CROSS JOIN demo_seed_catalog_context context
CROSS JOIN demo_seed_organization_context organization;
