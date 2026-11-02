-- Precondition: this catalog is included inside an already-open transaction by demo seed or verification SQL.
-- It materializes only deterministic temporary catalog data and never writes persistent application tables.

\ir project-cleanup-catalog.sql

DROP TABLE IF EXISTS pg_temp.demo_seed_project_user_catalog;
DROP TABLE IF EXISTS pg_temp.demo_seed_project_catalog;
DROP TABLE IF EXISTS pg_temp.demo_seed_client_catalog;
DROP TABLE IF EXISTS pg_temp.demo_seed_user_catalog;
DROP TABLE IF EXISTS pg_temp.demo_seed_generator_catalog;
DROP TABLE IF EXISTS pg_temp.demo_seed_catalog_context;

CREATE TEMP TABLE demo_seed_catalog_context
(
    namespace_uuid   UUID    NOT NULL,
    organization_key TEXT   NOT NULL UNIQUE,
    organization_id UUID    NOT NULL UNIQUE,
    catalog_version INTEGER NOT NULL CHECK (catalog_version = 1)
) ON COMMIT DROP;

INSERT INTO demo_seed_catalog_context (
    namespace_uuid,
    organization_key,
    organization_id,
    catalog_version
)
VALUES (
    '2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::UUID,
    'iteratorstesting',
    public.uuid_generate_v5(
        '2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::UUID,
        'organization:iteratorstesting'
    ),
    1
);

CREATE TEMP TABLE demo_seed_user_catalog
(
    profile_order   INTEGER PRIMARY KEY,
    logical_key     TEXT    NOT NULL UNIQUE,
    user_id         UUID    NOT NULL UNIQUE,
    identity_id     UUID    NOT NULL UNIQUE,
    membership_id   UUID    NOT NULL UNIQUE,
    first_name      TEXT    NOT NULL,
    last_name       TEXT    NOT NULL,
    email           TEXT    NOT NULL UNIQUE,
    password_hash   TEXT    NOT NULL CHECK (
        pg_catalog.length(password_hash) = 60
        AND pg_catalog.left(password_hash, 7) IN ('$2a$12$', '$2b$12$', '$2y$12$')
    ),
    color           TEXT    NOT NULL,
    reserved_role   TEXT    NULL CHECK (reserved_role IN ('Owner', 'Manager')),
    is_super_admin  BOOLEAN NOT NULL,
    catalog_state   TEXT    NOT NULL CHECK (catalog_state IN ('active', 'tombstone'))
) ON COMMIT DROP;

INSERT INTO demo_seed_user_catalog (
    profile_order,
    logical_key,
    user_id,
    identity_id,
    membership_id,
    first_name,
    last_name,
    email,
    password_hash,
    color,
    reserved_role,
    is_super_admin,
    catalog_state
)
SELECT
    profile.profile_order,
    profile.logical_key,
    public.uuid_generate_v5(context.namespace_uuid, 'user:' || profile.logical_key),
    public.uuid_generate_v5(context.namespace_uuid, 'identity:' || profile.logical_key),
    public.uuid_generate_v5(context.namespace_uuid, 'organization-user:' || profile.logical_key),
    profile.first_name,
    profile.last_name,
    'demo+' || profile.logical_key || '@epoca.example.invalid',
    '$2a$12$YnR4CcAvwX3gnw1qIllM.OgzpgUOPSfftXVJ/5ZiNUfc0tAC6GkVK',
    (ARRAY[
        '#5B5BD6',
        '#2E90FA',
        '#12B76A',
        '#F79009',
        '#F04438',
        '#7A5AF8',
        '#06AED4',
        '#EE46BC'
    ]::TEXT[])[((profile.profile_order - 1) % 8) + 1],
    profile.reserved_role,
    profile.is_super_admin,
    'active'
FROM (
    VALUES
    (1, 'amelia-nowak', 'Amelia', 'Nowak', 'Owner', true),
    (2, 'jan-lewandowski', 'Jan', 'Lewandowski', 'Manager', true),
    (3, 'marta-zielinska', 'Marta', 'Zielińska', NULL, false),
    (4, 'piotr-wisniewski', 'Piotr', 'Wiśniewski', NULL, false),
    (5, 'alicja-kowalska', 'Alicja', 'Kowalska', NULL, false),
    (6, 'kamil-wojcik', 'Kamil', 'Wójcik', NULL, false),
    (7, 'lena-kaminska', 'Lena', 'Kamińska', NULL, false),
    (8, 'michal-dabrowski', 'Michał', 'Dąbrowski', NULL, false),
    (9, 'zofia-kaczmarek', 'Zofia', 'Kaczmarek', NULL, false),
    (10, 'tomasz-mazur', 'Tomasz', 'Mazur', NULL, false),
    (11, 'julia-krawczyk', 'Julia', 'Krawczyk', NULL, false),
    (12, 'pawel-piotrowski', 'Paweł', 'Piotrowski', NULL, false),
    (13, 'natalia-grabowska', 'Natalia', 'Grabowska', NULL, false),
    (14, 'jakub-pawlak', 'Jakub', 'Pawlak', NULL, false),
    (15, 'emilia-michalska', 'Emilia', 'Michalska', NULL, false),
    (16, 'filip-nowicki', 'Filip', 'Nowicki', NULL, false),
    (17, 'maja-adamczyk', 'Maja', 'Adamczyk', NULL, false),
    (18, 'wojciech-dudek', 'Wojciech', 'Dudek', NULL, false),
    (19, 'hanna-wieczorek', 'Hanna', 'Wieczorek', NULL, false),
    (20, 'adam-jankowski', 'Adam', 'Jankowski', NULL, false),
    (21, 'oliwia-krol', 'Oliwia', 'Król', NULL, false),
    (22, 'szymon-lis', 'Szymon', 'Lis', NULL, false),
    (23, 'laura-sadowska', 'Laura', 'Sadowska', NULL, false),
    (24, 'krzysztof-baran', 'Krzysztof', 'Baran', NULL, false),
    (25, 'iga-sikora', 'Iga', 'Sikora', NULL, false),
    (26, 'damian-gorski', 'Damian', 'Górski', NULL, false),
    (27, 'sara-nilsson', 'Sara', 'Nilsson', NULL, false),
    (28, 'lucas-martin', 'Lucas', 'Martin', NULL, false),
    (29, 'nina-petrova', 'Nina', 'Petrova', NULL, false),
    (30, 'mateo-costa', 'Mateo', 'Costa', NULL, false)
) AS profile(profile_order, logical_key, first_name, last_name, reserved_role, is_super_admin)
CROSS JOIN demo_seed_catalog_context AS context;

CREATE TEMP TABLE demo_seed_client_catalog
(
    client_order  INTEGER PRIMARY KEY,
    logical_key   TEXT NOT NULL UNIQUE,
    client_id     UUID NOT NULL UNIQUE,
    name          TEXT NOT NULL,
    client_type   TEXT NOT NULL CHECK (client_type IN ('Internal', 'External')),
    color         TEXT NOT NULL,
    texture       TEXT NOT NULL,
    logo          TEXT NOT NULL CHECK (logo = ''),
    status        TEXT NOT NULL CHECK (status IN ('active', 'archived')),
    catalog_state TEXT NOT NULL CHECK (catalog_state IN ('active', 'tombstone'))
) ON COMMIT DROP;

INSERT INTO demo_seed_client_catalog (
    client_order,
    logical_key,
    client_id,
    name,
    client_type,
    color,
    texture,
    logo,
    status,
    catalog_state
)
SELECT
    client.client_order,
    client.logical_key,
    public.uuid_generate_v5(context.namespace_uuid, 'client:' || client.logical_key),
    client.name,
    client.client_type,
    client.color,
    client.texture,
    client.logo,
    client.status,
    'active'
FROM (
    VALUES
    (1, 'atelier-ops', 'Atelier Operations', 'Internal', '#5B5BD6', '', '', 'active'),
    (2, 'epoca-labs', 'Época Labs', 'Internal', '#2E90FA', '', '', 'active'),
    (3, 'northstar-health', 'Northstar Health', 'External', '#12B76A', '', '', 'active'),
    (4, 'lumen-retail', 'Lumen Retail', 'External', '#F79009', '', '', 'active'),
    (5, 'verdant-mobility', 'Verdant Mobility', 'External', '#06AED4', '', '', 'active'),
    (6, 'harbor-finance', 'Harbor Finance', 'External', '#7A5AF8', '', '', 'active'),
    (7, 'aurora-learning', 'Aurora Learning', 'External', '#EE46BC', '', '', 'active'),
    (8, 'pulse-commerce', 'Pulse Commerce', 'External', '#F04438', '', '', 'active'),
    (9, 'orbit-logistics', 'Orbit Logistics', 'External', '#667085', '', '', 'archived'),
    (10, 'ember-hospitality', 'Ember Hospitality', 'External', '#B54708', '', '', 'archived')
) AS client(client_order, logical_key, name, client_type, color, texture, logo, status)
CROSS JOIN demo_seed_catalog_context AS context;

CREATE TEMP TABLE demo_seed_project_catalog
(
    project_order INTEGER PRIMARY KEY,
    logical_key   TEXT    NOT NULL UNIQUE,
    project_id    UUID    NOT NULL UNIQUE,
    client_key    TEXT    NOT NULL,
    client_id     UUID    NOT NULL,
    name          TEXT    NOT NULL,
    description   TEXT    NOT NULL,
    status        TEXT    NOT NULL CHECK (status IN ('active', 'archived')),
    team_size     INTEGER NOT NULL CHECK (team_size > 0),
    task_prefix   TEXT    NOT NULL UNIQUE CHECK (task_prefix = upper(task_prefix)),
    catalog_state TEXT    NOT NULL CHECK (catalog_state IN ('active', 'tombstone')),
    UNIQUE (client_key, project_id)
) ON COMMIT DROP;

INSERT INTO demo_seed_project_catalog (
    project_order,
    logical_key,
    project_id,
    client_key,
    client_id,
    name,
    description,
    status,
    team_size,
    task_prefix,
    catalog_state
)
SELECT
    project.project_order,
    project.logical_key,
    public.uuid_generate_v5(context.namespace_uuid, 'project:' || project.logical_key),
    project.client_key,
    client.client_id,
    project.name,
    project.description,
    project.status,
    project.team_size,
    project.task_prefix,
    'active'
FROM (
    VALUES
    (1, 'delivery-operations-hub', 'atelier-ops', 'Delivery Operations Hub', 'Coordinates delivery planning, staffing, risks, and operational handoffs across active engagements.', 'active', 8, 'DOH'),
    (2, 'design-system-evolution', 'atelier-ops', 'Design System Evolution', 'Evolves shared design-system foundations, accessible components, and contribution workflows for product teams.', 'active', 6, 'DSE'),
    (3, 'time-intelligence', 'epoca-labs', 'Time Intelligence', 'Improves time tracking with dependable entry workflows, actionable trends, and capacity insights.', 'active', 10, 'TI'),
    (4, 'reporting-sandbox', 'epoca-labs', 'Reporting Sandbox', 'Provides a safe reporting workspace for prototyping metrics, dashboards, and decision-ready exports.', 'active', 5, 'RS'),
    (5, 'patient-portal', 'northstar-health', 'Patient Portal', 'Delivers a secure health portal where patients can manage appointments, messages, and care information.', 'active', 12, 'PP'),
    (6, 'care-analytics', 'northstar-health', 'Care Analytics', 'Turns clinical and operational data into clear analytics for care quality and service planning.', 'active', 7, 'CA'),
    (7, 'omnichannel-checkout', 'lumen-retail', 'Omnichannel Checkout', 'Unifies checkout journeys across web, mobile, and stores with consistent payment and fulfillment options.', 'active', 9, 'OC'),
    (8, 'retail-insights', 'lumen-retail', 'Retail Insights', 'Combines retail performance data into practical business intelligence for merchandising and store teams.', 'active', 5, 'RI'),
    (9, 'fleet-companion', 'verdant-mobility', 'Fleet Companion', 'Supports drivers and fleet operators with a focused mobility app for vehicles, trips, and service needs.', 'active', 8, 'FC'),
    (10, 'mobility-discovery', 'verdant-mobility', 'Mobility Discovery', 'Explores customer needs and viable concepts for the next generation of connected mobility services.', 'archived', 4, 'MD'),
    (11, 'risk-dashboard', 'harbor-finance', 'Risk Dashboard', 'Surfaces portfolio exposure, control signals, and emerging financial risks in one auditable dashboard.', 'active', 6, 'RD'),
    (12, 'open-banking-integration', 'harbor-finance', 'Open Banking Integration', 'Connects regulated banking providers through resilient consent, account, and transaction integration flows.', 'active', 11, 'OBI'),
    (13, 'learning-experience', 'aurora-learning', 'Learning Experience', 'Creates an engaging learning experience for discovering content, tracking progress, and supporting instructors.', 'active', 7, 'LE'),
    (14, 'commerce-growth', 'pulse-commerce', 'Commerce Growth', 'Tests and scales commerce growth opportunities across acquisition, conversion, retention, and lifecycle messaging.', 'active', 5, 'CG'),
    (15, 'dispatch-optimizer', 'orbit-logistics', 'Dispatch Optimizer', 'Optimizes logistics dispatch decisions using route constraints, shipment priorities, and live operating conditions.', 'archived', 6, 'DOP'),
    (16, 'guest-journey', 'ember-hospitality', 'Guest Journey', 'Maps and improves the hospitality journey from reservation and arrival through the post-stay relationship.', 'archived', 3, 'GJ')
) AS project(project_order, logical_key, client_key, name, description, status, team_size, task_prefix)
JOIN demo_seed_client_catalog AS client
  ON client.logical_key = project.client_key
CROSS JOIN demo_seed_catalog_context AS context;

CREATE TEMP TABLE demo_seed_project_user_catalog
(
    logical_key    TEXT NOT NULL PRIMARY KEY,
    project_user_id UUID NOT NULL UNIQUE,
    project_key    TEXT NOT NULL,
    project_id     UUID NOT NULL,
    user_key       TEXT NOT NULL,
    user_id        UUID NOT NULL,
    catalog_state  TEXT NOT NULL CHECK (catalog_state IN ('active', 'tombstone')),
    UNIQUE (project_id, user_id)
) ON COMMIT DROP;

INSERT INTO demo_seed_project_user_catalog (
    logical_key,
    project_user_id,
    project_key,
    project_id,
    user_key,
    user_id,
    catalog_state
)
SELECT
    'project-user:' || project.logical_key || ':' || profile.logical_key,
    public.uuid_generate_v5(
        context.namespace_uuid,
        'project-user:' || project.logical_key || ':' || profile.logical_key
    ),
    project.logical_key,
    project.project_id,
    profile.logical_key,
    profile.user_id,
    'active'
FROM demo_seed_project_catalog AS project
CROSS JOIN demo_seed_user_catalog AS profile
CROSS JOIN demo_seed_catalog_context AS context;

CREATE TEMP TABLE demo_seed_generator_catalog
(
    generator_name TEXT    NOT NULL,
    version        INTEGER NOT NULL,
    max_slot       INTEGER NOT NULL CHECK (max_slot > 0),
    catalog_state  TEXT    NOT NULL CHECK (catalog_state IN ('active', 'tombstone')),
    PRIMARY KEY (generator_name, version)
) ON COMMIT DROP;

INSERT INTO demo_seed_generator_catalog (
    generator_name,
    version,
    max_slot,
    catalog_state
)
VALUES ('work-unit', 1, 64, 'active');

-- Durable issued-key registry. Entries are append-only; tombstones remain issued.
-- issued-key: client:atelier-ops
-- issued-key: client:aurora-learning
-- issued-key: client:ember-hospitality
-- issued-key: client:epoca-labs
-- issued-key: client:harbor-finance
-- issued-key: client:lumen-retail
-- issued-key: client:northstar-health
-- issued-key: client:orbit-logistics
-- issued-key: client:pulse-commerce
-- issued-key: client:verdant-mobility
-- issued-key: generator:work-unit:v1:64
-- issued-key: identity:adam-jankowski
-- issued-key: identity:alicja-kowalska
-- issued-key: identity:amelia-nowak
-- issued-key: identity:damian-gorski
-- issued-key: identity:emilia-michalska
-- issued-key: identity:filip-nowicki
-- issued-key: identity:hanna-wieczorek
-- issued-key: identity:iga-sikora
-- issued-key: identity:jakub-pawlak
-- issued-key: identity:jan-lewandowski
-- issued-key: identity:julia-krawczyk
-- issued-key: identity:kamil-wojcik
-- issued-key: identity:krzysztof-baran
-- issued-key: identity:laura-sadowska
-- issued-key: identity:lena-kaminska
-- issued-key: identity:lucas-martin
-- issued-key: identity:maja-adamczyk
-- issued-key: identity:marta-zielinska
-- issued-key: identity:mateo-costa
-- issued-key: identity:michal-dabrowski
-- issued-key: identity:natalia-grabowska
-- issued-key: identity:nina-petrova
-- issued-key: identity:oliwia-krol
-- issued-key: identity:pawel-piotrowski
-- issued-key: identity:piotr-wisniewski
-- issued-key: identity:sara-nilsson
-- issued-key: identity:szymon-lis
-- issued-key: identity:tomasz-mazur
-- issued-key: identity:wojciech-dudek
-- issued-key: identity:zofia-kaczmarek
-- issued-key: organization-user:adam-jankowski
-- issued-key: organization-user:alicja-kowalska
-- issued-key: organization-user:amelia-nowak
-- issued-key: organization-user:damian-gorski
-- issued-key: organization-user:emilia-michalska
-- issued-key: organization-user:filip-nowicki
-- issued-key: organization-user:hanna-wieczorek
-- issued-key: organization-user:iga-sikora
-- issued-key: organization-user:jakub-pawlak
-- issued-key: organization-user:jan-lewandowski
-- issued-key: organization-user:julia-krawczyk
-- issued-key: organization-user:kamil-wojcik
-- issued-key: organization-user:krzysztof-baran
-- issued-key: organization-user:laura-sadowska
-- issued-key: organization-user:lena-kaminska
-- issued-key: organization-user:lucas-martin
-- issued-key: organization-user:maja-adamczyk
-- issued-key: organization-user:marta-zielinska
-- issued-key: organization-user:mateo-costa
-- issued-key: organization-user:michal-dabrowski
-- issued-key: organization-user:natalia-grabowska
-- issued-key: organization-user:nina-petrova
-- issued-key: organization-user:oliwia-krol
-- issued-key: organization-user:pawel-piotrowski
-- issued-key: organization-user:piotr-wisniewski
-- issued-key: organization-user:sara-nilsson
-- issued-key: organization-user:szymon-lis
-- issued-key: organization-user:tomasz-mazur
-- issued-key: organization-user:wojciech-dudek
-- issued-key: organization-user:zofia-kaczmarek
-- issued-key: organization:iteratorstesting
-- issued-key: project-user:care-analytics:adam-jankowski
-- issued-key: project-user:care-analytics:alicja-kowalska
-- issued-key: project-user:care-analytics:amelia-nowak
-- issued-key: project-user:care-analytics:damian-gorski
-- issued-key: project-user:care-analytics:emilia-michalska
-- issued-key: project-user:care-analytics:filip-nowicki
-- issued-key: project-user:care-analytics:hanna-wieczorek
-- issued-key: project-user:care-analytics:iga-sikora
-- issued-key: project-user:care-analytics:jakub-pawlak
-- issued-key: project-user:care-analytics:jan-lewandowski
-- issued-key: project-user:care-analytics:julia-krawczyk
-- issued-key: project-user:care-analytics:kamil-wojcik
-- issued-key: project-user:care-analytics:krzysztof-baran
-- issued-key: project-user:care-analytics:laura-sadowska
-- issued-key: project-user:care-analytics:lena-kaminska
-- issued-key: project-user:care-analytics:lucas-martin
-- issued-key: project-user:care-analytics:maja-adamczyk
-- issued-key: project-user:care-analytics:marta-zielinska
-- issued-key: project-user:care-analytics:mateo-costa
-- issued-key: project-user:care-analytics:michal-dabrowski
-- issued-key: project-user:care-analytics:natalia-grabowska
-- issued-key: project-user:care-analytics:nina-petrova
-- issued-key: project-user:care-analytics:oliwia-krol
-- issued-key: project-user:care-analytics:pawel-piotrowski
-- issued-key: project-user:care-analytics:piotr-wisniewski
-- issued-key: project-user:care-analytics:sara-nilsson
-- issued-key: project-user:care-analytics:szymon-lis
-- issued-key: project-user:care-analytics:tomasz-mazur
-- issued-key: project-user:care-analytics:wojciech-dudek
-- issued-key: project-user:care-analytics:zofia-kaczmarek
-- issued-key: project-user:commerce-growth:adam-jankowski
-- issued-key: project-user:commerce-growth:alicja-kowalska
-- issued-key: project-user:commerce-growth:amelia-nowak
-- issued-key: project-user:commerce-growth:damian-gorski
-- issued-key: project-user:commerce-growth:emilia-michalska
-- issued-key: project-user:commerce-growth:filip-nowicki
-- issued-key: project-user:commerce-growth:hanna-wieczorek
-- issued-key: project-user:commerce-growth:iga-sikora
-- issued-key: project-user:commerce-growth:jakub-pawlak
-- issued-key: project-user:commerce-growth:jan-lewandowski
-- issued-key: project-user:commerce-growth:julia-krawczyk
-- issued-key: project-user:commerce-growth:kamil-wojcik
-- issued-key: project-user:commerce-growth:krzysztof-baran
-- issued-key: project-user:commerce-growth:laura-sadowska
-- issued-key: project-user:commerce-growth:lena-kaminska
-- issued-key: project-user:commerce-growth:lucas-martin
-- issued-key: project-user:commerce-growth:maja-adamczyk
-- issued-key: project-user:commerce-growth:marta-zielinska
-- issued-key: project-user:commerce-growth:mateo-costa
-- issued-key: project-user:commerce-growth:michal-dabrowski
-- issued-key: project-user:commerce-growth:natalia-grabowska
-- issued-key: project-user:commerce-growth:nina-petrova
-- issued-key: project-user:commerce-growth:oliwia-krol
-- issued-key: project-user:commerce-growth:pawel-piotrowski
-- issued-key: project-user:commerce-growth:piotr-wisniewski
-- issued-key: project-user:commerce-growth:sara-nilsson
-- issued-key: project-user:commerce-growth:szymon-lis
-- issued-key: project-user:commerce-growth:tomasz-mazur
-- issued-key: project-user:commerce-growth:wojciech-dudek
-- issued-key: project-user:commerce-growth:zofia-kaczmarek
-- issued-key: project-user:delivery-operations-hub:adam-jankowski
-- issued-key: project-user:delivery-operations-hub:alicja-kowalska
-- issued-key: project-user:delivery-operations-hub:amelia-nowak
-- issued-key: project-user:delivery-operations-hub:damian-gorski
-- issued-key: project-user:delivery-operations-hub:emilia-michalska
-- issued-key: project-user:delivery-operations-hub:filip-nowicki
-- issued-key: project-user:delivery-operations-hub:hanna-wieczorek
-- issued-key: project-user:delivery-operations-hub:iga-sikora
-- issued-key: project-user:delivery-operations-hub:jakub-pawlak
-- issued-key: project-user:delivery-operations-hub:jan-lewandowski
-- issued-key: project-user:delivery-operations-hub:julia-krawczyk
-- issued-key: project-user:delivery-operations-hub:kamil-wojcik
-- issued-key: project-user:delivery-operations-hub:krzysztof-baran
-- issued-key: project-user:delivery-operations-hub:laura-sadowska
-- issued-key: project-user:delivery-operations-hub:lena-kaminska
-- issued-key: project-user:delivery-operations-hub:lucas-martin
-- issued-key: project-user:delivery-operations-hub:maja-adamczyk
-- issued-key: project-user:delivery-operations-hub:marta-zielinska
-- issued-key: project-user:delivery-operations-hub:mateo-costa
-- issued-key: project-user:delivery-operations-hub:michal-dabrowski
-- issued-key: project-user:delivery-operations-hub:natalia-grabowska
-- issued-key: project-user:delivery-operations-hub:nina-petrova
-- issued-key: project-user:delivery-operations-hub:oliwia-krol
-- issued-key: project-user:delivery-operations-hub:pawel-piotrowski
-- issued-key: project-user:delivery-operations-hub:piotr-wisniewski
-- issued-key: project-user:delivery-operations-hub:sara-nilsson
-- issued-key: project-user:delivery-operations-hub:szymon-lis
-- issued-key: project-user:delivery-operations-hub:tomasz-mazur
-- issued-key: project-user:delivery-operations-hub:wojciech-dudek
-- issued-key: project-user:delivery-operations-hub:zofia-kaczmarek
-- issued-key: project-user:design-system-evolution:adam-jankowski
-- issued-key: project-user:design-system-evolution:alicja-kowalska
-- issued-key: project-user:design-system-evolution:amelia-nowak
-- issued-key: project-user:design-system-evolution:damian-gorski
-- issued-key: project-user:design-system-evolution:emilia-michalska
-- issued-key: project-user:design-system-evolution:filip-nowicki
-- issued-key: project-user:design-system-evolution:hanna-wieczorek
-- issued-key: project-user:design-system-evolution:iga-sikora
-- issued-key: project-user:design-system-evolution:jakub-pawlak
-- issued-key: project-user:design-system-evolution:jan-lewandowski
-- issued-key: project-user:design-system-evolution:julia-krawczyk
-- issued-key: project-user:design-system-evolution:kamil-wojcik
-- issued-key: project-user:design-system-evolution:krzysztof-baran
-- issued-key: project-user:design-system-evolution:laura-sadowska
-- issued-key: project-user:design-system-evolution:lena-kaminska
-- issued-key: project-user:design-system-evolution:lucas-martin
-- issued-key: project-user:design-system-evolution:maja-adamczyk
-- issued-key: project-user:design-system-evolution:marta-zielinska
-- issued-key: project-user:design-system-evolution:mateo-costa
-- issued-key: project-user:design-system-evolution:michal-dabrowski
-- issued-key: project-user:design-system-evolution:natalia-grabowska
-- issued-key: project-user:design-system-evolution:nina-petrova
-- issued-key: project-user:design-system-evolution:oliwia-krol
-- issued-key: project-user:design-system-evolution:pawel-piotrowski
-- issued-key: project-user:design-system-evolution:piotr-wisniewski
-- issued-key: project-user:design-system-evolution:sara-nilsson
-- issued-key: project-user:design-system-evolution:szymon-lis
-- issued-key: project-user:design-system-evolution:tomasz-mazur
-- issued-key: project-user:design-system-evolution:wojciech-dudek
-- issued-key: project-user:design-system-evolution:zofia-kaczmarek
-- issued-key: project-user:dispatch-optimizer:adam-jankowski
-- issued-key: project-user:dispatch-optimizer:alicja-kowalska
-- issued-key: project-user:dispatch-optimizer:amelia-nowak
-- issued-key: project-user:dispatch-optimizer:damian-gorski
-- issued-key: project-user:dispatch-optimizer:emilia-michalska
-- issued-key: project-user:dispatch-optimizer:filip-nowicki
-- issued-key: project-user:dispatch-optimizer:hanna-wieczorek
-- issued-key: project-user:dispatch-optimizer:iga-sikora
-- issued-key: project-user:dispatch-optimizer:jakub-pawlak
-- issued-key: project-user:dispatch-optimizer:jan-lewandowski
-- issued-key: project-user:dispatch-optimizer:julia-krawczyk
-- issued-key: project-user:dispatch-optimizer:kamil-wojcik
-- issued-key: project-user:dispatch-optimizer:krzysztof-baran
-- issued-key: project-user:dispatch-optimizer:laura-sadowska
-- issued-key: project-user:dispatch-optimizer:lena-kaminska
-- issued-key: project-user:dispatch-optimizer:lucas-martin
-- issued-key: project-user:dispatch-optimizer:maja-adamczyk
-- issued-key: project-user:dispatch-optimizer:marta-zielinska
-- issued-key: project-user:dispatch-optimizer:mateo-costa
-- issued-key: project-user:dispatch-optimizer:michal-dabrowski
-- issued-key: project-user:dispatch-optimizer:natalia-grabowska
-- issued-key: project-user:dispatch-optimizer:nina-petrova
-- issued-key: project-user:dispatch-optimizer:oliwia-krol
-- issued-key: project-user:dispatch-optimizer:pawel-piotrowski
-- issued-key: project-user:dispatch-optimizer:piotr-wisniewski
-- issued-key: project-user:dispatch-optimizer:sara-nilsson
-- issued-key: project-user:dispatch-optimizer:szymon-lis
-- issued-key: project-user:dispatch-optimizer:tomasz-mazur
-- issued-key: project-user:dispatch-optimizer:wojciech-dudek
-- issued-key: project-user:dispatch-optimizer:zofia-kaczmarek
-- issued-key: project-user:fleet-companion:adam-jankowski
-- issued-key: project-user:fleet-companion:alicja-kowalska
-- issued-key: project-user:fleet-companion:amelia-nowak
-- issued-key: project-user:fleet-companion:damian-gorski
-- issued-key: project-user:fleet-companion:emilia-michalska
-- issued-key: project-user:fleet-companion:filip-nowicki
-- issued-key: project-user:fleet-companion:hanna-wieczorek
-- issued-key: project-user:fleet-companion:iga-sikora
-- issued-key: project-user:fleet-companion:jakub-pawlak
-- issued-key: project-user:fleet-companion:jan-lewandowski
-- issued-key: project-user:fleet-companion:julia-krawczyk
-- issued-key: project-user:fleet-companion:kamil-wojcik
-- issued-key: project-user:fleet-companion:krzysztof-baran
-- issued-key: project-user:fleet-companion:laura-sadowska
-- issued-key: project-user:fleet-companion:lena-kaminska
-- issued-key: project-user:fleet-companion:lucas-martin
-- issued-key: project-user:fleet-companion:maja-adamczyk
-- issued-key: project-user:fleet-companion:marta-zielinska
-- issued-key: project-user:fleet-companion:mateo-costa
-- issued-key: project-user:fleet-companion:michal-dabrowski
-- issued-key: project-user:fleet-companion:natalia-grabowska
-- issued-key: project-user:fleet-companion:nina-petrova
-- issued-key: project-user:fleet-companion:oliwia-krol
-- issued-key: project-user:fleet-companion:pawel-piotrowski
-- issued-key: project-user:fleet-companion:piotr-wisniewski
-- issued-key: project-user:fleet-companion:sara-nilsson
-- issued-key: project-user:fleet-companion:szymon-lis
-- issued-key: project-user:fleet-companion:tomasz-mazur
-- issued-key: project-user:fleet-companion:wojciech-dudek
-- issued-key: project-user:fleet-companion:zofia-kaczmarek
-- issued-key: project-user:guest-journey:adam-jankowski
-- issued-key: project-user:guest-journey:alicja-kowalska
-- issued-key: project-user:guest-journey:amelia-nowak
-- issued-key: project-user:guest-journey:damian-gorski
-- issued-key: project-user:guest-journey:emilia-michalska
-- issued-key: project-user:guest-journey:filip-nowicki
-- issued-key: project-user:guest-journey:hanna-wieczorek
-- issued-key: project-user:guest-journey:iga-sikora
-- issued-key: project-user:guest-journey:jakub-pawlak
-- issued-key: project-user:guest-journey:jan-lewandowski
-- issued-key: project-user:guest-journey:julia-krawczyk
-- issued-key: project-user:guest-journey:kamil-wojcik
-- issued-key: project-user:guest-journey:krzysztof-baran
-- issued-key: project-user:guest-journey:laura-sadowska
-- issued-key: project-user:guest-journey:lena-kaminska
-- issued-key: project-user:guest-journey:lucas-martin
-- issued-key: project-user:guest-journey:maja-adamczyk
-- issued-key: project-user:guest-journey:marta-zielinska
-- issued-key: project-user:guest-journey:mateo-costa
-- issued-key: project-user:guest-journey:michal-dabrowski
-- issued-key: project-user:guest-journey:natalia-grabowska
-- issued-key: project-user:guest-journey:nina-petrova
-- issued-key: project-user:guest-journey:oliwia-krol
-- issued-key: project-user:guest-journey:pawel-piotrowski
-- issued-key: project-user:guest-journey:piotr-wisniewski
-- issued-key: project-user:guest-journey:sara-nilsson
-- issued-key: project-user:guest-journey:szymon-lis
-- issued-key: project-user:guest-journey:tomasz-mazur
-- issued-key: project-user:guest-journey:wojciech-dudek
-- issued-key: project-user:guest-journey:zofia-kaczmarek
-- issued-key: project-user:learning-experience:adam-jankowski
-- issued-key: project-user:learning-experience:alicja-kowalska
-- issued-key: project-user:learning-experience:amelia-nowak
-- issued-key: project-user:learning-experience:damian-gorski
-- issued-key: project-user:learning-experience:emilia-michalska
-- issued-key: project-user:learning-experience:filip-nowicki
-- issued-key: project-user:learning-experience:hanna-wieczorek
-- issued-key: project-user:learning-experience:iga-sikora
-- issued-key: project-user:learning-experience:jakub-pawlak
-- issued-key: project-user:learning-experience:jan-lewandowski
-- issued-key: project-user:learning-experience:julia-krawczyk
-- issued-key: project-user:learning-experience:kamil-wojcik
-- issued-key: project-user:learning-experience:krzysztof-baran
-- issued-key: project-user:learning-experience:laura-sadowska
-- issued-key: project-user:learning-experience:lena-kaminska
-- issued-key: project-user:learning-experience:lucas-martin
-- issued-key: project-user:learning-experience:maja-adamczyk
-- issued-key: project-user:learning-experience:marta-zielinska
-- issued-key: project-user:learning-experience:mateo-costa
-- issued-key: project-user:learning-experience:michal-dabrowski
-- issued-key: project-user:learning-experience:natalia-grabowska
-- issued-key: project-user:learning-experience:nina-petrova
-- issued-key: project-user:learning-experience:oliwia-krol
-- issued-key: project-user:learning-experience:pawel-piotrowski
-- issued-key: project-user:learning-experience:piotr-wisniewski
-- issued-key: project-user:learning-experience:sara-nilsson
-- issued-key: project-user:learning-experience:szymon-lis
-- issued-key: project-user:learning-experience:tomasz-mazur
-- issued-key: project-user:learning-experience:wojciech-dudek
-- issued-key: project-user:learning-experience:zofia-kaczmarek
-- issued-key: project-user:mobility-discovery:adam-jankowski
-- issued-key: project-user:mobility-discovery:alicja-kowalska
-- issued-key: project-user:mobility-discovery:amelia-nowak
-- issued-key: project-user:mobility-discovery:damian-gorski
-- issued-key: project-user:mobility-discovery:emilia-michalska
-- issued-key: project-user:mobility-discovery:filip-nowicki
-- issued-key: project-user:mobility-discovery:hanna-wieczorek
-- issued-key: project-user:mobility-discovery:iga-sikora
-- issued-key: project-user:mobility-discovery:jakub-pawlak
-- issued-key: project-user:mobility-discovery:jan-lewandowski
-- issued-key: project-user:mobility-discovery:julia-krawczyk
-- issued-key: project-user:mobility-discovery:kamil-wojcik
-- issued-key: project-user:mobility-discovery:krzysztof-baran
-- issued-key: project-user:mobility-discovery:laura-sadowska
-- issued-key: project-user:mobility-discovery:lena-kaminska
-- issued-key: project-user:mobility-discovery:lucas-martin
-- issued-key: project-user:mobility-discovery:maja-adamczyk
-- issued-key: project-user:mobility-discovery:marta-zielinska
-- issued-key: project-user:mobility-discovery:mateo-costa
-- issued-key: project-user:mobility-discovery:michal-dabrowski
-- issued-key: project-user:mobility-discovery:natalia-grabowska
-- issued-key: project-user:mobility-discovery:nina-petrova
-- issued-key: project-user:mobility-discovery:oliwia-krol
-- issued-key: project-user:mobility-discovery:pawel-piotrowski
-- issued-key: project-user:mobility-discovery:piotr-wisniewski
-- issued-key: project-user:mobility-discovery:sara-nilsson
-- issued-key: project-user:mobility-discovery:szymon-lis
-- issued-key: project-user:mobility-discovery:tomasz-mazur
-- issued-key: project-user:mobility-discovery:wojciech-dudek
-- issued-key: project-user:mobility-discovery:zofia-kaczmarek
-- issued-key: project-user:omnichannel-checkout:adam-jankowski
-- issued-key: project-user:omnichannel-checkout:alicja-kowalska
-- issued-key: project-user:omnichannel-checkout:amelia-nowak
-- issued-key: project-user:omnichannel-checkout:damian-gorski
-- issued-key: project-user:omnichannel-checkout:emilia-michalska
-- issued-key: project-user:omnichannel-checkout:filip-nowicki
-- issued-key: project-user:omnichannel-checkout:hanna-wieczorek
-- issued-key: project-user:omnichannel-checkout:iga-sikora
-- issued-key: project-user:omnichannel-checkout:jakub-pawlak
-- issued-key: project-user:omnichannel-checkout:jan-lewandowski
-- issued-key: project-user:omnichannel-checkout:julia-krawczyk
-- issued-key: project-user:omnichannel-checkout:kamil-wojcik
-- issued-key: project-user:omnichannel-checkout:krzysztof-baran
-- issued-key: project-user:omnichannel-checkout:laura-sadowska
-- issued-key: project-user:omnichannel-checkout:lena-kaminska
-- issued-key: project-user:omnichannel-checkout:lucas-martin
-- issued-key: project-user:omnichannel-checkout:maja-adamczyk
-- issued-key: project-user:omnichannel-checkout:marta-zielinska
-- issued-key: project-user:omnichannel-checkout:mateo-costa
-- issued-key: project-user:omnichannel-checkout:michal-dabrowski
-- issued-key: project-user:omnichannel-checkout:natalia-grabowska
-- issued-key: project-user:omnichannel-checkout:nina-petrova
-- issued-key: project-user:omnichannel-checkout:oliwia-krol
-- issued-key: project-user:omnichannel-checkout:pawel-piotrowski
-- issued-key: project-user:omnichannel-checkout:piotr-wisniewski
-- issued-key: project-user:omnichannel-checkout:sara-nilsson
-- issued-key: project-user:omnichannel-checkout:szymon-lis
-- issued-key: project-user:omnichannel-checkout:tomasz-mazur
-- issued-key: project-user:omnichannel-checkout:wojciech-dudek
-- issued-key: project-user:omnichannel-checkout:zofia-kaczmarek
-- issued-key: project-user:open-banking-integration:adam-jankowski
-- issued-key: project-user:open-banking-integration:alicja-kowalska
-- issued-key: project-user:open-banking-integration:amelia-nowak
-- issued-key: project-user:open-banking-integration:damian-gorski
-- issued-key: project-user:open-banking-integration:emilia-michalska
-- issued-key: project-user:open-banking-integration:filip-nowicki
-- issued-key: project-user:open-banking-integration:hanna-wieczorek
-- issued-key: project-user:open-banking-integration:iga-sikora
-- issued-key: project-user:open-banking-integration:jakub-pawlak
-- issued-key: project-user:open-banking-integration:jan-lewandowski
-- issued-key: project-user:open-banking-integration:julia-krawczyk
-- issued-key: project-user:open-banking-integration:kamil-wojcik
-- issued-key: project-user:open-banking-integration:krzysztof-baran
-- issued-key: project-user:open-banking-integration:laura-sadowska
-- issued-key: project-user:open-banking-integration:lena-kaminska
-- issued-key: project-user:open-banking-integration:lucas-martin
-- issued-key: project-user:open-banking-integration:maja-adamczyk
-- issued-key: project-user:open-banking-integration:marta-zielinska
-- issued-key: project-user:open-banking-integration:mateo-costa
-- issued-key: project-user:open-banking-integration:michal-dabrowski
-- issued-key: project-user:open-banking-integration:natalia-grabowska
-- issued-key: project-user:open-banking-integration:nina-petrova
-- issued-key: project-user:open-banking-integration:oliwia-krol
-- issued-key: project-user:open-banking-integration:pawel-piotrowski
-- issued-key: project-user:open-banking-integration:piotr-wisniewski
-- issued-key: project-user:open-banking-integration:sara-nilsson
-- issued-key: project-user:open-banking-integration:szymon-lis
-- issued-key: project-user:open-banking-integration:tomasz-mazur
-- issued-key: project-user:open-banking-integration:wojciech-dudek
-- issued-key: project-user:open-banking-integration:zofia-kaczmarek
-- issued-key: project-user:patient-portal:adam-jankowski
-- issued-key: project-user:patient-portal:alicja-kowalska
-- issued-key: project-user:patient-portal:amelia-nowak
-- issued-key: project-user:patient-portal:damian-gorski
-- issued-key: project-user:patient-portal:emilia-michalska
-- issued-key: project-user:patient-portal:filip-nowicki
-- issued-key: project-user:patient-portal:hanna-wieczorek
-- issued-key: project-user:patient-portal:iga-sikora
-- issued-key: project-user:patient-portal:jakub-pawlak
-- issued-key: project-user:patient-portal:jan-lewandowski
-- issued-key: project-user:patient-portal:julia-krawczyk
-- issued-key: project-user:patient-portal:kamil-wojcik
-- issued-key: project-user:patient-portal:krzysztof-baran
-- issued-key: project-user:patient-portal:laura-sadowska
-- issued-key: project-user:patient-portal:lena-kaminska
-- issued-key: project-user:patient-portal:lucas-martin
-- issued-key: project-user:patient-portal:maja-adamczyk
-- issued-key: project-user:patient-portal:marta-zielinska
-- issued-key: project-user:patient-portal:mateo-costa
-- issued-key: project-user:patient-portal:michal-dabrowski
-- issued-key: project-user:patient-portal:natalia-grabowska
-- issued-key: project-user:patient-portal:nina-petrova
-- issued-key: project-user:patient-portal:oliwia-krol
-- issued-key: project-user:patient-portal:pawel-piotrowski
-- issued-key: project-user:patient-portal:piotr-wisniewski
-- issued-key: project-user:patient-portal:sara-nilsson
-- issued-key: project-user:patient-portal:szymon-lis
-- issued-key: project-user:patient-portal:tomasz-mazur
-- issued-key: project-user:patient-portal:wojciech-dudek
-- issued-key: project-user:patient-portal:zofia-kaczmarek
-- issued-key: project-user:reporting-sandbox:adam-jankowski
-- issued-key: project-user:reporting-sandbox:alicja-kowalska
-- issued-key: project-user:reporting-sandbox:amelia-nowak
-- issued-key: project-user:reporting-sandbox:damian-gorski
-- issued-key: project-user:reporting-sandbox:emilia-michalska
-- issued-key: project-user:reporting-sandbox:filip-nowicki
-- issued-key: project-user:reporting-sandbox:hanna-wieczorek
-- issued-key: project-user:reporting-sandbox:iga-sikora
-- issued-key: project-user:reporting-sandbox:jakub-pawlak
-- issued-key: project-user:reporting-sandbox:jan-lewandowski
-- issued-key: project-user:reporting-sandbox:julia-krawczyk
-- issued-key: project-user:reporting-sandbox:kamil-wojcik
-- issued-key: project-user:reporting-sandbox:krzysztof-baran
-- issued-key: project-user:reporting-sandbox:laura-sadowska
-- issued-key: project-user:reporting-sandbox:lena-kaminska
-- issued-key: project-user:reporting-sandbox:lucas-martin
-- issued-key: project-user:reporting-sandbox:maja-adamczyk
-- issued-key: project-user:reporting-sandbox:marta-zielinska
-- issued-key: project-user:reporting-sandbox:mateo-costa
-- issued-key: project-user:reporting-sandbox:michal-dabrowski
-- issued-key: project-user:reporting-sandbox:natalia-grabowska
-- issued-key: project-user:reporting-sandbox:nina-petrova
-- issued-key: project-user:reporting-sandbox:oliwia-krol
-- issued-key: project-user:reporting-sandbox:pawel-piotrowski
-- issued-key: project-user:reporting-sandbox:piotr-wisniewski
-- issued-key: project-user:reporting-sandbox:sara-nilsson
-- issued-key: project-user:reporting-sandbox:szymon-lis
-- issued-key: project-user:reporting-sandbox:tomasz-mazur
-- issued-key: project-user:reporting-sandbox:wojciech-dudek
-- issued-key: project-user:reporting-sandbox:zofia-kaczmarek
-- issued-key: project-user:retail-insights:adam-jankowski
-- issued-key: project-user:retail-insights:alicja-kowalska
-- issued-key: project-user:retail-insights:amelia-nowak
-- issued-key: project-user:retail-insights:damian-gorski
-- issued-key: project-user:retail-insights:emilia-michalska
-- issued-key: project-user:retail-insights:filip-nowicki
-- issued-key: project-user:retail-insights:hanna-wieczorek
-- issued-key: project-user:retail-insights:iga-sikora
-- issued-key: project-user:retail-insights:jakub-pawlak
-- issued-key: project-user:retail-insights:jan-lewandowski
-- issued-key: project-user:retail-insights:julia-krawczyk
-- issued-key: project-user:retail-insights:kamil-wojcik
-- issued-key: project-user:retail-insights:krzysztof-baran
-- issued-key: project-user:retail-insights:laura-sadowska
-- issued-key: project-user:retail-insights:lena-kaminska
-- issued-key: project-user:retail-insights:lucas-martin
-- issued-key: project-user:retail-insights:maja-adamczyk
-- issued-key: project-user:retail-insights:marta-zielinska
-- issued-key: project-user:retail-insights:mateo-costa
-- issued-key: project-user:retail-insights:michal-dabrowski
-- issued-key: project-user:retail-insights:natalia-grabowska
-- issued-key: project-user:retail-insights:nina-petrova
-- issued-key: project-user:retail-insights:oliwia-krol
-- issued-key: project-user:retail-insights:pawel-piotrowski
-- issued-key: project-user:retail-insights:piotr-wisniewski
-- issued-key: project-user:retail-insights:sara-nilsson
-- issued-key: project-user:retail-insights:szymon-lis
-- issued-key: project-user:retail-insights:tomasz-mazur
-- issued-key: project-user:retail-insights:wojciech-dudek
-- issued-key: project-user:retail-insights:zofia-kaczmarek
-- issued-key: project-user:risk-dashboard:adam-jankowski
-- issued-key: project-user:risk-dashboard:alicja-kowalska
-- issued-key: project-user:risk-dashboard:amelia-nowak
-- issued-key: project-user:risk-dashboard:damian-gorski
-- issued-key: project-user:risk-dashboard:emilia-michalska
-- issued-key: project-user:risk-dashboard:filip-nowicki
-- issued-key: project-user:risk-dashboard:hanna-wieczorek
-- issued-key: project-user:risk-dashboard:iga-sikora
-- issued-key: project-user:risk-dashboard:jakub-pawlak
-- issued-key: project-user:risk-dashboard:jan-lewandowski
-- issued-key: project-user:risk-dashboard:julia-krawczyk
-- issued-key: project-user:risk-dashboard:kamil-wojcik
-- issued-key: project-user:risk-dashboard:krzysztof-baran
-- issued-key: project-user:risk-dashboard:laura-sadowska
-- issued-key: project-user:risk-dashboard:lena-kaminska
-- issued-key: project-user:risk-dashboard:lucas-martin
-- issued-key: project-user:risk-dashboard:maja-adamczyk
-- issued-key: project-user:risk-dashboard:marta-zielinska
-- issued-key: project-user:risk-dashboard:mateo-costa
-- issued-key: project-user:risk-dashboard:michal-dabrowski
-- issued-key: project-user:risk-dashboard:natalia-grabowska
-- issued-key: project-user:risk-dashboard:nina-petrova
-- issued-key: project-user:risk-dashboard:oliwia-krol
-- issued-key: project-user:risk-dashboard:pawel-piotrowski
-- issued-key: project-user:risk-dashboard:piotr-wisniewski
-- issued-key: project-user:risk-dashboard:sara-nilsson
-- issued-key: project-user:risk-dashboard:szymon-lis
-- issued-key: project-user:risk-dashboard:tomasz-mazur
-- issued-key: project-user:risk-dashboard:wojciech-dudek
-- issued-key: project-user:risk-dashboard:zofia-kaczmarek
-- issued-key: project-user:time-intelligence:adam-jankowski
-- issued-key: project-user:time-intelligence:alicja-kowalska
-- issued-key: project-user:time-intelligence:amelia-nowak
-- issued-key: project-user:time-intelligence:damian-gorski
-- issued-key: project-user:time-intelligence:emilia-michalska
-- issued-key: project-user:time-intelligence:filip-nowicki
-- issued-key: project-user:time-intelligence:hanna-wieczorek
-- issued-key: project-user:time-intelligence:iga-sikora
-- issued-key: project-user:time-intelligence:jakub-pawlak
-- issued-key: project-user:time-intelligence:jan-lewandowski
-- issued-key: project-user:time-intelligence:julia-krawczyk
-- issued-key: project-user:time-intelligence:kamil-wojcik
-- issued-key: project-user:time-intelligence:krzysztof-baran
-- issued-key: project-user:time-intelligence:laura-sadowska
-- issued-key: project-user:time-intelligence:lena-kaminska
-- issued-key: project-user:time-intelligence:lucas-martin
-- issued-key: project-user:time-intelligence:maja-adamczyk
-- issued-key: project-user:time-intelligence:marta-zielinska
-- issued-key: project-user:time-intelligence:mateo-costa
-- issued-key: project-user:time-intelligence:michal-dabrowski
-- issued-key: project-user:time-intelligence:natalia-grabowska
-- issued-key: project-user:time-intelligence:nina-petrova
-- issued-key: project-user:time-intelligence:oliwia-krol
-- issued-key: project-user:time-intelligence:pawel-piotrowski
-- issued-key: project-user:time-intelligence:piotr-wisniewski
-- issued-key: project-user:time-intelligence:sara-nilsson
-- issued-key: project-user:time-intelligence:szymon-lis
-- issued-key: project-user:time-intelligence:tomasz-mazur
-- issued-key: project-user:time-intelligence:wojciech-dudek
-- issued-key: project-user:time-intelligence:zofia-kaczmarek
-- issued-key: project:care-analytics
-- issued-key: project:commerce-growth
-- issued-key: project:delivery-operations-hub
-- issued-key: project:design-system-evolution
-- issued-key: project:dispatch-optimizer
-- issued-key: project:fleet-companion
-- issued-key: project:guest-journey
-- issued-key: project:learning-experience
-- issued-key: project:mobility-discovery
-- issued-key: project:omnichannel-checkout
-- issued-key: project:open-banking-integration
-- issued-key: project:patient-portal
-- issued-key: project:reporting-sandbox
-- issued-key: project:retail-insights
-- issued-key: project:risk-dashboard
-- issued-key: project:time-intelligence
-- issued-key: user:adam-jankowski
-- issued-key: user:alicja-kowalska
-- issued-key: user:amelia-nowak
-- issued-key: user:damian-gorski
-- issued-key: user:emilia-michalska
-- issued-key: user:filip-nowicki
-- issued-key: user:hanna-wieczorek
-- issued-key: user:iga-sikora
-- issued-key: user:jakub-pawlak
-- issued-key: user:jan-lewandowski
-- issued-key: user:julia-krawczyk
-- issued-key: user:kamil-wojcik
-- issued-key: user:krzysztof-baran
-- issued-key: user:laura-sadowska
-- issued-key: user:lena-kaminska
-- issued-key: user:lucas-martin
-- issued-key: user:maja-adamczyk
-- issued-key: user:marta-zielinska
-- issued-key: user:mateo-costa
-- issued-key: user:michal-dabrowski
-- issued-key: user:natalia-grabowska
-- issued-key: user:nina-petrova
-- issued-key: user:oliwia-krol
-- issued-key: user:pawel-piotrowski
-- issued-key: user:piotr-wisniewski
-- issued-key: user:sara-nilsson
-- issued-key: user:szymon-lis
-- issued-key: user:tomasz-mazur
-- issued-key: user:wojciech-dudek
-- issued-key: user:zofia-kaczmarek
