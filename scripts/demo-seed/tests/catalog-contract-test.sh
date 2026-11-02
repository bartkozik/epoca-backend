#!/usr/bin/env bash

set -euo pipefail

TEST_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
REPO_ROOT=$(CDPATH= cd -- "$TEST_DIR/../../.." && pwd -P)
# shellcheck source=assertions.sh
. "$TEST_DIR/assertions.sh"

CATALOG_PATH="$REPO_ROOT/scripts/demo-seed/catalog.sql"
CLEANUP_CATALOG_PATH="$REPO_ROOT/scripts/demo-seed/project-cleanup-catalog.sql"
HISTORY_DIR="$REPO_ROOT/scripts/demo-seed/catalog-history"
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/demo-seed-catalog.XXXXXX")
trap 'rm -rf "$TMP_DIR"' EXIT HUP INT TERM
VALIDATION_COUNTER=0

validation_error() {
  printf 'catalog contract: %s\n' "$*" >&2
  return 1
}

count_prefix() {
  local prefix=$1
  local file=$2

  awk -v prefix="$prefix" 'index($0, prefix) == 1 { count += 1 } END { print count + 0 }' "$file"
}

extract_issued_keys() {
  local catalog=$1

  sed -n 's/^[[:space:]]*-- issued-key: \([^[:space:]]*\)[[:space:]]*$/\1/p' "$catalog"
}

assert_uuid_calls_are_schema_qualified() {
  local unqualified

  unqualified=$(awk '
    /uuid_generate_v5[[:space:]]*\(/ &&
    !/public\.uuid_generate_v5[[:space:]]*\(/ &&
    !/to_regprocedure/ &&
    !/CREATE FUNCTION demo_seed_uuid_shadow\.uuid_generate_v5/ {
      print FILENAME ":" FNR ":" $0
    }
  ' "$CATALOG_PATH" "$TEST_DIR/catalog-contract-test.sh")
  [ -z "$unqualified" ] || fail "unqualified executable uuid_generate_v5 call: $unqualified"
}

validate_catalog() {
  local catalog=$1
  local history_dir=$2
  local work_dir
  local history_file
  local history_name
  local history_version
  local unexpected
  local family
  local project_key
  local user_key

  [ -f "$catalog" ] || {
    validation_error "missing catalog.sql: $catalog"
    return 1
  }
  [ -d "$history_dir" ] || {
    validation_error "missing catalog-history directory: $history_dir"
    return 1
  }
  [ "$(grep -Fxc '\ir project-cleanup-catalog.sql' "$catalog")" = 1 ] || {
    validation_error 'catalog must include project-cleanup-catalog.sql exactly once'
    return 1
  }

  VALIDATION_COUNTER=$((VALIDATION_COUNTER + 1))
  work_dir="$TMP_DIR/validation-$VALIDATION_COUNTER"
  mkdir -p "$work_dir"

  extract_issued_keys "$catalog" >"$work_dir/current-keys.txt"
  [ -s "$work_dir/current-keys.txt" ] || {
    validation_error 'catalog has no exact -- issued-key: <type>:<key> comments'
    return 1
  }
  LC_ALL=C sort "$work_dir/current-keys.txt" >"$work_dir/current-sorted.txt"
  LC_ALL=C sort "$work_dir/current-keys.txt" | uniq -d >"$work_dir/current-duplicates.txt"
  [ ! -s "$work_dir/current-duplicates.txt" ] || {
    validation_error "duplicate current issued-key comment: $(head -n 1 "$work_dir/current-duplicates.txt")"
    return 1
  }

  set -- "$history_dir"/*-issued-keys.txt
  [ -e "$1" ] || {
    validation_error 'catalog history must contain issued-key files'
    return 1
  }
  : >"$work_dir/all-history-keys.txt"
  for history_file in "$@"; do
    history_name=${history_file##*/}
    case "$history_name" in
      v*-issued-keys.txt) ;;
      *)
        validation_error "history filename does not have a numeric version: $history_name"
        return 1
        ;;
    esac
    history_version=${history_name#v}
    history_version=${history_version%-issued-keys.txt}
    case "$history_version" in
      ''|*[!0-9]*)
        validation_error "history filename does not have a numeric version: $history_name"
        return 1
        ;;
    esac
    [ -s "$history_file" ] || {
      validation_error "history file is empty: $history_name"
      return 1
    }
    if grep -q '^[[:space:]]*$' "$history_file"; then
      validation_error "history file contains an empty line: $history_name"
      return 1
    fi
    LC_ALL=C sort -u "$history_file" >"$work_dir/sorted-$history_name"
    cmp -s "$history_file" "$work_dir/sorted-$history_name" || {
      validation_error "history file is not sorted and unique: $history_name"
      return 1
    }
    cat "$history_file" >>"$work_dir/all-history-keys.txt"
  done

  [ -f "$history_dir/v1-issued-keys.txt" ] || {
    validation_error 'catalog history must contain v1-issued-keys.txt'
    return 1
  }
  LC_ALL=C sort -u "$work_dir/all-history-keys.txt" >"$work_dir/history-union.txt"
  LC_ALL=C comm -23 "$work_dir/history-union.txt" "$work_dir/current-sorted.txt" >"$work_dir/removed-history.txt"
  [ ! -s "$work_dir/removed-history.txt" ] || {
    validation_error "historical issued key disappeared from catalog: $(head -n 1 "$work_dir/removed-history.txt")"
    return 1
  }

  assert_eq 1 "$(count_prefix 'organization:' "$history_dir/v1-issued-keys.txt")" 'v1 organization key count'
  assert_eq 30 "$(count_prefix 'user:' "$history_dir/v1-issued-keys.txt")" 'v1 user key count'
  assert_eq 30 "$(count_prefix 'identity:' "$history_dir/v1-issued-keys.txt")" 'v1 identity key count'
  assert_eq 30 "$(count_prefix 'organization-user:' "$history_dir/v1-issued-keys.txt")" 'v1 organization-user key count'
  assert_eq 10 "$(count_prefix 'client:' "$history_dir/v1-issued-keys.txt")" 'v1 client key count'
  assert_eq 16 "$(count_prefix 'project:' "$history_dir/v1-issued-keys.txt")" 'v1 project key count'
  assert_eq 480 "$(count_prefix 'project-user:' "$history_dir/v1-issued-keys.txt")" 'v1 project-user key count'
  assert_eq 1 "$(awk '$0 == "generator:work-unit:v1:64" { count += 1 } END { print count + 0 }' "$history_dir/v1-issued-keys.txt")" 'v1 generator key count'
  assert_eq 598 "$(wc -l <"$history_dir/v1-issued-keys.txt" | tr -d ' ')" 'v1 total issued-key count'

  sed -n 's/^user://p' "$history_dir/v1-issued-keys.txt" | LC_ALL=C sort >"$work_dir/user-suffixes.txt"
  for family in identity organization-user; do
    sed -n "s/^$family://p" "$history_dir/v1-issued-keys.txt" | LC_ALL=C sort >"$work_dir/$family-suffixes.txt"
    cmp -s "$work_dir/user-suffixes.txt" "$work_dir/$family-suffixes.txt" || {
      validation_error "v1 $family logical suffixes differ from user suffixes"
      return 1
    }
  done

  sed -n 's/^project://p' "$history_dir/v1-issued-keys.txt" | LC_ALL=C sort >"$work_dir/project-suffixes.txt"
  : >"$work_dir/expected-project-user.txt"
  for project_key in $(cat "$work_dir/project-suffixes.txt"); do
    for user_key in $(cat "$work_dir/user-suffixes.txt"); do
      printf 'project-user:%s:%s\n' "$project_key" "$user_key" >>"$work_dir/expected-project-user.txt"
    done
  done
  LC_ALL=C sort "$work_dir/expected-project-user.txt" -o "$work_dir/expected-project-user.txt"
  assert_eq 480 "$(wc -l <"$work_dir/expected-project-user.txt" | tr -d ' ')" 'derived project-user cross-product count'
  LC_ALL=C comm -23 "$work_dir/expected-project-user.txt" "$work_dir/current-sorted.txt" >"$work_dir/missing-project-user.txt"
  [ ! -s "$work_dir/missing-project-user.txt" ] || {
    validation_error "catalog is missing project-user cross-product member: $(head -n 1 "$work_dir/missing-project-user.txt")"
    return 1
  }

  grep -q '2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532' "$catalog" || {
    validation_error 'catalog is missing the fixed namespace UUID'
    return 1
  }
  unexpected=$(awk '/^(credential|idempotency|work_unit_order):/ { print; exit }' "$work_dir/current-sorted.txt")
  [ -z "$unexpected" ] || {
    validation_error "forbidden durable key family was issued: $unexpected"
    return 1
  }
}

emit_materialization_assertions() {
  cat <<'SQL'
DO $catalog_contract$
DECLARE
  actual_temp_tables TEXT[];
BEGIN
  SELECT array_agg(c.relname::TEXT ORDER BY c.relname::TEXT)
  INTO actual_temp_tables
  FROM pg_class AS c
  WHERE c.relnamespace = pg_my_temp_schema()
    AND c.relkind = 'r';

  IF actual_temp_tables IS DISTINCT FROM ARRAY[
    'demo_seed_catalog_context',
    'demo_seed_client_catalog',
    'demo_seed_generator_catalog',
    'demo_seed_project_catalog',
    'demo_seed_project_cleanup_catalog',
    'demo_seed_project_user_catalog',
    'demo_seed_user_catalog'
  ]::TEXT[] THEN
    RAISE EXCEPTION 'unexpected temporary catalog tables: %', actual_temp_tables;
  END IF;

  IF (SELECT count(*) FROM demo_seed_catalog_context) <> 1
     OR NOT EXISTS (
       SELECT 1
       FROM demo_seed_catalog_context
       WHERE namespace_uuid = '2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::UUID
         AND organization_key = 'iteratorstesting'
         AND catalog_version = 1
         AND organization_id = public.uuid_generate_v5(
           '2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::UUID,
           'organization:iteratorstesting'
         )
     ) THEN
    RAISE EXCEPTION 'exact catalog context mismatch';
  END IF;

  IF (SELECT count(*) FROM demo_seed_user_catalog) <> 30
     OR (SELECT count(*) FROM demo_seed_client_catalog) <> 10
     OR (SELECT count(*) FROM demo_seed_project_catalog) <> 16
     OR (SELECT count(*) FROM demo_seed_project_user_catalog) <> 480
     OR (SELECT count(*) FROM demo_seed_generator_catalog) <> 1 THEN
    RAISE EXCEPTION 'catalog row counts mismatch';
  END IF;

  IF (SELECT count(*) FROM demo_seed_project_cleanup_catalog) <> 12
     OR (SELECT count(DISTINCT project_id) FROM demo_seed_project_cleanup_catalog) <> 12
     OR (SELECT count(DISTINCT new_name) FROM demo_seed_project_cleanup_catalog) <> 12
     OR (SELECT count(*) FROM demo_seed_project_cleanup_catalog WHERE new_description <> '') <> 12
     OR (SELECT count(*) FROM demo_seed_project_cleanup_catalog WHERE old_description_mode = 'exact') <> 5
     OR (SELECT count(*) FROM demo_seed_project_cleanup_catalog WHERE old_description_mode = 'blank') <> 7 THEN
    RAISE EXCEPTION 'project cleanup catalog shape mismatch';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM demo_seed_project_cleanup_catalog
    WHERE (old_description_mode = 'blank' AND old_description IS NOT NULL)
       OR (old_description_mode = 'exact' AND old_description IS NULL)
  ) THEN
    RAISE EXCEPTION 'project cleanup description mode is inconsistent';
  END IF;

  IF EXISTS (
    WITH expected(
      project_id,
      old_name,
      old_description,
      old_description_mode,
      new_name,
      new_description
    ) AS (
      VALUES
        (
          'b3c577d9-bede-458e-a523-ca094f79cf11'::UUID,
          'testfggp',
          'opis2hgfhfghf',
          'exact',
          'Learning Platform Refresh',
          'Modernization of the learning portal, course catalog and participant onboarding experience.'
        ),
        (
          'c65a2c74-4884-48bb-acd8-9602badc55de'::UUID,
          'fdsfasef',
          NULL,
          'blank',
          'Supplier Quality Dashboard',
          'Dashboard for supplier quality metrics, audits and corrective action tracking.'
        ),
        (
          '0c33f71d-d4da-4cfd-bbab-ec99285de371'::UUID,
          'a',
          NULL,
          'blank',
          'Inventory Forecasting',
          'Demand forecasting and inventory planning for seasonal product lines.'
        ),
        (
          'e2a7ab54-1c05-4629-8d18-f0c26af7074a'::UUID,
          's',
          NULL,
          'blank',
          'Order Fulfillment Portal',
          'Portal for order processing, fulfillment status and delivery exception handling.'
        ),
        (
          '51952efb-5ccb-48ec-bf91-92f3a921a24e'::UUID,
          '22222',
          NULL,
          'blank',
          'Product Catalog Modernization',
          'Structured product catalog with improved search, attributes and publishing workflows.'
        ),
        (
          'b72b5bad-0a38-488c-b97e-4e1d429ae21a'::UUID,
          'sssss',
          NULL,
          'blank',
          'Warehouse Operations',
          'Operational tools for inbound stock, picking, packing and warehouse performance.'
        ),
        (
          '98aadacb-4a32-4261-8c07-c739f26da987'::UUID,
          'ssssss',
          NULL,
          'blank',
          'Sustainability Reporting',
          'Collection and reporting of sustainability indicators across suppliers and products.'
        ),
        (
          '4389f10e-59c4-4f8a-9cb2-d82613db3283'::UUID,
          'ssss',
          'sqqs',
          'exact',
          'Retail Partner Hub',
          'Shared workspace for retail partners, product launches and commercial materials.'
        ),
        (
          '503df73c-5ed8-4849-be79-8bd202f947ea'::UUID,
          'wwwws',
          'sswdsafasf',
          'exact',
          'Brand Campaign Planner',
          'Planning workspace for campaign calendars, creative approvals and channel delivery.'
        ),
        (
          '4a50b93c-6f7b-4cfd-b366-da071a3024b5'::UUID,
          'sasadasf',
          'rhjh',
          'exact',
          'Portfolio Website Refresh',
          'Redesign and implementation of the public portfolio and case study experience.'
        ),
        (
          '51c0e908-ddb3-434d-ac7a-b992df853914'::UUID,
          'asFAFSADGS',
          'DSAFAEf',
          'exact',
          'Beauty Commerce Analytics',
          'Analytics for product discovery, conversion and repeat purchase behavior.'
        ),
        (
          '8ba09b81-76c9-4b1c-be1d-cb16159b3b50'::UUID,
          'Warszgtaty firmowe - omó÷wienie nowych ciekawych tematów do oceny nowych tematów',
          NULL,
          'blank',
          'Corporate Workshop Program',
          'Planning and delivery of internal workshops, including topics, facilitators and feedback.'
        )
    )
    SELECT 1
    FROM (
      (
        SELECT * FROM expected
        EXCEPT
        SELECT project_id, old_name, old_description, old_description_mode, new_name, new_description
        FROM demo_seed_project_cleanup_catalog
      )
      UNION ALL
      (
        SELECT project_id, old_name, old_description, old_description_mode, new_name, new_description
        FROM demo_seed_project_cleanup_catalog
        EXCEPT
        SELECT * FROM expected
      )
    ) AS difference
  ) THEN
    RAISE EXCEPTION 'exact project cleanup catalog mismatch';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM demo_seed_project_cleanup_catalog AS cleanup
    JOIN demo_seed_project_catalog AS seed_project ON seed_project.project_id = cleanup.project_id
  ) THEN
    RAISE EXCEPTION 'project cleanup id collides with the main seed project catalog';
  END IF;

  IF (SELECT count(*) FROM demo_seed_project_user_catalog WHERE catalog_state = 'active') <> 480 THEN
    RAISE EXCEPTION 'all 480 project-user catalog_state must be active';
  END IF;

  IF EXISTS (
    WITH expected(
      profile_order,
      logical_key,
      first_name,
      last_name,
      email,
      color,
      reserved_role,
      is_super_admin,
      catalog_state
    ) AS (
      VALUES
        (1, 'amelia-nowak', 'Amelia', 'Nowak', 'demo+amelia-nowak@epoca.example.invalid', '#5B5BD6', 'Owner', true, 'active'),
        (2, 'jan-lewandowski', 'Jan', 'Lewandowski', 'demo+jan-lewandowski@epoca.example.invalid', '#2E90FA', 'Manager', true, 'active'),
        (3, 'marta-zielinska', 'Marta', 'Zielińska', 'demo+marta-zielinska@epoca.example.invalid', '#12B76A', NULL, false, 'active'),
        (4, 'piotr-wisniewski', 'Piotr', 'Wiśniewski', 'demo+piotr-wisniewski@epoca.example.invalid', '#F79009', NULL, false, 'active'),
        (5, 'alicja-kowalska', 'Alicja', 'Kowalska', 'demo+alicja-kowalska@epoca.example.invalid', '#F04438', NULL, false, 'active'),
        (6, 'kamil-wojcik', 'Kamil', 'Wójcik', 'demo+kamil-wojcik@epoca.example.invalid', '#7A5AF8', NULL, false, 'active'),
        (7, 'lena-kaminska', 'Lena', 'Kamińska', 'demo+lena-kaminska@epoca.example.invalid', '#06AED4', NULL, false, 'active'),
        (8, 'michal-dabrowski', 'Michał', 'Dąbrowski', 'demo+michal-dabrowski@epoca.example.invalid', '#EE46BC', NULL, false, 'active'),
        (9, 'zofia-kaczmarek', 'Zofia', 'Kaczmarek', 'demo+zofia-kaczmarek@epoca.example.invalid', '#5B5BD6', NULL, false, 'active'),
        (10, 'tomasz-mazur', 'Tomasz', 'Mazur', 'demo+tomasz-mazur@epoca.example.invalid', '#2E90FA', NULL, false, 'active'),
        (11, 'julia-krawczyk', 'Julia', 'Krawczyk', 'demo+julia-krawczyk@epoca.example.invalid', '#12B76A', NULL, false, 'active'),
        (12, 'pawel-piotrowski', 'Paweł', 'Piotrowski', 'demo+pawel-piotrowski@epoca.example.invalid', '#F79009', NULL, false, 'active'),
        (13, 'natalia-grabowska', 'Natalia', 'Grabowska', 'demo+natalia-grabowska@epoca.example.invalid', '#F04438', NULL, false, 'active'),
        (14, 'jakub-pawlak', 'Jakub', 'Pawlak', 'demo+jakub-pawlak@epoca.example.invalid', '#7A5AF8', NULL, false, 'active'),
        (15, 'emilia-michalska', 'Emilia', 'Michalska', 'demo+emilia-michalska@epoca.example.invalid', '#06AED4', NULL, false, 'active'),
        (16, 'filip-nowicki', 'Filip', 'Nowicki', 'demo+filip-nowicki@epoca.example.invalid', '#EE46BC', NULL, false, 'active'),
        (17, 'maja-adamczyk', 'Maja', 'Adamczyk', 'demo+maja-adamczyk@epoca.example.invalid', '#5B5BD6', NULL, false, 'active'),
        (18, 'wojciech-dudek', 'Wojciech', 'Dudek', 'demo+wojciech-dudek@epoca.example.invalid', '#2E90FA', NULL, false, 'active'),
        (19, 'hanna-wieczorek', 'Hanna', 'Wieczorek', 'demo+hanna-wieczorek@epoca.example.invalid', '#12B76A', NULL, false, 'active'),
        (20, 'adam-jankowski', 'Adam', 'Jankowski', 'demo+adam-jankowski@epoca.example.invalid', '#F79009', NULL, false, 'active'),
        (21, 'oliwia-krol', 'Oliwia', 'Król', 'demo+oliwia-krol@epoca.example.invalid', '#F04438', NULL, false, 'active'),
        (22, 'szymon-lis', 'Szymon', 'Lis', 'demo+szymon-lis@epoca.example.invalid', '#7A5AF8', NULL, false, 'active'),
        (23, 'laura-sadowska', 'Laura', 'Sadowska', 'demo+laura-sadowska@epoca.example.invalid', '#06AED4', NULL, false, 'active'),
        (24, 'krzysztof-baran', 'Krzysztof', 'Baran', 'demo+krzysztof-baran@epoca.example.invalid', '#EE46BC', NULL, false, 'active'),
        (25, 'iga-sikora', 'Iga', 'Sikora', 'demo+iga-sikora@epoca.example.invalid', '#5B5BD6', NULL, false, 'active'),
        (26, 'damian-gorski', 'Damian', 'Górski', 'demo+damian-gorski@epoca.example.invalid', '#2E90FA', NULL, false, 'active'),
        (27, 'sara-nilsson', 'Sara', 'Nilsson', 'demo+sara-nilsson@epoca.example.invalid', '#12B76A', NULL, false, 'active'),
        (28, 'lucas-martin', 'Lucas', 'Martin', 'demo+lucas-martin@epoca.example.invalid', '#F79009', NULL, false, 'active'),
        (29, 'nina-petrova', 'Nina', 'Petrova', 'demo+nina-petrova@epoca.example.invalid', '#F04438', NULL, false, 'active'),
        (30, 'mateo-costa', 'Mateo', 'Costa', 'demo+mateo-costa@epoca.example.invalid', '#7A5AF8', NULL, false, 'active')
    )
    SELECT 1
    FROM (
      (
        SELECT * FROM expected
        EXCEPT
        SELECT profile_order, logical_key, first_name, last_name, email, color, reserved_role, is_super_admin, catalog_state
        FROM demo_seed_user_catalog
      )
      UNION ALL
      (
        SELECT profile_order, logical_key, first_name, last_name, email, color, reserved_role, is_super_admin, catalog_state
        FROM demo_seed_user_catalog
        EXCEPT
        SELECT * FROM expected
      )
    ) AS difference
  ) THEN
    RAISE EXCEPTION 'exact user catalog mismatch';
  END IF;

  IF EXISTS (
    WITH expected(
      client_order,
      logical_key,
      name,
      client_type,
      color,
      texture,
      logo,
      status,
      catalog_state
    ) AS (
      VALUES
        (1, 'atelier-ops', 'Atelier Operations', 'Internal', '#5B5BD6', '', '', 'active', 'active'),
        (2, 'epoca-labs', 'Época Labs', 'Internal', '#2E90FA', '', '', 'active', 'active'),
        (3, 'northstar-health', 'Northstar Health', 'External', '#12B76A', '', '', 'active', 'active'),
        (4, 'lumen-retail', 'Lumen Retail', 'External', '#F79009', '', '', 'active', 'active'),
        (5, 'verdant-mobility', 'Verdant Mobility', 'External', '#06AED4', '', '', 'active', 'active'),
        (6, 'harbor-finance', 'Harbor Finance', 'External', '#7A5AF8', '', '', 'active', 'active'),
        (7, 'aurora-learning', 'Aurora Learning', 'External', '#EE46BC', '', '', 'active', 'active'),
        (8, 'pulse-commerce', 'Pulse Commerce', 'External', '#F04438', '', '', 'active', 'active'),
        (9, 'orbit-logistics', 'Orbit Logistics', 'External', '#667085', '', '', 'archived', 'active'),
        (10, 'ember-hospitality', 'Ember Hospitality', 'External', '#B54708', '', '', 'archived', 'active')
    )
    SELECT 1
    FROM (
      (
        SELECT * FROM expected
        EXCEPT
        SELECT client_order, logical_key, name, client_type, color, texture, logo, status, catalog_state
        FROM demo_seed_client_catalog
      )
      UNION ALL
      (
        SELECT client_order, logical_key, name, client_type, color, texture, logo, status, catalog_state
        FROM demo_seed_client_catalog
        EXCEPT
        SELECT * FROM expected
      )
    ) AS difference
  ) THEN
    RAISE EXCEPTION 'exact client catalog mismatch';
  END IF;

  IF EXISTS (
    WITH expected(
      project_order,
      logical_key,
      client_key,
      name,
      description,
      status,
      team_size,
      task_prefix,
      catalog_state
    ) AS (
      VALUES
        (1, 'delivery-operations-hub', 'atelier-ops', 'Delivery Operations Hub', 'Coordinates delivery planning, staffing, risks, and operational handoffs across active engagements.', 'active', 8, 'DOH', 'active'),
        (2, 'design-system-evolution', 'atelier-ops', 'Design System Evolution', 'Evolves shared design-system foundations, accessible components, and contribution workflows for product teams.', 'active', 6, 'DSE', 'active'),
        (3, 'time-intelligence', 'epoca-labs', 'Time Intelligence', 'Improves time tracking with dependable entry workflows, actionable trends, and capacity insights.', 'active', 10, 'TI', 'active'),
        (4, 'reporting-sandbox', 'epoca-labs', 'Reporting Sandbox', 'Provides a safe reporting workspace for prototyping metrics, dashboards, and decision-ready exports.', 'active', 5, 'RS', 'active'),
        (5, 'patient-portal', 'northstar-health', 'Patient Portal', 'Delivers a secure health portal where patients can manage appointments, messages, and care information.', 'active', 12, 'PP', 'active'),
        (6, 'care-analytics', 'northstar-health', 'Care Analytics', 'Turns clinical and operational data into clear analytics for care quality and service planning.', 'active', 7, 'CA', 'active'),
        (7, 'omnichannel-checkout', 'lumen-retail', 'Omnichannel Checkout', 'Unifies checkout journeys across web, mobile, and stores with consistent payment and fulfillment options.', 'active', 9, 'OC', 'active'),
        (8, 'retail-insights', 'lumen-retail', 'Retail Insights', 'Combines retail performance data into practical business intelligence for merchandising and store teams.', 'active', 5, 'RI', 'active'),
        (9, 'fleet-companion', 'verdant-mobility', 'Fleet Companion', 'Supports drivers and fleet operators with a focused mobility app for vehicles, trips, and service needs.', 'active', 8, 'FC', 'active'),
        (10, 'mobility-discovery', 'verdant-mobility', 'Mobility Discovery', 'Explores customer needs and viable concepts for the next generation of connected mobility services.', 'archived', 4, 'MD', 'active'),
        (11, 'risk-dashboard', 'harbor-finance', 'Risk Dashboard', 'Surfaces portfolio exposure, control signals, and emerging financial risks in one auditable dashboard.', 'active', 6, 'RD', 'active'),
        (12, 'open-banking-integration', 'harbor-finance', 'Open Banking Integration', 'Connects regulated banking providers through resilient consent, account, and transaction integration flows.', 'active', 11, 'OBI', 'active'),
        (13, 'learning-experience', 'aurora-learning', 'Learning Experience', 'Creates an engaging learning experience for discovering content, tracking progress, and supporting instructors.', 'active', 7, 'LE', 'active'),
        (14, 'commerce-growth', 'pulse-commerce', 'Commerce Growth', 'Tests and scales commerce growth opportunities across acquisition, conversion, retention, and lifecycle messaging.', 'active', 5, 'CG', 'active'),
        (15, 'dispatch-optimizer', 'orbit-logistics', 'Dispatch Optimizer', 'Optimizes logistics dispatch decisions using route constraints, shipment priorities, and live operating conditions.', 'archived', 6, 'DOP', 'active'),
        (16, 'guest-journey', 'ember-hospitality', 'Guest Journey', 'Maps and improves the hospitality journey from reservation and arrival through the post-stay relationship.', 'archived', 3, 'GJ', 'active')
    )
    SELECT 1
    FROM (
      (
        SELECT * FROM expected
        EXCEPT
        SELECT project_order, logical_key, client_key, name, description, status, team_size, task_prefix, catalog_state
        FROM demo_seed_project_catalog
      )
      UNION ALL
      (
        SELECT project_order, logical_key, client_key, name, description, status, team_size, task_prefix, catalog_state
        FROM demo_seed_project_catalog
        EXCEPT
        SELECT * FROM expected
      )
    ) AS difference
  ) THEN
    RAISE EXCEPTION 'exact project catalog mismatch';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM demo_seed_user_catalog AS profile
    CROSS JOIN demo_seed_catalog_context AS context
    WHERE profile.user_id IS DISTINCT FROM public.uuid_generate_v5(context.namespace_uuid, 'user:' || profile.logical_key)
       OR profile.identity_id IS DISTINCT FROM public.uuid_generate_v5(context.namespace_uuid, 'identity:' || profile.logical_key)
       OR profile.membership_id IS DISTINCT FROM public.uuid_generate_v5(context.namespace_uuid, 'organization-user:' || profile.logical_key)
  ) THEN
    RAISE EXCEPTION 'user UUID formula mismatch';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM demo_seed_client_catalog AS client
    CROSS JOIN demo_seed_catalog_context AS context
    WHERE client.client_id IS DISTINCT FROM public.uuid_generate_v5(context.namespace_uuid, 'client:' || client.logical_key)
  ) THEN
    RAISE EXCEPTION 'client UUID formula mismatch';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM demo_seed_project_catalog AS project
    CROSS JOIN demo_seed_catalog_context AS context
    WHERE project.project_id IS DISTINCT FROM public.uuid_generate_v5(context.namespace_uuid, 'project:' || project.logical_key)
       OR project.client_id IS DISTINCT FROM public.uuid_generate_v5(context.namespace_uuid, 'client:' || project.client_key)
  ) THEN
    RAISE EXCEPTION 'project UUID formula mismatch';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM demo_seed_project_user_catalog AS project_user
    CROSS JOIN demo_seed_catalog_context AS context
    WHERE project_user.logical_key IS DISTINCT FROM 'project-user:' || project_user.project_key || ':' || project_user.user_key
       OR project_user.project_user_id IS DISTINCT FROM public.uuid_generate_v5(context.namespace_uuid, project_user.logical_key)
       OR project_user.project_id IS DISTINCT FROM public.uuid_generate_v5(context.namespace_uuid, 'project:' || project_user.project_key)
       OR project_user.user_id IS DISTINCT FROM public.uuid_generate_v5(context.namespace_uuid, 'user:' || project_user.user_key)
  ) THEN
    RAISE EXCEPTION 'project-user UUID formula mismatch';
  END IF;
END
$catalog_contract$;
SQL
}

emit_materialized_key_assertions() {
  local catalog=$1

  cat <<'SQL'
CREATE TEMP TABLE demo_seed_expected_issued_key
(
  logical_key TEXT NOT NULL
) ON COMMIT DROP;
COPY demo_seed_expected_issued_key (logical_key) FROM STDIN;
SQL
  extract_issued_keys "$catalog" | LC_ALL=C sort
  printf '\\.\n'
  cat <<'SQL'
CREATE TEMP TABLE demo_seed_materialized_issued_key
(
  logical_key TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO demo_seed_materialized_issued_key (logical_key)
SELECT durable.logical_key
FROM (
  SELECT 'organization:' || organization_key AS logical_key
  FROM demo_seed_catalog_context
  UNION ALL
  SELECT 'user:' || logical_key
  FROM demo_seed_user_catalog
  UNION ALL
  SELECT 'identity:' || logical_key
  FROM demo_seed_user_catalog
  UNION ALL
  SELECT 'organization-user:' || logical_key
  FROM demo_seed_user_catalog
  UNION ALL
  SELECT 'client:' || logical_key
  FROM demo_seed_client_catalog
  UNION ALL
  SELECT 'project:' || logical_key
  FROM demo_seed_project_catalog
  UNION ALL
  SELECT logical_key
  FROM demo_seed_project_user_catalog
  UNION ALL
  SELECT 'generator:' || generator_name || ':v' || version || ':' || max_slot
  FROM demo_seed_generator_catalog
) AS durable
ORDER BY durable.logical_key;

DO $issued_key_contract$
BEGIN
  IF (SELECT array_agg(logical_key ORDER BY logical_key) FROM demo_seed_materialized_issued_key)
       IS DISTINCT FROM
     (SELECT array_agg(logical_key ORDER BY logical_key) FROM demo_seed_expected_issued_key) THEN
    RAISE EXCEPTION 'durable issued-key mismatch between materialized catalog and comments';
  END IF;

  IF (SELECT count(*) FROM demo_seed_generator_catalog) <> 1
     OR NOT EXISTS (
       SELECT 1
       FROM demo_seed_generator_catalog
       WHERE generator_name = 'work-unit'
         AND version = 1
         AND max_slot = 64
         AND catalog_state = 'active'
     ) THEN
    RAISE EXCEPTION 'exact generator catalog mismatch';
  END IF;
END
$issued_key_contract$;

DROP TABLE demo_seed_materialized_issued_key;
DROP TABLE demo_seed_expected_issued_key;
SQL
}

emit_shadow_function_setup() {
  cat <<'SQL'
DO $shadow_precondition$
BEGIN
  IF to_regnamespace('demo_seed_uuid_shadow') IS NOT NULL THEN
    RAISE EXCEPTION 'shadow probe schema already exists';
  END IF;
END
$shadow_precondition$;

CREATE SCHEMA demo_seed_uuid_shadow;
CREATE FUNCTION demo_seed_uuid_shadow.uuid_generate_v5(UUID, TEXT)
RETURNS UUID
LANGUAGE SQL
IMMUTABLE
STRICT
AS $shadow_function$
  SELECT '00000000-0000-0000-0000-000000000000'::UUID
$shadow_function$;
SET LOCAL search_path = demo_seed_uuid_shadow, public, pg_catalog;

DO $shadow_resolution$
BEGIN
  IF to_regprocedure('uuid_generate_v5(uuid,text)') IS DISTINCT FROM
     to_regprocedure('demo_seed_uuid_shadow.uuid_generate_v5(uuid,text)') THEN
    RAISE EXCEPTION 'shadow function did not take search_path precedence';
  END IF;
END
$shadow_resolution$;
SQL
}

emit_shadow_function_cleanup_assertion() {
  cat <<'SQL'
DO $shadow_cleanup$
BEGIN
  IF to_regnamespace('demo_seed_uuid_shadow') IS NOT NULL THEN
    RAISE EXCEPTION 'shadow probe objects survived rollback';
  END IF;
END
$shadow_cleanup$;
SELECT 'SHADOW_PROBE_ROLLED_BACK' AS catalog_shadow_probe;
SQL
}

emit_materialization_sql() {
  local catalog=$1
  local use_shadow=${2:-false}

  cat <<'SQL'
DO $catalog_database_preflight$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_extension AS extension
    JOIN pg_depend AS dependency
      ON dependency.refclassid = 'pg_extension'::regclass
     AND dependency.refobjid = extension.oid
     AND dependency.classid = 'pg_proc'::regclass
     AND dependency.deptype = 'e'
    JOIN pg_proc AS procedure
      ON procedure.oid = dependency.objid
    JOIN pg_namespace AS namespace
      ON namespace.oid = procedure.pronamespace
    WHERE extension.extname = 'uuid-ossp'
      AND extension.extnamespace = namespace.oid
      AND namespace.nspname = 'public'
      AND procedure.oid = to_regprocedure('public.uuid_generate_v5(uuid,text)')
  ) THEN
    RAISE EXCEPTION 'catalog database is not migrated: public.uuid_generate_v5(uuid,text) is not owned by public uuid-ossp';
  END IF;
END
$catalog_database_preflight$;
SQL
  printf 'BEGIN;\n'
  if [ "$use_shadow" = true ]; then
    emit_shadow_function_setup
  fi
  cat "$CLEANUP_CATALOG_PATH"
  sed '/^\\ir project-cleanup-catalog[.]sql$/d' "$catalog"
  printf '\n'
  cat "$CLEANUP_CATALOG_PATH"
  sed '/^\\ir project-cleanup-catalog[.]sql$/d' "$catalog"
  printf '\n'
  emit_materialization_assertions
  emit_materialized_key_assertions "$catalog"
  printf 'ROLLBACK;\n'
  if [ "$use_shadow" = true ]; then
    emit_shadow_function_cleanup_assertion
  fi
}

configure_materializer() {
  if [ "${DEMO_SEED_CATALOG_TEST_BACKEND:-}" = docker ]; then
    MATERIALIZER_KIND=docker
  elif [ "${DEMO_SEED_CATALOG_TEST_BACKEND:-}" = psql ]; then
    MATERIALIZER_KIND=psql
  elif command -v psql >/dev/null 2>&1; then
    MATERIALIZER_KIND=psql
  elif command -v docker >/dev/null 2>&1; then
    MATERIALIZER_KIND=docker
  else
    fail 'catalog materialization requires psql or docker'
  fi

  if [ "$MATERIALIZER_KIND" = psql ]; then
    MATERIALIZER_DATABASE=${DEMO_SEED_CATALOG_TEST_DATABASE:-${PGDATABASE:-}}
    [ -n "$MATERIALIZER_DATABASE" ] || \
      fail 'host psql catalog materialization requires an explicit PGDATABASE or DEMO_SEED_CATALOG_TEST_DATABASE'
  fi
}

run_materialization_backend() {
  case "$MATERIALIZER_KIND" in
    psql)
      psql -X --set ON_ERROR_STOP=1 --dbname "$MATERIALIZER_DATABASE"
      ;;
    docker)
      docker exec -i \
        "${DEMO_SEED_CATALOG_TEST_CONTAINER:-epoca-ct-532-postgres}" \
        psql -X --set ON_ERROR_STOP=1 \
          --username "${DEMO_SEED_CATALOG_TEST_DOCKER_USER:-postgres}" \
          --dbname "${DEMO_SEED_CATALOG_TEST_DOCKER_DATABASE:-epoca_ct532}"
      ;;
    *)
      fail "unknown catalog materializer: $MATERIALIZER_KIND"
      ;;
  esac
}

validate_materialized_catalog() {
  local catalog=$1
  local use_shadow=${2:-false}
  local materialization_status

  set +e
  LAST_MATERIALIZATION_OUTPUT=$(emit_materialization_sql "$catalog" "$use_shadow" | run_materialization_backend 2>&1)
  materialization_status=$?
  set -e
  return "$materialization_status"
}

expect_materialization_rejected() {
  local label=$1
  local expected_message=$2
  local catalog=$3

  if validate_materialized_catalog "$catalog"; then
    fail "materialized catalog contract accepted mutation: $label"
  fi
  assert_contains "$LAST_MATERIALIZATION_OUTPUT" "$expected_message" "$label materialization failure"
}

assert_host_backend_requires_database() {
  local output
  local status

  set +e
  output=$(
    {
      unset PGDATABASE DEMO_SEED_CATALOG_TEST_DATABASE
      export DEMO_SEED_CATALOG_TEST_BACKEND=psql
      configure_materializer
    } 2>&1
  )
  status=$?
  set -e

  if [ "$status" -eq 0 ]; then
    fail 'host psql backend accepted an implicit default database'
  fi
  assert_contains "$output" 'explicit PGDATABASE' 'host psql missing-database failure'
}

snapshot_baseline() {
  (
    cksum "$CATALOG_PATH"
    cksum "$CLEANUP_CATALOG_PATH"
    find "$HISTORY_DIR" -type f -name '*-issued-keys.txt' -exec cksum {} \;
  ) | LC_ALL=C sort
}

expect_mutation_rejected() {
  local label=$1
  local expected_message=$2
  local catalog=$3
  local history_dir=$4
  local output
  local status

  set +e
  output=$(validate_catalog "$catalog" "$history_dir" 2>&1)
  status=$?
  set -e

  if [ "$status" -eq 0 ]; then
    fail "catalog contract accepted mutation: $label"
  fi
  assert_eq "$expected_message" "$output" "$label exact static failure"
}

if [ ! -f "$CATALOG_PATH" ] || [ ! -d "$HISTORY_DIR" ]; then
  validate_catalog "$CATALOG_PATH" "$HISTORY_DIR"
fi
[ -f "$CLEANUP_CATALOG_PATH" ] || fail "missing project cleanup catalog: $CLEANUP_CATALOG_PATH"

BASELINE_BEFORE=$(snapshot_baseline)
assert_uuid_calls_are_schema_qualified
validate_catalog "$CATALOG_PATH" "$HISTORY_DIR"
configure_materializer
if ! validate_materialized_catalog "$CATALOG_PATH"; then
  printf '%s\n' "$LAST_MATERIALIZATION_OUTPUT" >&2
  fail 'baseline catalog did not materialize and validate'
fi
assert_contains "$LAST_MATERIALIZATION_OUTPUT" 'ROLLBACK' 'baseline catalog must finish with an explicit rollback'
if ! validate_materialized_catalog "$CATALOG_PATH" true; then
  printf '%s\n' "$LAST_MATERIALIZATION_OUTPUT" >&2
  fail 'catalog failed the rollback-only shadow-function search_path probe'
fi
assert_contains "$LAST_MATERIALIZATION_OUTPUT" 'SHADOW_PROBE_ROLLED_BACK' 'shadow probe rollback evidence'

MUTATION_DIR="$TMP_DIR/mutations"
mkdir -p "$MUTATION_DIR/baseline-history"
cp "$HISTORY_DIR"/*-issued-keys.txt "$MUTATION_DIR/baseline-history/"

NON_C_LOCALE=
case "$(locale -a 2>/dev/null)" in
  *en_US.utf8*) NON_C_LOCALE=en_US.utf8 ;;
  *en_US.UTF-8*) NON_C_LOCALE=en_US.UTF-8 ;;
esac
if [ -n "$NON_C_LOCALE" ]; then
  cp "$CATALOG_PATH" "$MUTATION_DIR/non-c-locale.sql"
  printf '\n-- issued-key: client:Zebra-locale-probe\n' >>"$MUTATION_DIR/non-c-locale.sql"
  if ! (
    export LC_ALL="$NON_C_LOCALE"
    validate_catalog "$MUTATION_DIR/non-c-locale.sql" "$MUTATION_DIR/baseline-history" >/dev/null
  ); then
    fail "catalog history subset comparison depends on locale: $NON_C_LOCALE"
  fi
fi

sed '/^[[:space:]]*-- issued-key: organization:iteratorstesting[[:space:]]*$/d' "$CATALOG_PATH" >"$MUTATION_DIR/removed-key.sql"
expect_mutation_rejected \
  'removed historical key' \
  'catalog contract: historical issued key disappeared from catalog: organization:iteratorstesting' \
  "$MUTATION_DIR/removed-key.sql" \
  "$MUTATION_DIR/baseline-history"

cp "$CATALOG_PATH" "$MUTATION_DIR/duplicate-key.sql"
printf '\n-- issued-key: organization:iteratorstesting\n' >>"$MUTATION_DIR/duplicate-key.sql"
expect_mutation_rejected \
  'duplicate current key' \
  'catalog contract: duplicate current issued-key comment: organization:iteratorstesting' \
  "$MUTATION_DIR/duplicate-key.sql" \
  "$MUTATION_DIR/baseline-history"

mkdir -p "$MUTATION_DIR/unsorted-history"
cp "$HISTORY_DIR"/*-issued-keys.txt "$MUTATION_DIR/unsorted-history/"
LC_ALL=C sort -r "$HISTORY_DIR/v1-issued-keys.txt" >"$MUTATION_DIR/unsorted-history/v1-issued-keys.txt"
expect_mutation_rejected \
  'unsorted history' \
  'catalog contract: history file is not sorted and unique: v1-issued-keys.txt' \
  "$CATALOG_PATH" \
  "$MUTATION_DIR/unsorted-history"

mkdir -p "$MUTATION_DIR/missing-project-user-history"
sed 's/project-user:delivery-operations-hub:amelia-nowak/project-user:zz-mutation-only:amelia-nowak/' "$CATALOG_PATH" >"$MUTATION_DIR/missing-project-user.sql"
sed 's/project-user:delivery-operations-hub:amelia-nowak/project-user:zz-mutation-only:amelia-nowak/' "$HISTORY_DIR/v1-issued-keys.txt" \
  | LC_ALL=C sort >"$MUTATION_DIR/missing-project-user-history/v1-issued-keys.txt"
expect_mutation_rejected \
  'missing project-user cross-product member' \
  'catalog contract: catalog is missing project-user cross-product member: project-user:delivery-operations-hub:amelia-nowak' \
  "$MUTATION_DIR/missing-project-user.sql" \
  "$MUTATION_DIR/missing-project-user-history"

sed "/    (29, 'nina-petrova'/d" "$CATALOG_PATH" >"$MUTATION_DIR/missing-executable-user.sql"
validate_catalog "$MUTATION_DIR/missing-executable-user.sql" "$MUTATION_DIR/baseline-history" >/dev/null
expect_materialization_rejected \
  'missing executable user VALUES row' \
  'catalog row counts mismatch' \
  "$MUTATION_DIR/missing-executable-user.sql"

sed "/INSERT INTO demo_seed_project_user_catalog/,/CREATE TEMP TABLE demo_seed_generator_catalog/ s/    'active'/    'tombstone'/" \
  "$CATALOG_PATH" >"$MUTATION_DIR/tombstoned-project-user.sql"
validate_catalog "$MUTATION_DIR/tombstoned-project-user.sql" "$MUTATION_DIR/baseline-history" >/dev/null
expect_materialization_rejected \
  'tombstoned project-user ownership keys' \
  'project-user catalog_state must be active' \
  "$MUTATION_DIR/tombstoned-project-user.sql"

sed "s/'2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::UUID/'11111111-2222-5333-8444-555555555555'::UUID/g" \
  "$CATALOG_PATH" >"$MUTATION_DIR/changed-executable-namespace.sql"
printf '\n-- expected namespace remains 2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532\n' \
  >>"$MUTATION_DIR/changed-executable-namespace.sql"
validate_catalog "$MUTATION_DIR/changed-executable-namespace.sql" "$MUTATION_DIR/baseline-history" >/dev/null
expect_materialization_rejected \
  'changed executable namespace' \
  'exact catalog context mismatch' \
  "$MUTATION_DIR/changed-executable-namespace.sql"

cp "$CATALOG_PATH" "$MUTATION_DIR/extra-issued-comment.sql"
printf '\n-- issued-key: client:mutation-only\n' >>"$MUTATION_DIR/extra-issued-comment.sql"
validate_catalog "$MUTATION_DIR/extra-issued-comment.sql" "$MUTATION_DIR/baseline-history" >/dev/null
expect_materialization_rejected \
  'issued comment without executable data' \
  'durable issued-key mismatch' \
  "$MUTATION_DIR/extra-issued-comment.sql"

sed "s/VALUES ('work-unit', 1, 64, 'active');/VALUES ('mutation-work-unit', 1, 64, 'active');/" \
  "$CATALOG_PATH" >"$MUTATION_DIR/unissued-executable-key.sql"
validate_catalog "$MUTATION_DIR/unissued-executable-key.sql" "$MUTATION_DIR/baseline-history" >/dev/null
expect_materialization_rejected \
  'executable data without an issued comment' \
  'durable issued-key mismatch' \
  "$MUTATION_DIR/unissued-executable-key.sql"

BASELINE_AFTER=$(snapshot_baseline)
assert_eq "$BASELINE_BEFORE" "$BASELINE_AFTER" 'catalog baseline must remain byte-stable during contract test'
assert_host_backend_requires_database

pass 'catalog history is append-only'
