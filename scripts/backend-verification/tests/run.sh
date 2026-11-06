#!/usr/bin/env bash

set -euo pipefail

TEST_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
REPO_ROOT=$(CDPATH= cd -- "$TEST_DIR/../../.." && pwd -P)
TMP_ROOT=$(mktemp -d)
trap 'rm -rf -- "$TMP_ROOT"' EXIT

SELECTED_CASE=all

die() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

if [ "$#" -gt 0 ]; then
  if [ "$#" -ne 2 ] || [ "$1" != --case ]; then
    die "Usage: $0 [--case fast|dependency-policy|database-snapshot|contracts|jvm-images|migration-data-drift|runner|runtime-jdbc]"
  fi
  SELECTED_CASE=$2
fi

case "$SELECTED_CASE" in
  all|fast|dependency-policy|database-snapshot|contracts|jvm-images|migration-data-drift|runner|runtime-jdbc) ;;
  *) die "Unknown case group: $SELECTED_CASE" ;;
esac

pass() {
  printf 'PASS: %s\n' "$1"
}

expect_success() {
  local case_name=$1
  shift
  if ! "$@" >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr"; then
    sed -n '1,120p' "$TMP_ROOT/stderr" >&2
    die "$case_name unexpectedly failed"
  fi
  pass "$case_name"
}

expect_failure() {
  local case_name=$1
  local diagnostic=$2
  shift 2
  if "$@" >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr"; then
    die "$case_name unexpectedly passed"
  fi
  if ! grep -F -- "$diagnostic" "$TMP_ROOT/stderr" >/dev/null; then
    sed -n '1,120p' "$TMP_ROOT/stderr" >&2
    die "$case_name did not report: $diagnostic"
  fi
  pass "$case_name"
}

write_dependency_fixture() {
  local output=$1
  local actor_revision=$2
  local http_revision=$3
  {
    printf 'configuration\torganization\tname\trevision\tevicted\n'
    printf 'compile\torg.apache.pekko\tpekko-actor_2.13\t%s\tfalse\n' "$actor_revision"
    printf 'compile\torg.apache.pekko\tpekko-http_2.13\t%s\tfalse\n' "$http_revision"
    printf 'compile\torg.typelevel\tcats-core_2.13\t2.10.0\tfalse\n'
  } >"$output"
}

run_dependency_cases() {
  local fixture_root="$TMP_ROOT/dependency-policy"
  local test_repo="$fixture_root/repo"
  local actual="$fixture_root/actual.tsv"
  local accepted="$fixture_root/accepted.tsv"
  local policy="$fixture_root/policy.tsv"
  local verifier="$test_repo/scripts/backend-verification/verify-dependencies.sh"

  mkdir -p "$test_repo/scripts/backend-verification" "$test_repo/project"
  ln -s "$REPO_ROOT/scripts/backend-verification/verify-dependencies.sh" "$verifier"
  printf 'sbt.version=1.8.3\n' >"$test_repo/project/build.properties"
  {
    printf 'family\torganization\tname_pattern\texpected_revision\n'
    printf 'pekko-http\torg.apache.pekko\t^pekko-(http.*|parsing)_2[.]13$\t1.4.0\n'
    printf 'pekko-core\torg.apache.pekko\t^pekko-(actor|cluster|coordination|discovery|distributed-data|event|protobuf-v3|remote|serialization-jackson|slf4j|stream|testkit).*_2[.]13$\t1.7.0\n'
  } >"$policy"

  write_dependency_fixture "$accepted" 1.7.0 1.4.0
  cp "$accepted" "$actual"
  expect_success 'accepted Pekko manifest' \
    bash "$verifier" --actual "$actual" --accepted "$accepted" --policy "$policy"

  write_dependency_fixture "$actual" 1.6.9 1.4.0
  expect_failure 'Pekko core drift rejected' 'Pekko policy violation' \
    bash "$verifier" --actual "$actual" --accepted "$accepted" --policy "$policy"

  write_dependency_fixture "$actual" 1.7.0 1.3.9
  expect_failure 'Pekko HTTP drift rejected' 'Pekko policy violation' \
    bash "$verifier" --actual "$actual" --accepted "$accepted" --policy "$policy"

  cp "$accepted" "$actual"
  printf 'compile\tcom.typesafe.akka\takka-actor_2.13\t2.6.20\tfalse\n' >>"$actual"
  expect_failure 'legacy Akka rejected' 'com.typesafe.akka is forbidden' \
    bash "$verifier" --actual "$actual" --accepted "$accepted" --policy "$policy"

  cp "$accepted" "$actual"
  printf 'compile\torg.apache.pekko\tpekko-unapproved_2.13\t1.7.0\tfalse\n' >>"$actual"
  expect_failure 'unapproved Pekko coordinate rejected' 'Pekko policy violation: unapproved coordinate' \
    bash "$verifier" --actual "$actual" --accepted "$accepted" --policy "$policy"

  cp "$accepted" "$actual"
  printf 'compile\torg.apache.pekko\tpekko-actor_2.13\t1.6.9\ttrue\n' >>"$actual"
  cp "$actual" "$accepted"
  expect_success 'evicted Pekko revision does not override selected policy' \
    bash "$verifier" --actual "$actual" --accepted "$accepted" --policy "$policy"
  write_dependency_fixture "$accepted" 1.7.0 1.4.0

  cp "$accepted" "$actual"
  printf 'compile\torg.typelevel\tcats-effect_3\t3.6.3\tfalse\n' >>"$actual"
  expect_failure 'Scala 3 artifact rejected' 'Scala 3 artifact is forbidden' \
    bash "$verifier" --actual "$actual" --accepted "$accepted" --policy "$policy"

  cp "$accepted" "$actual"
  printf 'sbt.version=2.0.0\n' >"$test_repo/project/build.properties"
  expect_failure 'sbt 2 rejected' 'sbt 2 is forbidden' \
    bash "$verifier" --actual "$actual" --accepted "$accepted" --policy "$policy"

  printf '  sbt.version \t= \t2.0.0 \t\n' >"$test_repo/project/build.properties"
  expect_failure 'sbt 2 with horizontal whitespace rejected' 'sbt 2 is forbidden' \
    bash "$verifier" --actual "$actual" --accepted "$accepted" --policy "$policy"
  printf '%s\n' \
    '# sbt.version = 2.0.0' \
    'unrelated.sbt.version = 2.0.0' \
    'sbt.version = 1.8.3' >"$test_repo/project/build.properties"

  cp "$accepted" "$actual"
  printf 'test\torg.typelevel\tcats-core_2.13\t2.9.0\tfalse\n' >>"$actual"
  cp "$actual" "$accepted"
  expect_success 'different revisions across configurations accepted' \
    bash "$verifier" --actual "$actual" --accepted "$accepted" --policy "$policy"

  write_dependency_fixture "$accepted" 1.7.0 1.4.0
  cp "$accepted" "$actual"
  printf 'compile\torg.typelevel\tcats-core_2.13\t2.9.0\tfalse\n' >>"$actual"
  expect_failure 'duplicate selected-family resolution rejected' 'multiple non-evicted revisions' \
    bash "$verifier" --actual "$actual" --accepted "$accepted" --policy "$policy"

  cp "$accepted" "$actual"
  printf 'compile\torg.example\textra_2.13\t1.0.0\tfalse\n' >>"$actual"
  expect_failure 'unexpected resolved row rejected' 'resolved manifest differs from accepted manifest' \
    bash "$verifier" --actual "$actual" --accepted "$accepted" --policy "$policy"
}

install_fake_psql() {
  local fake_bin=$1
  mkdir -p "$fake_bin"
  cp /dev/null "$fake_bin/psql"
  chmod +x "$fake_bin/psql"
  apply_fake_psql="$fake_bin/psql"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'set -euo pipefail' \
    'database=' \
    'saw_command=false' \
    'marker=$(dirname -- "$0")/invocations' \
    'override_marker=$(dirname -- "$0")/connection-overrides' \
    'isolated_marker=$(dirname -- "$0")/connection-environment-isolated' \
    'if [ "${FAKE_PSQL_REQUIRE_ISOLATED:-false}" = true ]; then' \
    '  for connection_variable in PGHOSTADDR PGHOST PGPORT PGDATABASE PGSERVICE PGSERVICEFILE; do' \
    '    if printenv "$connection_variable" >/dev/null 2>&1; then' \
    '      printf "%s\\n" "$connection_variable" >>"$override_marker"' \
    '      exit 72' \
    '    fi' \
    '  done' \
    '  [ "${PGPASSWORD:-}" = test_password ] || exit 73' \
    '  touch "$isolated_marker"' \
    'fi' \
    'printf "1\\n" >>"$marker"' \
    'while [ "$#" -gt 0 ]; do' \
    '  case "$1" in' \
    '    --dbname) database=$2; shift 2 ;;' \
    '    --command) saw_command=true; shift 2 ;;' \
    '    *) shift ;;' \
    '  esac' \
    'done' \
    '# PostgreSQL 13.4 psql exposes only the final result when one --command' \
    '# contains a multi-statement BEGIN/SELECT/COMMIT string.' \
    'if [ "$saw_command" = true ]; then' \
    '  exit 0' \
    'fi' \
    'sql=$(cat)' \
    '[[ "$sql" == *"BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;"* ]]' \
    '[[ "$sql" == *"pg_catalog.pg_class"* ]]' \
    '[[ "$sql" == *"row_to_json(source_row)::text"* ]]' \
    '[[ "$sql" == *"ORDER BY row_json"* ]]' \
    '[[ "$sql" == *"\\gexec"* ]]' \
    '[[ "$sql" == *"COMMIT;"* ]]' \
    'case "$database" in' \
    '  */empty) exit 0 ;;' \
    '  */changed) public_hash=cccccccccccccccccccccccccccccccc ;;' \
    '  *) public_hash=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa ;;' \
    'esac' \
    'printf "public.order\\t2\\t%s\\nquartz.qrtz_job_details\\t1\\tbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\\n" "$public_hash"' >"$apply_fake_psql"
}

run_database_cases() {
  local fixture_root="$TMP_ROOT/database-snapshot"
  local fake_bin="$fixture_root/bin"
  local snapshot_a="$fixture_root/a.tsv"
  local snapshot_b="$fixture_root/b.tsv"
  local snapshot_environment="$fixture_root/environment.tsv"
  local snapshot_ipv6="$fixture_root/ipv6.tsv"
  local snapshot_changed="$fixture_root/changed.tsv"
  local snapshot_empty="$fixture_root/empty.tsv"
  local snapshot_unsafe="$fixture_root/unsafe.tsv"
  local unsafe_status
  local unsafe_url

  install_fake_psql "$fake_bin"

  printf 'existing snapshot sentinel\n' >"$snapshot_empty"
  unsafe_status=0
  PATH="$fake_bin:$PATH" bash "$REPO_ROOT/scripts/backend-verification/snapshot-database.sh" \
    --url postgresql://localhost/empty --output "$snapshot_empty" \
    >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr" || unsafe_status=$?
  grep -qx 'existing snapshot sentinel' "$snapshot_empty" || \
    die 'empty database result replaced the existing snapshot'
  [ "$unsafe_status" -ne 0 ] || die 'empty database result unexpectedly passed'
  pass 'database snapshot rejects an empty result without replacing output'

  for unsafe_url in \
    'postgresql://snapshot_user:topsecret@127.0.0.1/demo?host=staging.db.internal&port=5432' \
    'postgresql://127.0.0.1/demo?hostaddr=203.0.113.10' \
    'postgresql://localhost/demo#host=staging.db.internal' \
    'postgresql://localhost/demo/extra' \
    'postgresql://%31%32%37.0.0.1/demo' \
    'postgresql://snapshot_user@user-e375246078e9@example.test/demo' \
    'postgresql://127.0.0.1,staging.db.internal/demo' \
    'postgresql://127.0.0.1:0/demo' \
    'postgresql://127.0.0.1:65536/demo'
  do
    rm -f -- "$fake_bin/invocations" "$snapshot_unsafe"
    unsafe_status=0
    PATH="$fake_bin:$PATH" bash "$REPO_ROOT/scripts/backend-verification/snapshot-database.sh" \
      --url "$unsafe_url" --output "$snapshot_unsafe" \
      >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr" || unsafe_status=$?
    [ ! -e "$fake_bin/invocations" ] || \
      die 'unsafe database URL invoked psql before rejection'
    [ "$unsafe_status" -ne 0 ] || die 'unsafe database URL unexpectedly passed'
    if grep -F -- 'topsecret' "$TMP_ROOT/stdout" "$TMP_ROOT/stderr" >/dev/null; then
      die 'database URL credentials leaked to output'
    fi
  done
  pass 'database snapshot rejects ambiguous or overridable URLs before psql'

  unsafe_status=0
  env \
    FAKE_PSQL_REQUIRE_ISOLATED=true \
    PGPASSWORD=test_password \
    PGHOSTADDR=203.0.113.10 \
    PGHOST=staging.db.internal \
    PGPORT=6432 \
    PGDATABASE=staging \
    PGSERVICE=staging \
    PGSERVICEFILE="$fixture_root/staging.pg_service.conf" \
    PATH="$fake_bin:$PATH" \
    bash "$REPO_ROOT/scripts/backend-verification/snapshot-database.sh" \
      --url postgresql://snapshot_user@127.0.0.1:55438/epoca_snapshot \
      --output "$snapshot_environment" \
      >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr" || unsafe_status=$?
  if [ -e "$fake_bin/connection-overrides" ]; then
    die 'libpq target-selection environment reached psql'
  fi
  [ "$unsafe_status" -eq 0 ] || die 'isolated connection environment snapshot failed'
  [ -e "$fake_bin/connection-environment-isolated" ] || \
    die 'fake psql did not verify the isolated connection environment'
  pass 'database snapshot isolates libpq target-selection environment'
  rm -f -- "$fake_bin/invocations"

  PATH="$fake_bin:$PATH" bash "$REPO_ROOT/scripts/backend-verification/snapshot-database.sh" \
    --url postgresql://snapshot_user:test_password@localhost/order_a \
    --output "$snapshot_a" >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr" || \
    die 'database snapshot fixture A failed'
  grep -qx $'public.order\t2\taaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' "$snapshot_a" || \
    die 'PostgreSQL 13.4 psql result handling dropped snapshot rows'
  [ "$(wc -l <"$fake_bin/invocations")" -eq 1 ] || \
    die 'database snapshot must use one psql session and one read-only transaction'
  pass 'database snapshot supports PostgreSQL 13.4 psql result handling'
  PATH="$fake_bin:$PATH" bash "$REPO_ROOT/scripts/backend-verification/snapshot-database.sh" \
    --url postgresql://postgres@127.0.0.1:55438/epoca_snapshot \
    --output "$snapshot_b" >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr" || \
    die 'database snapshot fixture B failed'
  cmp -s "$snapshot_a" "$snapshot_b" || die 'physical insertion order changed the snapshot'
  pass 'database snapshot ignores physical insertion order'

  PATH="$fake_bin:$PATH" bash "$REPO_ROOT/scripts/backend-verification/snapshot-database.sh" \
    --url 'postgresql://snapshot_user:test_password@[::1]:5432/ipv6' \
    --output "$snapshot_ipv6" >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr" || \
    die 'bracketed IPv6 loopback URL was rejected'
  cmp -s "$snapshot_a" "$snapshot_ipv6" || \
    die 'bracketed IPv6 loopback changed the snapshot'
  pass 'database snapshot accepts bracketed IPv6 loopback'

  PATH="$fake_bin:$PATH" bash "$REPO_ROOT/scripts/backend-verification/snapshot-database.sh" \
    --url postgresql://localhost/changed --output "$snapshot_changed" >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr" || \
    die 'changed database snapshot fixture failed'
  if cmp -s "$snapshot_a" "$snapshot_changed"; then
    die 'changed row value did not change the snapshot'
  fi
  pass 'database snapshot detects changed values'
}

write_openapi_expected() {
  local output=$1
  local example_value=${2:-expected-example}
  printf '%s\n' \
    'openapi: 3.0.0' \
    'info:' \
    '  title: backend verification fixture' \
    '  version: 1.0.0' \
    'paths:' \
    '  /people:' \
    '    get:' \
    '      responses:' \
    "        '200':" \
    '          description: ok' \
    '          content:' \
    '            application/json:' \
    '              schema:' \
    '                type: object' \
    '                properties:' \
    '                  id:' \
    '                    type: string' \
    '                  name:' \
    '                    type: string' \
    '              examples:' \
    '                minimal:' \
    "                  value: '$example_value'" >"$output"
}

write_openapi_reordered() {
  local output=$1
  local example_value=${2:-expected-example}
  printf '%s\n' \
    'info:' \
    '  version: 1.0.0' \
    '  title: backend verification fixture' \
    'openapi: 3.0.0' \
    'paths:' \
    '  /people:' \
    '    get:' \
    '      responses:' \
    "        '200':" \
    '          content:' \
    '            application/json:' \
    '              schema:' \
    '                properties:' \
    '                  name:' \
    '                    type: string' \
    '                  id:' \
    '                    type: string' \
    '                type: object' \
    '              examples:' \
    '                minimal:' \
    "                  value: '$example_value'" \
    '          description: ok' >"$output"
}

write_openapi_removed_field() {
  local output=$1
  write_openapi_expected "$output"
  sed -i.bak '/^[[:space:]]*name:$/,+1d' "$output"
  rm -f -- "$output.bak"
}

write_openapi_required_field() {
  local output=$1
  write_openapi_expected "$output"
  sed -i.bak '/^[[:space:]]*properties:$/i\
                required: [name]' "$output"
  rm -f -- "$output.bak"
}

write_openapi_removed_required_field() {
  local output=$1
  write_openapi_required_field "$output"
  sed -i.bak '/^[[:space:]]*name:$/,+1d' "$output"
  rm -f -- "$output.bak"
}

write_openapi_added_optional_field() {
  local output=$1
  write_openapi_expected "$output"
  sed -i.bak '/^[[:space:]]*name:$/i\
                  nickname:\
                    type: string' "$output"
  rm -f -- "$output.bak"
}

write_openapi_changed_title() {
  local output=$1
  write_openapi_expected "$output"
  sed -i.bak 's/title: backend verification fixture/title: Changed fixture/' "$output"
  rm -f -- "$output.bak"
}

write_openapi_changed_version() {
  local output=$1
  write_openapi_expected "$output"
  sed -i.bak 's/version: 1.0.0/version: 2.0.0/' "$output"
  rm -f -- "$output.bak"
}

write_openapi_added_server() {
  local output=$1
  write_openapi_expected "$output"
  sed -i.bak '/^paths:$/i\
servers:\
- url: https://changed.example.invalid' "$output"
  rm -f -- "$output.bak"
}

write_openapi_changed_operation_description() {
  local output=$1
  write_openapi_expected "$output"
  sed -i.bak '/^[[:space:]]*responses:$/i\
      description: Changed operation description' "$output"
  rm -f -- "$output.bak"
}

write_openapi_changed_response_description() {
  local output=$1
  write_openapi_expected "$output"
  sed -i.bak 's/description: ok/description: Changed response description/' "$output"
  rm -f -- "$output.bak"
}

write_openapi_added_example_name() {
  local output=$1
  write_openapi_expected "$output"
  printf '%s\n' \
    '                maximal:' \
    "                  value: 'extra-example'" >>"$output"
}

write_simpledocs_fixture() {
  local output=$1
  local sample=$2
  local description=$3
  local untargeted=$4
  local aliases=${5:-'"first"'}
  local metadata=${6:-'"expected-key" : "expected-value"'}
  printf '%s\n' \
    '<table>' \
    '<tr><td><b>ROUTE</b></td><td>/people/{personId}</td></tr>' \
    "<tr><td><b>DESCRIPTION</b></td><td>$description</td></tr>" \
    '<tr><td><b>REQUEST MINIMAL JSON</b></td><td><pre>{' \
    "  \"name\" : \"$sample\"," \
    "  \"aliases\" : [$aliases]," \
    "  \"metadata\" : {$metadata}," \
    '  "kind" : "person",' \
    '  "score" : 5' \
    '}</pre></td></tr>' \
    '<tr><td><b>REQUEST MAXIMAL JSON</b></td><td><pre>{' \
    "  \"name\" : \"$sample\"," \
    "  \"aliases\" : [$aliases]," \
    "  \"metadata\" : {$metadata}," \
    '  "kind" : "person",' \
    '  "score" : 5,' \
    '  "nickname" : null' \
    '}</pre></td></tr>' \
    '<tr><td><b>REQUEST SCHEMA</b></td><td><pre>{' \
    '  "type" : "object",' \
    '  "additionalProperties" : false,' \
    '  "required" : ["name", "aliases", "metadata", "kind", "score"],' \
    '  "properties" : {' \
    '    "name" : {"type":"string", "minLength":1, "maxLength":100, "pattern":"^.+$", "allOf":[{"type":"string"}]} ,' \
    '    "aliases" : {"type":"array", "minItems":1, "maxItems":3, "uniqueItems":true, "items":{"anyOf":[{"type":"string"},{"type":"integer"}]}},' \
    '    "metadata" : {"type":"object", "additionalProperties":{"oneOf":[{"type":"string"},{"type":"integer"}]}},' \
    '    "kind" : {"type":"string", "enum":["person"], "const":"person"},' \
    '    "score" : {"type":"number", "minimum":0, "maximum":10, "exclusiveMinimum":-1, "exclusiveMaximum":11},' \
    '    "nickname" : {"type":"string", "default":null}' \
    '  },' \
    '  "allOf" : [{"type":"object"}]' \
    '}</pre></td></tr>' \
    "<tr><td><b>ROUTE WITH ALL PARAMS</b></td><td>/people/{personId}?sample=$sample&filter=one&filter=two&flag</td></tr>" \
    "<tr><td><b>CUSTOM DETAILS</b></td><td><pre>$untargeted</pre></td></tr>" \
    '</table>' >"$output"
}

write_datetime_simpledocs_fixture() {
  local output=$1
  local value=$2
  printf '%s\n' \
    '<table>' \
    '<tr><td><b>ROUTE</b></td><td>/date-time</td></tr>' \
    '<tr><td><b>REQUEST SCALA TYPE</b></td><td><pre>java.time.OffsetDateTime</pre></td></tr>' \
    "<tr><td><b>REQUEST MINIMAL JSON</b></td><td><pre>\"$value\"</pre></td></tr>" \
    "<tr><td><b>REQUEST MAXIMAL JSON</b></td><td><pre>\"$value\"</pre></td></tr>" \
    '<tr><td><b>REQUEST SCHEMA</b></td><td><pre>{"type":"string","format":"date-time"}</pre></td></tr>' \
    '</table>' >"$output"
}

write_build_info_simpledocs_fixture() {
  local output=$1
  local commit_sha=$2
  local method=${3:-GET}
  local scala_type=${4:-'pl.iterators.epoca.healthcheck.routers.HealthCheckRouter.BuildInfo'}
  local schema_extra=${5:-}
  local sample_shape=${6:-valid}
  local route=${7:-'/build-info'}
  local minimal_json
  local maximal_json
  case "$sample_shape" in
    valid)
      minimal_json="{\"commitSha\":\"$commit_sha-minimal\"}"
      maximal_json="{\"commitSha\":\"$commit_sha-maximal\"}"
      ;;
    array)
      minimal_json="[\"$commit_sha-minimal\"]"
      maximal_json="[\"$commit_sha-maximal\"]"
      ;;
    wrong-key)
      minimal_json="{\"revision\":\"$commit_sha-minimal\"}"
      maximal_json="{\"revision\":\"$commit_sha-maximal\"}"
      ;;
    wrong-type)
      minimal_json='{"commitSha":7}'
      maximal_json='{"commitSha":8}'
      ;;
    extra-key)
      minimal_json="{\"commitSha\":\"$commit_sha-minimal\",\"extra\":true}"
      maximal_json="{\"commitSha\":\"$commit_sha-maximal\",\"extra\":true}"
      ;;
    *) die "Unknown build-info sample shape: $sample_shape" ;;
  esac
  printf '%s\n' \
    '<table>' \
    "<tr><td><b>METHOD</b></td><td>$method</td></tr>" \
    "<tr><td><b>ROUTE</b></td><td>$route</td></tr>" \
    "<tr><td><b>RESPONSE SCALA TYPE</b></td><td><pre>$scala_type</pre></td></tr>" \
    "<tr><td><b>RESPONSE MINIMAL JSON</b></td><td><pre>$minimal_json</pre></td></tr>" \
    "<tr><td><b>RESPONSE MAXIMAL JSON</b></td><td><pre>$maximal_json</pre></td></tr>" \
    "<tr><td><b>RESPONSE SCHEMA</b></td><td><pre>{\"\$schema\":\"http://json-schema.org/draft-07/schema#\",\"\$id\":\"\",\"type\":\"string\"$schema_extra}</pre></td></tr>" \
    '</table>' >"$output"
}

write_single_field_product_simpledocs_fixture() {
  local output=$1
  local value=$2
  local scala_type=${3:-'example.Request'}
  local extra_property=${4:-}
  printf '%s\n' \
    '<table>' \
    '<tr><td><b>ROUTE</b></td><td>/single-field-product</td></tr>' \
    "<tr><td><b>REQUEST SCALA TYPE</b></td><td><pre>$scala_type</pre></td></tr>" \
    '<tr><td><b>REQUEST MINIMAL JSON</b></td><td><pre>{' \
    "  \"token\" : \"$value\"$extra_property" \
    '}</pre></td></tr>' \
    '<tr><td><b>REQUEST MAXIMAL JSON</b></td><td><pre>{' \
    "  \"token\" : \"$value\"$extra_property" \
    '}</pre></td></tr>' \
    '<tr><td><b>REQUEST SCHEMA</b></td><td><pre>{' \
    '  "type" : "string",' \
    '  "pattern" : "^[a-z]+$"' \
    '}</pre></td></tr>' \
    '</table>' >"$output"
}

write_single_field_response_simpledocs_fixture() {
  local output=$1
  local value=$2
  printf '%s\n' \
    '<table>' \
    '<tr><td><b>ROUTE</b></td><td>/single-field-response</td></tr>' \
    '<tr><td><b>RESPONSE SCALA TYPE</b></td><td><pre>example.Response</pre></td></tr>' \
    '<tr><td><b>RESPONSE MINIMAL JSON</b></td><td><pre>{' \
    "  \"token\" : \"$value\"" \
    '}</pre></td></tr>' \
    '<tr><td><b>RESPONSE MAXIMAL JSON</b></td><td><pre>{' \
    "  \"token\" : \"$value\"" \
    '}</pre></td></tr>' \
    '<tr><td><b>RESPONSE SCHEMA</b></td><td><pre>{"type":"string"}</pre></td></tr>' \
    '</table>' >"$output"
}

write_pivot_field_value_simpledocs_fixture() {
  local output=$1
  local string_value=$2
  local nullable_value=${3:-null}
  local route=${4:-'/organizations/{organizationSlug}/pivot-tables/work-units/query'}
  local scala_type=${5:-'pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.Request'}
  local fingerprint_tail=${6:-'{"type":"string","pattern":"^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$"}'}
  printf '%s\n' \
    '<table>' \
    '<tr><td><b>METHOD</b></td><td>POST</td></tr>' \
    "<tr><td><b>ROUTE</b></td><td>$route</td></tr>" \
    "<tr><td><b>REQUEST SCALA TYPE</b></td><td><pre>$scala_type</pre></td></tr>" \
    '<tr><td><b>REQUEST MINIMAL JSON</b></td><td><pre>{' \
    '  "filters" : [{' \
    '    "key" : "ClientKey",' \
    "    \"includedValues\" : [\"$string_value\", $nullable_value, 2026]" \
    '  }]' \
    '}</pre></td></tr>' \
    '<tr><td><b>REQUEST MAXIMAL JSON</b></td><td><pre>{' \
    '  "filters" : [{' \
    '    "key" : "ClientKey",' \
    "    \"includedValues\" : [\"$string_value\", $nullable_value, 2026]" \
    '  }]' \
    '}</pre></td></tr>' \
    '<tr><td><b>REQUEST SCHEMA</b></td><td><pre>{' \
    '  "type":"object",' \
    '  "additionalProperties":false,' \
    '  "required":["filters"],' \
    '  "properties":{"filters":{' \
    '    "type":"array",' \
    '    "items":{' \
    '      "type":"object",' \
    '      "additionalProperties":false,' \
    '      "required":["key"],' \
    '      "properties":{' \
    '        "key":{"type":"string"},' \
    '        "includedValues":{' \
    '          "type":"array",' \
    '          "items":{"oneOf":[' \
    '            {"type":"integer"},' \
    '            {"type":"string","enum":["Null"]},' \
    '            {"type":"string"},' \
    '            {"type":"string","format":"date"},' \
    '            {"type":"object","additionalProperties":false,"properties":{"value":{"type":"string","enum":["Note","Worklog"]}},"required":["value"]},' \
    "            $fingerprint_tail" \
    '          ]}' \
    '        }' \
    '      }' \
    '    }' \
    '  }}' \
    '}</pre></td></tr>' \
    '</table>' >"$output"
}

write_overlapping_oneof_simpledocs_fixture() {
  local output=$1
  printf '%s\n' \
    '<table>' \
    '<tr><td><b>ROUTE</b></td><td>/not-pivot</td></tr>' \
    '<tr><td><b>REQUEST SCALA TYPE</b></td><td><pre>example.Request</pre></td></tr>' \
    '<tr><td><b>REQUEST MINIMAL JSON</b></td><td><pre>"2026-08-01"</pre></td></tr>' \
    '<tr><td><b>REQUEST MAXIMAL JSON</b></td><td><pre>"2026-08-01"</pre></td></tr>' \
    '<tr><td><b>REQUEST SCHEMA</b></td><td><pre>{"oneOf":[{"type":"string"},{"type":"string","format":"date"}]}</pre></td></tr>' \
    '</table>' >"$output"
}

write_pivot_response_simpledocs_fixture() {
  local output=$1
  local row_value=$2
  local route=${3:-'/organizations/{organizationSlug}/pivot-tables/work-units/query'}
  local scala_type=${4:-'pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.Response'}
  local fingerprint_tail=${5:-'{"type":"string","pattern":"^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$"}'}
  local row_key=${6:-value}
  printf '%s\n' \
    '<table>' \
    '<tr><td><b>METHOD</b></td><td>POST</td></tr>' \
    "<tr><td><b>ROUTE</b></td><td>$route</td></tr>" \
    "<tr><td><b>RESPONSE SCALA TYPE</b></td><td><pre>$scala_type</pre></td></tr>" \
    '<tr><td><b>RESPONSE MINIMAL JSON</b></td><td><pre>{' \
    "  \"cells\":[{\"row\":{\"path\":[{\"$row_key\":\"$row_value\"}]},\"column\":{\"path\":[]}}]," \
    '  "filterMembers":[]' \
    '}</pre></td></tr>' \
    '<tr><td><b>RESPONSE MAXIMAL JSON</b></td><td><pre>{' \
    "  \"cells\":[{\"row\":{\"path\":[{\"$row_key\":\"$row_value\"}]},\"column\":{\"path\":[]}}]," \
    '  "filterMembers":[]' \
    '}</pre></td></tr>' \
    '<tr><td><b>RESPONSE SCHEMA</b></td><td><pre>{' \
    '  "type":"object",' \
    '  "additionalProperties":false,' \
    '  "required":["cells","filterMembers"],' \
    '  "properties":{' \
    '    "cells":{"type":"array","items":{"type":"object","properties":{' \
    '      "row":{"type":"object","properties":{"path":{"type":"array","items":{"type":"object","properties":{' \
    '        "value":{"oneOf":[' \
    '          {"type":"integer"},' \
    '          {"type":"string","enum":["Null"]},' \
    '          {"type":"string"},' \
    '          {"type":"string","format":"date"},' \
    '          {"type":"object","additionalProperties":false,"properties":{"value":{"type":"string","enum":["Note","Worklog"]}},"required":["value"]},' \
    "          $fingerprint_tail" \
    '        ]}' \
    '      }}}}},' \
    '      "column":{"type":"object","properties":{"path":{"type":"array","items":{"type":"object","properties":{' \
    '        "value":{"oneOf":[' \
    '          {"type":"integer"},{"type":"string","enum":["Null"]},{"type":"string"},{"type":"string","format":"date"},' \
    '          {"type":"object","additionalProperties":false,"properties":{"value":{"type":"string","enum":["Note","Worklog"]}},"required":["value"]},' \
    '          {"type":"string","pattern":"^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$"}' \
    '        ]}' \
    '      }}}}}' \
    '    }}},' \
    '    "filterMembers":{"type":"array","items":{"type":"object","properties":{"members":{"type":"array","items":{"type":"object","properties":{' \
    '      "value":{"oneOf":[' \
    '        {"type":"integer"},{"type":"string","enum":["Null"]},{"type":"string"},{"type":"string","format":"date"},' \
    '        {"type":"object","additionalProperties":false,"properties":{"value":{"type":"string","enum":["Note","Worklog"]}},"required":["value"]},' \
    '        {"type":"string","pattern":"^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$"}' \
    '      ]}' \
    '    }}}}}}' \
    '  }' \
    '}</pre></td></tr>' \
    '</table>' >"$output"
}

run_contract_cases() {
  local fixture_root="$TMP_ROOT/contracts"
  local test_repo="$fixture_root/repo"
  local expected="$fixture_root/expected"
  local actual="$fixture_root/actual"
  local comparer="$test_repo/scripts/backend-verification/compare-contracts.sh"
  local platform
  local corrupted_tool
  local corrupted_marker="$fixture_root/corrupted-tool-executed"
  local no_hash_bin="$fixture_root/no-hash-bin"
  local malicious_config="$fixture_root/unmatch-people.yaml"

  mkdir -p "$test_repo/scripts/backend-verification" \
    "$expected/openapi" "$expected/simple/nested" \
    "$actual/openapi" "$actual/simple/nested"
  ln -s "$REPO_ROOT/scripts/backend-verification/compare-contracts.sh" "$comparer"
  ln -s "$REPO_ROOT/scripts/backend-verification/ContractNormalizer.java" \
    "$test_repo/scripts/backend-verification/ContractNormalizer.java"
  write_openapi_expected "$expected/openapi/openapi.yml"
  write_simpledocs_fixture "$actual/simple/nested/routes.html" expected-sample 'List people' stable-details
  sed 's/$/  \r/' "$actual/simple/nested/routes.html" >"$expected/simple/nested/routes.html"

  case "$(uname -s):$(uname -m)" in
    Linux:x86_64|Linux:amd64) platform=linux_amd64 ;;
    Linux:aarch64|Linux:arm64) platform=linux_arm64 ;;
    Darwin:arm64|Darwin:x86_64) platform=darwin_all ;;
    *) die 'Contract test platform is unsupported' ;;
  esac

  corrupted_tool="$test_repo/target/backend-verification-tools/oasdiff/1.28.0/$platform/oasdiff"
  mkdir -p "$(dirname -- "$corrupted_tool")"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'set -euo pipefail' \
    "touch '$corrupted_marker'" \
    'case "${1:-}" in' \
    '  --version) printf "oasdiff version 1.28.0\\n" ;;' \
    '  breaking) exit 0 ;;' \
    '  diff) printf "{}\\n" ;;' \
    '  *) exit 2 ;;' \
    'esac' >"$corrupted_tool"
  chmod +x "$corrupted_tool"
  write_openapi_reordered "$actual/openapi/openapi.yml"
  mkdir -p "$no_hash_bin"
  for required_tool in dirname pwd java javac uname mkdir rmdir rm awk; do
    ln -s "$(command -v "$required_tool")" "$no_hash_bin/$required_tool"
  done
  expect_failure 'contract comparison fails closed without a SHA-256 tool' 'A SHA-256 checksum tool is required' \
    env PATH="$no_hash_bin" /bin/bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_openapi_removed_field "$actual/openapi/openapi.yml"
  if bash "$comparer" --expected-dir "$expected" --actual-dir "$actual" \
    >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr"; then
    die 'corrupted cached oasdiff unexpectedly passed'
  fi
  if ! grep -F -- 'Cached oasdiff executable checksum mismatch' "$TMP_ROOT/stderr" >/dev/null; then
    sed -n '1,120p' "$TMP_ROOT/stderr" >&2
    die 'corrupted cached oasdiff did not report its checksum mismatch'
  fi
  [ ! -e "$corrupted_marker" ] || die 'corrupted cached oasdiff was executed before authentication'
  pass 'corrupted cached oasdiff rejected before execution'

  rm -rf -- "$test_repo/target/backend-verification-tools"
  printf '{not-json}\n' >"$fixture_root/malformed-diff.json"
  expect_failure 'malformed oasdiff JSON rejected' 'Malformed JSON' \
    java "$test_repo/scripts/backend-verification/ContractNormalizer.java" \
      validate-openapi-diff "$fixture_root/malformed-diff.json"
  write_openapi_reordered "$actual/openapi/openapi.yml"
  expect_success 'OpenAPI mapping/order-only difference accepted' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_openapi_reordered "$actual/openapi/openapi.yml" actual-random-example
  write_simpledocs_fixture "$actual/simple/nested/routes.html" actual-random-example 'List people' stable-details \
    '"first", "second"' '"actual-free-key" : 7'
  sed -i.bak '/ROUTE WITH ALL PARAMS/s/{personId}/actual-person/' "$actual/simple/nested/routes.html"
  rm -f -- "$actual/simple/nested/routes.html.bak"
  expect_success 'generated samples and optional Baklava null normalized' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_single_field_product_simpledocs_fixture "$expected/simple/nested/routes.html" expected
  write_single_field_product_simpledocs_fixture "$actual/simple/nested/routes.html" actual
  expect_success 'Baklava single-field Scala request product normalized' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_single_field_product_simpledocs_fixture "$actual/simple/nested/routes.html" actual
  sed -i.bak '/REQUEST SCALA TYPE/d' "$actual/simple/nested/routes.html"
  rm -f -- "$actual/simple/nested/routes.html.bak"
  expect_failure 'single-field product without Scala type rejected' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_single_field_product_simpledocs_fixture "$actual/simple/nested/routes.html" actual 'example.Request' ', "other" : "value"'
  expect_failure 'single-field product with multiple keys rejected' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_single_field_product_simpledocs_fixture "$actual/simple/nested/routes.html" 'NOT-LOWERCASE'
  expect_failure 'single-field product scalar constraint violation rejected' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_single_field_response_simpledocs_fixture "$actual/simple/nested/routes.html" actual
  expect_failure 'single-field response mismatch remains rejected' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_pivot_field_value_simpledocs_fixture "$expected/simple/nested/routes.html" 2026-08-01

  write_pivot_field_value_simpledocs_fixture "$actual/simple/nested/routes.html" 2026-08-02 null '/other-pivot'
  expect_failure 'pivot FieldValue compatibility requires exact route' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_pivot_field_value_simpledocs_fixture "$actual/simple/nested/routes.html" 2026-08-02 null \
    '/organizations/{organizationSlug}/pivot-tables/work-units/query' 'example.Request'
  expect_failure 'pivot FieldValue compatibility requires exact Scala type' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_pivot_field_value_simpledocs_fixture "$actual/simple/nested/routes.html" 2026-08-02 null \
    '/organizations/{organizationSlug}/pivot-tables/work-units/query' \
    'pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.Request' '{"type":"boolean"}'
  expect_failure 'pivot FieldValue compatibility requires the complete schema fingerprint' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_pivot_field_value_simpledocs_fixture "$actual/simple/nested/routes.html" 2026-08-02 '{"unexpected":true}'
  expect_failure 'pivot FieldValue compatibility rejects an unsupported object' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_pivot_field_value_simpledocs_fixture "$actual/simple/nested/routes.html" 2026-08-02 true
  expect_failure 'pivot FieldValue compatibility rejects a wrong scalar type' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_overlapping_oneof_simpledocs_fixture "$actual/simple/nested/routes.html"
  expect_failure 'non-pivot overlapping oneOf remains rejected' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_pivot_field_value_simpledocs_fixture "$actual/simple/nested/routes.html" 2026-08-02
  expect_success 'exact pivot FieldValue Baklava schema defect normalized' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_pivot_response_simpledocs_fixture "$expected/simple/nested/routes.html" 33333333-3333-4333-8333-333333333333
  cp "$expected/simple/nested/routes.html" "$actual/simple/nested/routes.html"
  expect_success 'exact fixed pivot response accepted despite Baklava schema defect' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_pivot_response_simpledocs_fixture "$actual/simple/nested/routes.html" 44444444-4444-4444-8444-444444444444
  expect_failure 'pivot response scalar change remains visible' 'SimpleDocs manifest differs' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_pivot_response_simpledocs_fixture "$actual/simple/nested/routes.html" 33333333-3333-4333-8333-333333333333 \
    '/organizations/{organizationSlug}/pivot-tables/work-units/query' \
    'pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.Response' \
    '{"type":"string","pattern":"^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$"}' changedValue
  expect_failure 'pivot response key change remains visible' 'SimpleDocs manifest differs' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_pivot_response_simpledocs_fixture "$actual/simple/nested/routes.html" 33333333-3333-4333-8333-333333333333 '/other-pivot'
  expect_failure 'pivot response compatibility requires exact route' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_pivot_response_simpledocs_fixture "$actual/simple/nested/routes.html" 33333333-3333-4333-8333-333333333333 \
    '/organizations/{organizationSlug}/pivot-tables/work-units/query' 'example.Response'
  expect_failure 'pivot response compatibility requires exact Scala type' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_pivot_response_simpledocs_fixture "$actual/simple/nested/routes.html" 33333333-3333-4333-8333-333333333333 \
    '/organizations/{organizationSlug}/pivot-tables/work-units/query' \
    'pl.iterators.epoca.stats.domain.WorkUnitPivotQuery.Response' '{"type":"boolean"}'
  expect_failure 'pivot response compatibility requires all three schema fingerprints' 'SimpleDocs manifest differs' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_simpledocs_fixture "$expected/simple/nested/routes.html" expected-sample 'List people' stable-details
  cp "$expected/simple/nested/routes.html" "$actual/simple/nested/routes.html"

  write_build_info_simpledocs_fixture "$expected/simple/nested/routes.html" expected-commit
  write_build_info_simpledocs_fixture "$actual/simple/nested/routes.html" actual-commit
  expect_success 'exact build-info Baklava schema mismatch normalizes only commitSha' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_build_info_simpledocs_fixture "$actual/simple/nested/routes.html" actual-commit GET \
    'pl.iterators.epoca.healthcheck.routers.HealthCheckRouter.BuildInfo' '' array
  expect_failure 'build-info array sample rejected' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_build_info_simpledocs_fixture "$actual/simple/nested/routes.html" actual-commit GET \
    'pl.iterators.epoca.healthcheck.routers.HealthCheckRouter.BuildInfo' '' wrong-key
  expect_failure 'build-info wrong object key rejected' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_build_info_simpledocs_fixture "$actual/simple/nested/routes.html" actual-commit GET \
    'pl.iterators.epoca.healthcheck.routers.HealthCheckRouter.BuildInfo' '' wrong-type
  expect_failure 'build-info commitSha scalar type rejected' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_build_info_simpledocs_fixture "$actual/simple/nested/routes.html" actual-commit GET \
    'pl.iterators.epoca.healthcheck.routers.HealthCheckRouter.BuildInfo' '' extra-key
  expect_failure 'build-info extra object key rejected' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_build_info_simpledocs_fixture "$actual/simple/nested/routes.html" actual-commit POST
  expect_failure 'build-info compatibility requires GET' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_build_info_simpledocs_fixture "$actual/simple/nested/routes.html" actual-commit GET 'example.BuildInfo'
  expect_failure 'build-info compatibility requires exact Scala type' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_build_info_simpledocs_fixture "$actual/simple/nested/routes.html" actual-commit GET \
    'pl.iterators.epoca.healthcheck.routers.HealthCheckRouter.BuildInfo' ',"pattern":"^[a-f]+$"'
  expect_failure 'build-info compatibility requires exact root string schema fingerprint' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_build_info_simpledocs_fixture "$actual/simple/nested/routes.html" actual-commit GET \
    'pl.iterators.epoca.healthcheck.routers.HealthCheckRouter.BuildInfo' '' valid '/other'
  expect_failure 'same object/string mismatch outside build-info rejected' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_build_info_simpledocs_fixture "$actual/simple/nested/routes.html" actual-commit
  sed -i.bak '/RESPONSE MINIMAL JSON/s/{\"commitSha\"/commitSha/' "$actual/simple/nested/routes.html"
  rm -f -- "$actual/simple/nested/routes.html.bak"
  expect_failure 'malformed build-info sample JSON rejected' 'Malformed JSON number' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_simpledocs_fixture "$expected/simple/nested/routes.html" expected-sample 'List people' stable-details
  cp "$expected/simple/nested/routes.html" "$actual/simple/nested/routes.html"

  cp "$expected/simple/nested/routes.html" "$actual/simple/nested/routes.html"
  write_openapi_changed_title "$actual/openapi/openapi.yml"
  expect_failure 'OpenAPI info title change rejected' 'OpenAPI diff contains unsupported changes' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_openapi_changed_version "$actual/openapi/openapi.yml"
  expect_failure 'OpenAPI info version change rejected' 'OpenAPI diff contains unsupported changes' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_openapi_added_server "$actual/openapi/openapi.yml"
  expect_failure 'OpenAPI server change rejected' 'OpenAPI diff contains unsupported changes' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_openapi_changed_operation_description "$actual/openapi/openapi.yml"
  expect_failure 'OpenAPI operation description change rejected' 'OpenAPI diff contains unsupported changes' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_openapi_changed_response_description "$actual/openapi/openapi.yml"
  expect_failure 'OpenAPI response description change rejected' 'OpenAPI diff contains unsupported changes' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_openapi_added_example_name "$actual/openapi/openapi.yml"
  expect_failure 'OpenAPI added example name rejected' 'OpenAPI diff contains unsupported changes' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_openapi_removed_field "$actual/openapi/openapi.yml"
  cp "$expected/simple/nested/routes.html" "$actual/simple/nested/routes.html"
  expect_failure 'removed OpenAPI response field rejected' 'OpenAPI diff contains unsupported changes' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_openapi_required_field "$expected/openapi/openapi.yml"
  write_openapi_removed_required_field "$actual/openapi/openapi.yml"
  printf 'unmatch-path: ^/people$\n' >"$malicious_config"
  expect_failure 'ambient oasdiff config cannot hide a removed required field' 'OpenAPI breaking comparison failed' \
    env OASDIFF_CONFIG="$malicious_config" \
      bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  printf 'unmatch-path: ^/people$\n' >"$test_repo/.oasdiff.yaml"
  expect_failure 'repo-discovered oasdiff config cannot hide a removed required field' 'OpenAPI breaking comparison failed' \
    bash -c 'cd "$1" && bash "$2" --expected-dir "$3" --actual-dir "$4"' \
      stack-test "$test_repo" "$comparer" "$expected" "$actual"
  rm -f -- "$test_repo/.oasdiff.yaml"

  write_openapi_expected "$expected/openapi/openapi.yml"
  write_openapi_added_optional_field "$actual/openapi/openapi.yml"
  expect_failure 'added optional OpenAPI response field rejected' 'OpenAPI diff contains unsupported changes' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_openapi_reordered "$actual/openapi/openapi.yml"
  write_datetime_simpledocs_fixture "$expected/simple/nested/routes.html" '2026-08-13T08:30:00Z'
  write_datetime_simpledocs_fixture "$actual/simple/nested/routes.html" '2026-08-13T10:30:00+02:00'
  expect_success 'RFC3339 date-time accepts Z and numeric offsets' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_datetime_simpledocs_fixture "$actual/simple/nested/routes.html" '2026-08-13T10:30:00+02:00[Europe/Warsaw]'
  expect_failure 'RFC3339 date-time rejects bracketed zone identifiers' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_datetime_simpledocs_fixture "$expected/simple/nested/routes.html" '2026-08-13T08:30:00Z'
  write_datetime_simpledocs_fixture "$actual/simple/nested/routes.html" '2026-08-13T10:30:00+25:00'
  expect_failure 'RFC3339 date-time rejects invalid numeric offsets' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_simpledocs_fixture "$expected/simple/nested/routes.html" expected-sample 'List people' stable-details
  write_simpledocs_fixture "$actual/simple/nested/routes.html" expected-sample 'List people' stable-details
  sed -i.bak '/REQUEST MINIMAL JSON/,/<\/pre>/ { /^  "name"/d; }' "$actual/simple/nested/routes.html"
  rm -f -- "$actual/simple/nested/routes.html.bak"
  expect_failure 'SimpleDocs sample missing required key rejected' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_simpledocs_fixture "$actual/simple/nested/routes.html" expected-sample 'List people' stable-details
  sed -i.bak '/^  "metadata" :/i\
  "unexpected" : "value",' "$actual/simple/nested/routes.html"
  rm -f -- "$actual/simple/nested/routes.html.bak"
  expect_failure 'SimpleDocs sample extra key rejected' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_simpledocs_fixture "$actual/simple/nested/routes.html" expected-sample 'List people' stable-details
  sed -i.bak 's/"name" : "expected-sample"/"name" : 7/' "$actual/simple/nested/routes.html"
  rm -f -- "$actual/simple/nested/routes.html.bak"
  expect_failure 'SimpleDocs sample scalar type change rejected' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_simpledocs_fixture "$actual/simple/nested/routes.html" expected-sample 'List people' stable-details
  sed -i.bak 's/"name" : "expected-sample"/"name" : null/' "$actual/simple/nested/routes.html"
  rm -f -- "$actual/simple/nested/routes.html.bak"
  expect_failure 'SimpleDocs required property null rejected' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_simpledocs_fixture "$actual/simple/nested/routes.html" expected-sample 'List people' stable-details
  sed -i.bak 's/"minLength":1/"minLength":2/' "$actual/simple/nested/routes.html"
  rm -f -- "$actual/simple/nested/routes.html.bak"
  expect_failure 'SimpleDocs schema constraint change rejected' 'SimpleDocs manifest differs' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_simpledocs_fixture "$actual/simple/nested/routes.html" expected-sample 'List people' stable-details
  sed -i.bak 's/"type":"string", "minLength":1/"type":"integer", "minLength":1/' "$actual/simple/nested/routes.html"
  rm -f -- "$actual/simple/nested/routes.html.bak"
  expect_failure 'SimpleDocs schema type change rejected' 'SimpleDocs sample does not satisfy its schema' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_simpledocs_fixture "$actual/simple/nested/routes.html" expected-sample 'List people' stable-details
  sed -i.bak '/^    "metadata" :/i\
    "addedField" : {"type":"string"},' "$actual/simple/nested/routes.html"
  rm -f -- "$actual/simple/nested/routes.html.bak"
  expect_failure 'SimpleDocs schema field change rejected' 'SimpleDocs manifest differs' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_simpledocs_fixture "$actual/simple/nested/routes.html" expected-sample 'List people' stable-details
  sed -i.bak '/ROUTE WITH ALL PARAMS/s#/people/{personId}?#/persons/{personId}?#' "$actual/simple/nested/routes.html"
  rm -f -- "$actual/simple/nested/routes.html.bak"
  expect_failure 'SimpleDocs route literal path change rejected' 'SimpleDocs route does not match its template' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_simpledocs_fixture "$actual/simple/nested/routes.html" expected-sample 'List people' stable-details
  sed -i.bak 's/sample=expected-sample/changed=expected-sample/' "$actual/simple/nested/routes.html"
  rm -f -- "$actual/simple/nested/routes.html.bak"
  expect_failure 'SimpleDocs route query key change rejected' 'SimpleDocs manifest differs' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_simpledocs_fixture "$actual/simple/nested/routes.html" expected-sample 'List people' stable-details
  sed -i.bak 's/&filter=two//' "$actual/simple/nested/routes.html"
  rm -f -- "$actual/simple/nested/routes.html.bak"
  expect_failure 'SimpleDocs route query multiplicity change rejected' 'SimpleDocs manifest differs' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_simpledocs_fixture "$actual/simple/nested/routes.html" expected-sample 'Changed description' stable-details
  expect_failure 'non-example SimpleDocs description change rejected' 'SimpleDocs manifest differs' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_simpledocs_fixture "$actual/simple/nested/routes.html" expected-sample 'List people' stable-details
  sed -i.bak '/REQUEST MINIMAL JSON/,/<\/pre><\/td><\/tr>/d' "$actual/simple/nested/routes.html"
  rm -f -- "$actual/simple/nested/routes.html.bak"
  expect_failure 'targeted SimpleDocs example row removal rejected' 'Unpaired targeted SimpleDocs blocks: REQUEST' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_simpledocs_fixture "$actual/simple/nested/routes.html" expected-sample 'List people' stable-details
  sed -i.bak '/REQUEST MINIMAL JSON/,/<\/pre><\/td><\/tr>/p' "$actual/simple/nested/routes.html"
  rm -f -- "$actual/simple/nested/routes.html.bak"
  expect_failure 'duplicate targeted SimpleDocs block rejected' 'Duplicate targeted SimpleDocs block: REQUEST MINIMAL JSON' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_simpledocs_fixture "$actual/simple/nested/routes.html" expected-sample 'List people' stable-details
  sed -i.bak '/REQUEST SCHEMA/s/<pre>/<code>/' "$actual/simple/nested/routes.html"
  rm -f -- "$actual/simple/nested/routes.html.bak"
  expect_failure 'malformed targeted SimpleDocs block rejected' 'Malformed targeted SimpleDocs block: REQUEST SCHEMA' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_simpledocs_fixture "$actual/simple/nested/routes.html" expected-sample 'List people' stable-details
  sed -i.bak '/"type" : "object",/a\
  "unsupportedAcceptanceKeyword" : true,' "$actual/simple/nested/routes.html"
  rm -f -- "$actual/simple/nested/routes.html.bak"
  expect_failure 'unsupported SimpleDocs schema keyword rejected' 'Unsupported SimpleDocs schema keyword' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_simpledocs_fixture "$actual/simple/nested/routes.html" expected-sample 'List people' changed-details
  expect_failure 'untargeted SimpleDocs pre block change rejected' 'SimpleDocs manifest differs' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"

  write_simpledocs_fixture "$actual/simple/nested/routes.html" expected-sample 'List people' stable-details
  sed -i.bak '/REQUEST MINIMAL JSON/,/<\/pre><\/td><\/tr>/s#</pre></td></tr>##' "$actual/simple/nested/routes.html"
  rm -f -- "$actual/simple/nested/routes.html.bak"
  expect_failure 'unterminated targeted SimpleDocs block rejected' 'Unterminated targeted SimpleDocs block: REQUEST MINIMAL JSON' \
    bash "$comparer" --expected-dir "$expected" --actual-dir "$actual"
}

run_jvm_image_cases() {
  local fixture_root="$TMP_ROOT/jvm-images"
  local test_repo="$fixture_root/repo"
  local fake_bin="$fixture_root/bin"
  local docker_marker="$fixture_root/docker-called"
  local verifier="$test_repo/scripts/backend-verification/verify-jvm-images.sh"
  local builder_image="registry.example/stack-builder@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  local runtime_image="registry.example/stack-runtime@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

  mkdir -p "$test_repo/scripts/backend-verification" "$fake_bin"
  ln -s "$REPO_ROOT/scripts/backend-verification/verify-jvm-images.sh" "$verifier"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'set -euo pipefail' \
    ': "${FAKE_DOCKER_MARKER:?}"' \
    ': "${FAKE_IMAGE_PLATFORM:?}"' \
    ': "${FAKE_JAVA_VENDOR:?}"' \
    ': "${FAKE_JAVA_RUNTIME_VERSION:?}"' \
    'touch "$FAKE_DOCKER_MARKER"' \
    'case "${1:-}" in' \
    '  pull)' \
    '    exit 0' \
    '    ;;' \
    '  image)' \
    '    [ "${2:-}" = inspect ]' \
    '    image=${!#}' \
    '    if [[ " $* " == *"{{.Os}}/{{.Architecture}}"* ]]; then' \
    '      printf "%s\\n" "$FAKE_IMAGE_PLATFORM"' \
    '    else' \
    '      printf "%s\\n" "$image"' \
    '    fi' \
    '    ;;' \
    '  run)' \
    '    if [[ " $* " == *" -XshowSettings:properties "* ]]; then' \
    '      printf "    java.runtime.version = %s\\n" "$FAKE_JAVA_RUNTIME_VERSION" >&2' \
    '      printf "    java.vendor = %s\\n" "$FAKE_JAVA_VENDOR" >&2' \
    '    else' \
    '      printf "openjdk version \\"17.0.20\\" 2026-07-21\\n" >&2' \
    '      printf "OpenJDK Runtime Environment Temurin-17.0.20+8 (build 17.0.20+8)\\n" >&2' \
    '    fi' \
    '    ;;' \
    '  *) exit 2 ;;' \
    'esac' >"$fake_bin/docker"
  chmod +x "$fake_bin/docker"

  expect_failure 'JVM image verification requires builder digest' 'EPOCA_BUILDER_IMAGE is required' \
    env -u EPOCA_BUILDER_IMAGE -u EPOCA_RUNTIME_IMAGE \
      FAKE_DOCKER_MARKER="$docker_marker" \
      FAKE_IMAGE_PLATFORM='linux/amd64' \
      FAKE_JAVA_VENDOR='Eclipse Adoptium' \
      FAKE_JAVA_RUNTIME_VERSION='17.0.20+8' \
      PATH="$fake_bin:$PATH" bash "$verifier"
  [ ! -e "$docker_marker" ] || die 'Docker was invoked before the builder image preflight failed'

  expect_success 'exact Temurin JVM images accepted' \
    env EPOCA_BUILDER_IMAGE="$builder_image" EPOCA_RUNTIME_IMAGE="$runtime_image" \
      FAKE_DOCKER_MARKER="$docker_marker" \
      FAKE_IMAGE_PLATFORM='linux/amd64' \
      FAKE_JAVA_VENDOR='Eclipse Adoptium' \
      FAKE_JAVA_RUNTIME_VERSION='17.0.20+8' \
      PATH="$fake_bin:$PATH" bash "$verifier"
  [ "$(cat "$test_repo/target/backend-verification/images/builder.reference.txt")" = "$builder_image" ] || \
    die 'Builder image evidence does not contain the exact reference'
  [ "$(cat "$test_repo/target/backend-verification/images/runtime.reference.txt")" = "$runtime_image" ] || \
    die 'Runtime image evidence does not contain the exact reference'
  [ "$(cat "$test_repo/target/backend-verification/images/builder.platform.txt")" = 'linux/amd64' ] || \
    die 'Builder image evidence does not contain the inspected platform'
  [ "$(cat "$test_repo/target/backend-verification/images/runtime.platform.txt")" = 'linux/amd64' ] || \
    die 'Runtime image evidence does not contain the inspected platform'

  expect_failure 'JVM image wrong platform rejected' 'builder image platform must equal linux/amd64 (got linux/arm64)' \
    env EPOCA_BUILDER_IMAGE="$builder_image" EPOCA_RUNTIME_IMAGE="$runtime_image" \
      FAKE_DOCKER_MARKER="$docker_marker" \
      FAKE_IMAGE_PLATFORM='linux/arm64' \
      FAKE_JAVA_VENDOR='Eclipse Adoptium' \
      FAKE_JAVA_RUNTIME_VERSION='17.0.20+8' \
      PATH="$fake_bin:$PATH" bash "$verifier"

  expect_failure 'JVM image wrong patch rejected' 'java.runtime.version must equal 17.0.20+8' \
    env EPOCA_BUILDER_IMAGE="$builder_image" EPOCA_RUNTIME_IMAGE="$runtime_image" \
      FAKE_DOCKER_MARKER="$docker_marker" \
      FAKE_IMAGE_PLATFORM='linux/amd64' \
      FAKE_JAVA_VENDOR='Eclipse Adoptium' \
      FAKE_JAVA_RUNTIME_VERSION='17.0.200+80' \
      PATH="$fake_bin:$PATH" bash "$verifier"

  expect_failure 'JVM image wrong build rejected' 'java.runtime.version must equal 17.0.20+8' \
    env EPOCA_BUILDER_IMAGE="$builder_image" EPOCA_RUNTIME_IMAGE="$runtime_image" \
      FAKE_DOCKER_MARKER="$docker_marker" \
      FAKE_IMAGE_PLATFORM='linux/amd64' \
      FAKE_JAVA_VENDOR='Eclipse Adoptium' \
      FAKE_JAVA_RUNTIME_VERSION='17.0.20+81' \
      PATH="$fake_bin:$PATH" bash "$verifier"

  expect_failure 'JVM image wrong vendor rejected' 'java.vendor must be Eclipse Adoptium or Temurin' \
    env EPOCA_BUILDER_IMAGE="$builder_image" EPOCA_RUNTIME_IMAGE="$runtime_image" \
      FAKE_DOCKER_MARKER="$docker_marker" \
      FAKE_IMAGE_PLATFORM='linux/amd64' \
      FAKE_JAVA_VENDOR='Acme Eclipse Adoptium Mirror' \
      FAKE_JAVA_RUNTIME_VERSION='17.0.20+8' \
      PATH="$fake_bin:$PATH" bash "$verifier"
}

run_runner_cases() {
  local fixture_root="$TMP_ROOT/runner"
  local test_repo="$fixture_root/repo"
  local runner="$test_repo/scripts/backend-verification/run.sh"
  local fake_bin="$fixture_root/bin"
  local sbt_marker="$fixture_root/sbt-called"
  mkdir -p "$test_repo/scripts/backend-verification/accepted" "$fake_bin"
  ln -s "$REPO_ROOT/scripts/backend-verification/run.sh" "$runner"
  printf '#!/usr/bin/env bash\ntouch %q\n' "$sbt_marker" >"$fake_bin/sbt"
  chmod +x "$fake_bin/sbt"

  expect_failure 'checkpoint runner requires focus' 'checkpoint mode requires --focus' \
    env PATH="$fake_bin:$PATH" bash "$runner" checkpoint
  expect_failure 'checkpoint runner requires accepted manifest' 'Accepted dependency manifest is required' \
    env PATH="$fake_bin:$PATH" bash "$runner" checkpoint --focus 'testOnly ExampleSpec'

  printf 'configuration\torganization\tname\trevision\tevicted\n' \
    >"$test_repo/scripts/backend-verification/accepted/dependencies.tsv"
  cp "$REPO_ROOT/scripts/backend-verification/accepted/dependency-policy.tsv" \
    "$test_repo/scripts/backend-verification/accepted/dependency-policy.tsv"
  expect_failure 'final runner requires migration harness' 'Final gate requires scripts/backend-verification/verify-migrations.sh' \
    env -u EPOCA_REGRESSION_REPOSITORY -u EPOCA_REGRESSION_COMMIT_SHA -u EPOCA_REGRESSION_COMMAND \
      PATH="$fake_bin:$PATH" bash "$runner" final --focus 'testOnly ExampleSpec'
  [ ! -e "$sbt_marker" ] || die 'sbt ran before runner preflight validation completed'
}

run_runtime_jdbc_cases() {
  local fixture_root="$TMP_ROOT/runtime-jdbc"
  local fake_bin="$fixture_root/bin"
  local docker_marker="$fixture_root/docker-called"
  local docker_invocations="$fixture_root/docker-invocations"
  local fake_container_id=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  local verifier="$REPO_ROOT/scripts/backend-verification/verify-runtime-jdbc.sh"
  local status=0

  mkdir -p "$fake_bin"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'touch "$FAKE_DOCKER_MARKER"' \
    'exit 1' >"$fake_bin/docker"
  printf '#!/usr/bin/env bash\nexit 99\n' >"$fake_bin/curl"
  chmod +x "$fake_bin/docker" "$fake_bin/curl"

  env \
    EPOCA_VERIFY_APPLICATION_IMAGE=example.invalid/stack:local \
    EPOCA_VERIFY_RUNTIME_JDBC_URL=jdbc:postgresql://host.docker.internal:62028/epoca_stack_task7 \
    EPOCA_VERIFY_RUNTIME_JDBC_USER=postgres \
    EPOCA_VERIFY_RUNTIME_JDBC_PASSWORD=postgres \
    EPOCA_VERIFY_RUNTIME_JDBC_LOGIN_EMAIL=local@example.invalid \
    EPOCA_VERIFY_RUNTIME_JDBC_LOGIN_PASSWORD='Aa1!local-only' \
    FAKE_DOCKER_MARKER="$docker_marker" \
    PATH="$fake_bin:$PATH" \
    bash "$verifier" >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr" || status=$?

  [ "$status" -ne 0 ] || die 'non-gate backend verification database unexpectedly passed runtime JDBC preflight'
  if ! grep -F -- 'EPOCA_VERIFY_RUNTIME_JDBC_URL must name a disposable backend verification gate database' \
    "$TMP_ROOT/stderr" >/dev/null; then
    sed -n '1,120p' "$TMP_ROOT/stderr" >&2
    die 'non-gate backend verification database did not report the disposable-database requirement'
  fi
  [ ! -e "$docker_marker" ] || die 'runtime JDBC preflight contacted Docker before rejecting a non-gate database'
  pass 'runtime JDBC gate rejects a non-disposable database before Docker'

  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'set -euo pipefail' \
    'printf "%q " "$@" >>"$FAKE_DOCKER_INVOCATIONS"' \
    'printf "\\n" >>"$FAKE_DOCKER_INVOCATIONS"' \
    'case "$1 ${2:-}" in' \
    '  "image inspect") exit 0 ;;' \
    '  "run -d") printf "%s\\n" "$FAKE_CONTAINER_ID" ;;' \
    '  "inspect --format") printf "healthy\\n" ;;' \
    '  "port "*) printf "127.0.0.1:52601\\n" ;;' \
    '  "container inspect") exit 0 ;;' \
    '  "logs "*) exit 0 ;;' \
    '  "stop --time") exit "${FAKE_DOCKER_STOP_STATUS:-0}" ;;' \
    '  *) exit 97 ;;' \
    'esac' >"$fake_bin/docker"
  printf '#!/usr/bin/env bash\nprintf "200"\n' >"$fake_bin/curl"
  chmod +x "$fake_bin/docker" "$fake_bin/curl"

  expect_success 'runtime JDBC gate completes a database-backed login' \
    env \
      EPOCA_VERIFY_APPLICATION_IMAGE=example.invalid/stack:local \
      EPOCA_VERIFY_RUNTIME_JDBC_URL=jdbc:postgresql://host.docker.internal:62028/epoca_stack_runtime_gate \
      EPOCA_VERIFY_RUNTIME_JDBC_USER=postgres \
      EPOCA_VERIFY_RUNTIME_JDBC_PASSWORD=postgres \
      EPOCA_VERIFY_RUNTIME_JDBC_LOGIN_EMAIL=local@example.invalid \
      EPOCA_VERIFY_RUNTIME_JDBC_LOGIN_PASSWORD='Aa1!local-only' \
      FAKE_CONTAINER_ID="$fake_container_id" \
      FAKE_DOCKER_INVOCATIONS="$docker_invocations" \
      PATH="$fake_bin:$PATH" \
      bash "$verifier"

  grep -F -- "stop --time 20 $fake_container_id" "$docker_invocations" >/dev/null || \
    die 'runtime JDBC cleanup did not stop the exact container ID returned by docker run'
  if grep -E -- 'stop --time 20 stack-runtime-jdbc-' "$docker_invocations" >/dev/null; then
    die 'runtime JDBC cleanup stopped a container by generated name instead of exact ID'
  fi
  pass 'runtime JDBC cleanup owns the exact container ID returned by Docker'

  status=0
  env \
    EPOCA_VERIFY_APPLICATION_IMAGE=example.invalid/stack:local \
    EPOCA_VERIFY_RUNTIME_JDBC_URL=jdbc:postgresql://host.docker.internal:62028/epoca_stack_runtime_gate \
    EPOCA_VERIFY_RUNTIME_JDBC_USER=postgres \
    EPOCA_VERIFY_RUNTIME_JDBC_PASSWORD=postgres \
    EPOCA_VERIFY_RUNTIME_JDBC_LOGIN_EMAIL=local@example.invalid \
    EPOCA_VERIFY_RUNTIME_JDBC_LOGIN_PASSWORD='Aa1!local-only' \
    FAKE_CONTAINER_ID="$fake_container_id" \
    FAKE_DOCKER_INVOCATIONS="$docker_invocations" \
    FAKE_DOCKER_STOP_STATUS=73 \
    PATH="$fake_bin:$PATH" \
    bash "$verifier" >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr" || status=$?

  [ "$status" -ne 0 ] || die 'runtime JDBC gate masked a container cleanup failure'
  if ! grep -F -- 'Runtime JDBC container cleanup failed' "$TMP_ROOT/stderr" >/dev/null; then
    sed -n '1,120p' "$TMP_ROOT/stderr" >&2
    die 'runtime JDBC gate did not diagnose its container cleanup failure'
  fi
  if grep -F -- 'Runtime JDBC compatibility GREEN' "$TMP_ROOT/stdout" >/dev/null; then
    die 'runtime JDBC gate reported GREEN before exact-container cleanup succeeded'
  fi
  pass 'runtime JDBC gate fails closed when exact-container cleanup fails'
}

assert_no_migration_resources() {
  local run_id=$1
  local case_name=$2
  local container_label="pl.iterators.epoca.stack.migration-run=$run_id"
  local container_ids
  if ! container_ids=$(docker ps -a --filter "label=$container_label" --format '{{.ID}}'); then
    die "$case_name could not audit task-owned migration resources"
  fi
  if [ -n "$container_ids" ]; then
    die "$case_name left a task-owned container after failure"
  fi
}

run_migration_cases() {
  local fixture_root="$TMP_ROOT/migration-data-drift"
  local fake_bin="$fixture_root/bin"
  local changed_migrations="$fixture_root/migrations.sha256"
  local changed_data="$fixture_root/data.tsv"
  local changed_dump_sql="$fixture_root/pre-refresh-lineage.sql"
  local changed_dump="$fixture_root/pre-refresh-lineage.sql.gz"
  local sbt_marker="$fixture_root/sbt-called"
  local sbt_failure_bin="$fixture_root/sbt-failure-bin"
  local sbt_signal_bin="$fixture_root/sbt-signal-bin"
  local sbt_ambient_bin="$fixture_root/sbt-ambient-bin"
  local ambient_marker="$fixture_root/ambient-flyway-contact"
  local ambient_invocations="$fixture_root/ambient-sbt-opts"
  local ambient_machine_opts="$fixture_root/ambient-machine-sbtopts"
  local ambient_secret='stack_non_task_secret'
  local jvmopts_repo="$fixture_root/jvmopts-repo"
  local jvmopts_bin="$fixture_root/jvmopts-bin"
  local jvmopts_docker_marker="$fixture_root/jvmopts-docker-called"
  local jvmopts_secret='stack_jvmopts_secret'
  local sbtopts_repo="$fixture_root/sbtopts-repo"
  local sbtopts_bin="$fixture_root/sbtopts-bin"
  local sbtopts_docker_marker="$fixture_root/sbtopts-docker-called"
  local sbtopts_secret='stack_sbtopts_secret'
  local cleanup_audit_bin="$fixture_root/cleanup-audit-bin"
  local run_id_bin="$fixture_root/run-id-bin"
  local run_id_docker_args="$fixture_root/run-id-docker-args"
  local run_id_38
  local run_id_39
  local collision_bin="$fixture_root/collision-bin"
  local collision_log="$fixture_root/collision-docker-log"
  local collision_nonce="$fixture_root/collision-new-nonce"
  local partial_docker_bin="$fixture_root/partial-docker-bin"
  local partial_owned_marker="$fixture_root/partial-owned-resource"
  local partial_foreign_marker="$fixture_root/partial-foreign-resource"
  local partial_docker_log="$fixture_root/partial-docker-log"
  local partial_no_cid_bin="$fixture_root/partial-no-cid-bin"
  local partial_no_cid_owned_marker="$fixture_root/partial-no-cid-owned-resource"
  local partial_no_cid_foreign_marker="$fixture_root/partial-no-cid-foreign-resource"
  local partial_no_cid_state="$fixture_root/partial-no-cid-state"
  local partial_no_cid_log="$fixture_root/partial-no-cid-log"
  local real_sbt
  local run_id
  local verifier="$REPO_ROOT/scripts/backend-verification/verify-migrations.sh"
  local status=0

  command -v docker >/dev/null 2>&1 || die 'migration-data-drift requires docker'
  mkdir -p "$fixture_root" "$fake_bin"

  mkdir -p "$cleanup_audit_bin"
  printf '#!/usr/bin/env bash\n[ "${1:-}" = ps ] && exit 74\nexit 75\n' \
    >"$cleanup_audit_bin/docker"
  chmod +x "$cleanup_audit_bin/docker"
  status=0
  (
    PATH="$cleanup_audit_bin:$PATH" \
      assert_no_migration_resources "audit_failure_$$_${RANDOM}" 'cleanup audit failure'
  ) \
    >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr" || status=$?
  [ "$status" -ne 0 ] || die 'failed Docker cleanup audit unexpectedly passed'
  if ! grep -F -- 'could not audit task-owned migration resources' \
    "$TMP_ROOT/stderr" >/dev/null; then
    sed -n '1,160p' "$TMP_ROOT/stderr" >&2
    die 'failed Docker cleanup audit did not fail closed'
  fi
  pass 'failed Docker cleanup audit is rejected'

  printf '#!/usr/bin/env bash\n[ "${1:-}" = ps ] && exit 0\nexit 75\n' \
    >"$cleanup_audit_bin/docker"
  PATH="$cleanup_audit_bin:$PATH" \
    assert_no_migration_resources "audit_empty_$$_${RANDOM}" 'empty cleanup audit'
  pass 'empty Docker cleanup audit is accepted'

  mkdir -p "$run_id_bin"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'printf "%s\\n" "$*" >>"$(dirname -- "$0")/../run-id-docker-args"' \
    'exit 76' \
    >"$run_id_bin/docker"
  printf '#!/usr/bin/env bash\nexit 77\n' >"$run_id_bin/sbt"
  chmod +x "$run_id_bin/docker" "$run_id_bin/sbt"
  run_id_38=$(printf 'a%.0s' {1..38})
  status=0
  PATH="$run_id_bin:$PATH" bash "$verifier" --run-id "$run_id_38" \
    >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr" || status=$?
  [ "$status" -ne 0 ] || die '38-character run id unexpectedly completed'
  grep -F -- "--name epoca-stack-migrations-$run_id_38" \
    "$run_id_docker_args" >/dev/null || \
    die '38-character run id was not accepted through Docker create semantics'
  pass '38-character migration run id reaches Docker safely'

  rm -f -- "$run_id_docker_args"
  run_id_39=$(printf 'b%.0s' {1..39})
  status=0
  PATH="$run_id_bin:$PATH" bash "$verifier" --run-id "$run_id_39" \
    >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr" || status=$?
  [ "$status" -ne 0 ] || die '39-character run id unexpectedly passed'
  if ! grep -F -- '--run-id must start with a lowercase letter or digit and contain 1-38 lowercase letters, digits, or underscores' \
    "$TMP_ROOT/stderr" >/dev/null; then
    sed -n '1,160p' "$TMP_ROOT/stderr" >&2
    die '39-character run id did not report the exact identifier limit'
  fi
  [ ! -e "$run_id_docker_args" ] || die '39-character run id reached Docker before rejection'
  pass '39-character migration run id is rejected before Docker'

  mkdir -p "$collision_bin"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'set -euo pipefail' \
    'fixture_root=$(dirname -- "$(dirname -- "$0")")' \
    'log="$fixture_root/collision-docker-log"' \
    'nonce_file="$fixture_root/collision-new-nonce"' \
    'existing_id=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' \
    'existing_nonce=first_invocation_nonce' \
    'command=${1:-}' \
    'shift || true' \
    'case "$command" in' \
    '  run)' \
    '    new_nonce=' \
    '    while [ "$#" -gt 0 ]; do' \
    '      case "$1" in' \
    '        --label)' \
    '          case "$2" in pl.iterators.epoca.stack.migration-nonce=*) new_nonce=${2#*=} ;; esac' \
    '          shift 2' \
    '          ;;' \
    '        *) shift ;;' \
    '      esac' \
    '    done' \
    '    printf "%s" "$new_nonce" >"$nonce_file"' \
    '    exit 125' \
    '    ;;' \
    '  ps)' \
    '    case " $* " in' \
    '      *" label=pl.iterators.epoca.stack.migration-nonce=$existing_nonce "*) printf "%s\\n" "$existing_id" ;;' \
    '      *" label=pl.iterators.epoca.stack.migration-nonce="*) ;;' \
    '      *) printf "%s\\n" "$existing_id" ;;' \
    '    esac' \
    '    ;;' \
    '  inspect)' \
    '    case "$*" in' \
    '      *migration-nonce*) printf "%s|migration-harness|%s|%s|/epoca-stack-migrations-%s|true\\n" "$existing_id" "${FAKE_EXPECTED_RUN_ID:?}" "$existing_nonce" "$FAKE_EXPECTED_RUN_ID" ;;' \
    '      *) printf "%s|migration-harness|%s|/epoca-stack-migrations-%s|true\\n" "$existing_id" "${FAKE_EXPECTED_RUN_ID:?}" "$FAKE_EXPECTED_RUN_ID" ;;' \
    '    esac' \
    '    ;;' \
    '  exec|rm) printf "%s %s\\n" "$command" "$*" >>"$log" ;;' \
    '  *) exit 96 ;;' \
    'esac' \
    >"$collision_bin/docker"
  printf '#!/usr/bin/env bash\nexit 97\n' >"$collision_bin/sbt"
  chmod +x "$collision_bin/docker" "$collision_bin/sbt"
  run_id="collision_$$_${RANDOM}"
  status=0
  FAKE_EXPECTED_RUN_ID="$run_id" PATH="$collision_bin:$PATH" \
    bash "$verifier" --run-id "$run_id" \
    >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr" || status=$?
  [ "$status" -ne 0 ] || die 'same-run-id Docker create conflict unexpectedly passed'
  [ ! -e "$collision_log" ] || {
    sed -n '1,160p' "$collision_log" >&2
    die 'same-run-id create conflict mutated the pre-existing invocation container'
  }
  [ -s "$collision_nonce" ] || die 'migration invocation did not attach a unique ownership nonce'
  if [ "$(cat "$collision_nonce")" = first_invocation_nonce ]; then
    die 'migration invocation reused the pre-existing ownership nonce'
  fi
  if ! grep -Eq '^[0-9a-f]{32}$' "$collision_nonce"; then
    die 'migration invocation ownership nonce is not a valid 32-character lowercase hex label value'
  fi
  pass 'same-run-id create conflict cannot mutate another invocation container'

  mkdir -p "$partial_docker_bin"
  touch "$partial_foreign_marker"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'set -euo pipefail' \
    'fixture_root=$(dirname -- "$(dirname -- "$0")")' \
    'owned_marker="$fixture_root/partial-owned-resource"' \
    'foreign_marker="$fixture_root/partial-foreign-resource"' \
    'log="$fixture_root/partial-docker-log"' \
    'nonce_file="$fixture_root/partial-owned-nonce"' \
    'owned_id=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
    'command=${1:-}' \
    'shift || true' \
    'case "$command" in' \
    '  run)' \
    '    cidfile=' \
    '    nonce=' \
    '    while [ "$#" -gt 0 ]; do' \
    '      case "$1" in' \
    '        --cidfile) cidfile=$2; shift 2 ;;' \
    '        --label)' \
    '          case "$2" in pl.iterators.epoca.stack.migration-nonce=*) nonce=${2#*=} ;; esac' \
    '          shift 2' \
    '          ;;' \
    '        *) shift ;;' \
    '      esac' \
    '    done' \
    '    touch "$owned_marker"' \
    '    printf "%s" "$nonce" >"$nonce_file"' \
    '    if [ -n "$cidfile" ]; then printf "%s" "$owned_id" >"$cidfile"; fi' \
    '    kill -TERM "$PPID"' \
    '    exit 0' \
    '    ;;' \
    '  ps)' \
    '    if [ -e "$owned_marker" ]; then printf "%s\\n" "$owned_id"; fi' \
    '    ;;' \
    '  inspect)' \
    '    case "$*" in' \
    '      *.Config.Labels*) printf "%s|migration-harness|%s|%s|/epoca-stack-migrations-%s|true\\n" "$owned_id" "${FAKE_EXPECTED_RUN_ID:?}" "$(cat "$nonce_file")" "$FAKE_EXPECTED_RUN_ID" ;;' \
    '      *RestartCount*) printf "0\\n" ;;' \
    '      *State.Running*) printf "true\\n" ;;' \
    '      *) exit 81 ;;' \
    '    esac' \
    '    ;;' \
    '  rm)' \
    '    printf "rm %s\\n" "$*" >>"$log"' \
    '    [ "${*: -1}" = "$owned_id" ] || exit 78' \
    '    rm -f -- "$owned_marker"' \
    '    ;;' \
    '  exec) [ "${FAKE_CLEANUP_EXEC_FAIL:-false}" != true ] ;;' \
    '  *) exit 79 ;;' \
    'esac' \
    >"$partial_docker_bin/docker"
  printf '#!/usr/bin/env bash\nexit 80\n' >"$partial_docker_bin/sbt"
  chmod +x "$partial_docker_bin/docker" "$partial_docker_bin/sbt"
  run_id="partial_$$_${RANDOM}"
  status=0
  FAKE_EXPECTED_RUN_ID="$run_id" PATH="$partial_docker_bin:$PATH" \
    bash "$verifier" --run-id "$run_id" \
    >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr" || status=$?
  [ "$status" -eq 143 ] || die "partial Docker creation returned $status instead of 143"
  if [ -e "$partial_owned_marker" ]; then
    sed -n '1,160p' "$TMP_ROOT/stderr" >&2
    [ ! -f "$partial_docker_log" ] || sed -n '1,160p' "$partial_docker_log" >&2
    die 'partial Docker creation left the exact task-owned container'
  fi
  [ -e "$partial_foreign_marker" ] || \
    die 'partial Docker cleanup removed a foreign resource'
  grep -F -- 'rm --force aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
    "$partial_docker_log" >/dev/null || \
    die 'partial Docker cleanup did not remove the exact owned container ID'
  pass 'partial Docker creation interruption removes only the exact owned container'

  mkdir -p "$partial_no_cid_bin"
  touch "$partial_no_cid_foreign_marker"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'set -euo pipefail' \
    'fixture_root=$(dirname -- "$(dirname -- "$0")")' \
    'owned_marker="$fixture_root/partial-no-cid-owned-resource"' \
    'foreign_marker="$fixture_root/partial-no-cid-foreign-resource"' \
    'state="$fixture_root/partial-no-cid-state"' \
    'log="$fixture_root/partial-no-cid-log"' \
    'owned_id=cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc' \
    'command=${1:-}' \
    'shift || true' \
    'case "$command" in' \
    '  run)' \
    '    cidfile=' \
    '    owner=' \
    '    run_id=' \
    '    nonce=' \
    '    name=' \
    '    while [ "$#" -gt 0 ]; do' \
    '      case "$1" in' \
    '        --cidfile) cidfile=$2; shift 2 ;;' \
    '        --name) name=$2; shift 2 ;;' \
    '        --label)' \
    '          case "$2" in' \
    '            pl.iterators.epoca.stack.owner=*) owner=${2#*=} ;;' \
    '            pl.iterators.epoca.stack.migration-run=*) run_id=${2#*=} ;;' \
    '            pl.iterators.epoca.stack.migration-nonce=*) nonce=${2#*=} ;;' \
    '          esac' \
    '          shift 2' \
    '          ;;' \
    '        *) shift ;;' \
    '      esac' \
    '    done' \
    '    [ "$owner" = migration-harness ]' \
    '    [ "$run_id" = "${FAKE_EXPECTED_RUN_ID:?}" ]' \
    '    [ "$name" = "epoca-stack-migrations-$FAKE_EXPECTED_RUN_ID" ]' \
    '    [ -n "$nonce" ]' \
    '    printf "%s|%s|%s|%s|%s\n" "$owner" "$run_id" "$nonce" "$name" "$cidfile" >"$state"' \
    '    touch "$owned_marker"' \
    '    kill -TERM "$PPID"' \
    '    exit 0' \
    '    ;;' \
    '  ps)' \
    '    IFS="|" read -r owner run_id nonce name cidfile <"$state"' \
    '    [ ! -s "$cidfile" ]' \
    '    case " $* " in *" label=pl.iterators.epoca.stack.migration-run=$run_id "*) ;; *) exit 98 ;; esac' \
    '    case " $* " in *" label=pl.iterators.epoca.stack.migration-nonce=$nonce "*) ;; *) exit 99 ;; esac' \
    '    if [ -e "$owned_marker" ]; then printf "%s\n" "$owned_id"; fi' \
    '    ;;' \
    '  inspect)' \
    '    IFS="|" read -r owner run_id nonce name cidfile <"$state"' \
    '    [ "${*: -1}" = "$owned_id" ]' \
    '    printf "%s|%s|%s|%s|/%s|true\n" "$owned_id" "$owner" "$run_id" "$nonce" "$name"' \
    '    ;;' \
    '  exec) [ "${1:-}" = "$owned_id" ] ;;' \
    '  rm)' \
    '    printf "rm %s\n" "$*" >>"$log"' \
    '    [ "${*: -1}" = "$owned_id" ]' \
    '    rm -f -- "$owned_marker"' \
    '    ;;' \
    '  *) exit 100 ;;' \
    'esac' \
    >"$partial_no_cid_bin/docker"
  printf '#!/usr/bin/env bash\nexit 101\n' >"$partial_no_cid_bin/sbt"
  chmod +x "$partial_no_cid_bin/docker" "$partial_no_cid_bin/sbt"
  run_id="partial_no_cid_$$_${RANDOM}"
  status=0
  FAKE_EXPECTED_RUN_ID="$run_id" PATH="$partial_no_cid_bin:$PATH" \
    bash "$verifier" --run-id "$run_id" \
    >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr" || status=$?
  [ "$status" -eq 143 ] || \
    die "no-cidfile partial Docker creation returned $status instead of 143"
  [ ! -e "$partial_no_cid_owned_marker" ] || \
    die 'no-cidfile partial Docker creation left the exact owned container'
  [ -e "$partial_no_cid_foreign_marker" ] || \
    die 'no-cidfile partial Docker cleanup removed a foreign resource'
  grep -F -- 'rm --force cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc' \
    "$partial_no_cid_log" >/dev/null || \
    die 'no-cidfile partial Docker cleanup did not remove the exact owned container ID'
  IFS='|' read -r partial_owner partial_run partial_nonce partial_name partial_cidfile \
    <"$partial_no_cid_state"
  [ "$partial_owner" = migration-harness ] && \
    [ "$partial_run" = "$run_id" ] && \
    [ "$partial_name" = "epoca-stack-migrations-$run_id" ] && \
    grep -Eq '^[0-9a-f]{32}$' <<<"$partial_nonce" && \
    [ ! -s "$partial_cidfile" ] || \
    die 'no-cidfile partial Docker fixture did not record exact invocation ownership'
  pass 'partial Docker creation without cidfile cleans exact labeled invocation'

  rm -f -- "$partial_docker_log"
  run_id="cleanup_primary_$$_${RANDOM}"
  status=0
  FAKE_EXPECTED_RUN_ID="$run_id" FAKE_CLEANUP_EXEC_FAIL=true \
    PATH="$partial_docker_bin:$PATH" bash "$verifier" --run-id "$run_id" \
    >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr" || status=$?
  [ "$status" -eq 143 ] || \
    die "cleanup failure masked primary status 143 with $status"
  grep -F -- 'Failed to clean up the task-owned migration databases or container' \
    "$TMP_ROOT/stderr" >/dev/null || \
    die 'cleanup failure during a primary error was not diagnosed'
  [ ! -e "$partial_owned_marker" ] || \
    die 'cleanup failure did not attempt exact container removal'
  [ -e "$partial_foreign_marker" ] || \
    die 'cleanup failure handling removed a foreign resource'
  pass 'cleanup failure is diagnosed without masking primary status'

  cp "$REPO_ROOT/scripts/backend-verification/accepted/migrations.sha256" "$changed_migrations"
  awk 'NR == 1 { sub(/^./, substr($1, 1, 1) == "0" ? "1" : "0", $1) } { print }' \
    "$changed_migrations" >"$changed_migrations.tmp"
  mv -- "$changed_migrations.tmp" "$changed_migrations"
  cmp -s "$changed_migrations" "$REPO_ROOT/scripts/backend-verification/accepted/migrations.sha256" && \
    die 'migration-checksum fixture did not change the accepted checksum'
  printf '#!/usr/bin/env bash\ntouch %q\n' "$sbt_marker" >"$fake_bin/sbt"
  chmod +x "$fake_bin/sbt"
  run_id="checksum_$$_${RANDOM}"
  status=0
  PATH="$fake_bin:$PATH" bash "$verifier" \
    --accepted-migrations "$changed_migrations" --run-id "$run_id" \
    >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr" || status=$?
  [ "$status" -ne 0 ] || die 'migration-checksum drift unexpectedly passed'
  grep -F -- 'Migration SQL checksums differ from the accepted manifest' \
    "$TMP_ROOT/stderr" >/dev/null || \
    die 'migration-checksum drift did not report the expected mismatch'
  [ ! -e "$sbt_marker" ] || die 'migration-checksum drift invoked sbt before rejection'
  assert_no_migration_resources "$run_id" 'migration-checksum drift'
  pass 'migration checksum drift rejected before sbt with failure cleanup'

  grep -F -- '-Dflyway.ignoreMigrationPatterns=*:pending' "$verifier" >/dev/null || \
    die 'empty migration preflight does not use the Flyway 12 pending pattern'
  ! grep -F -- '-Dflyway.ignorePendingMigrations=' "$verifier" >/dev/null || \
    die 'migration runner retains removed Flyway ignorePendingMigrations configuration'
  [ "$(grep -F -c -- "run_sbt_flyway \"\$database_name\" '*:pending'" "$verifier")" -eq 1 ] || \
    die 'empty migration preflight must enable the pending pattern exactly once'
  [ "$(grep -F -c -- 'run_sbt_flyway "$database_name" strict' "$verifier")" -eq 2 ] || \
    die 'strict migration paths must omit ignore patterns exactly twice'
  pass 'empty migration preflight is isolated from strict validation'

  mkdir -p \
    "$sbtopts_repo/scripts/backend-verification/accepted" \
    "$sbtopts_repo/src/main/resources/db/migration" \
    "$sbtopts_bin"
  cp "$verifier" "$sbtopts_repo/scripts/backend-verification/verify-migrations.sh"
  cp "$REPO_ROOT/scripts/backend-verification/snapshot-database.sh" "$sbtopts_repo/scripts/backend-verification/snapshot-database.sh"
  cp "$REPO_ROOT/scripts/backend-verification/accepted/data.tsv" "$sbtopts_repo/scripts/backend-verification/accepted/data.tsv"
  cp "$REPO_ROOT/scripts/backend-verification/accepted/migrations.sha256" "$sbtopts_repo/scripts/backend-verification/accepted/migrations.sha256"
  cp "$REPO_ROOT/scripts/backend-verification/accepted/pre-refresh.sql.gz" "$sbtopts_repo/scripts/backend-verification/accepted/pre-refresh.sql.gz"
  cp "$REPO_ROOT"/src/main/resources/db/migration/*.sql \
    "$sbtopts_repo/src/main/resources/db/migration/"
  printf '#!/usr/bin/env bash\ntouch %q\nexit 90\n' \
    "$sbtopts_docker_marker" >"$sbtopts_bin/docker"
  printf '#!/usr/bin/env bash\nexit 91\n' >"$sbtopts_bin/sbt"
  chmod +x "$sbtopts_bin/docker" "$sbtopts_bin/sbt"
  local sbtopts_case_number=0
  local sbtopts_case_name
  local sbtopts_case_token
  while IFS=$'\t' read -r sbtopts_case_name sbtopts_case_token; do
    sbtopts_case_number=$((sbtopts_case_number + 1))
    rm -f -- "$sbtopts_docker_marker"
    printf '%s\n' "$sbtopts_case_token" >"$sbtopts_repo/.sbtopts"
    [ "$(wc -l <"$sbtopts_repo/.sbtopts" | tr -d '[:space:]')" = 1 ] || \
      die "repository .sbtopts $sbtopts_case_name fixture is not isolated"
    status=0
    (
      cd "$sbtopts_repo"
      PATH="$sbtopts_bin:$PATH" bash scripts/backend-verification/verify-migrations.sh \
        --run-id "sbtopts_${sbtopts_case_number}_$$_${RANDOM}"
    ) >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr" || status=$?
    [ "$status" -ne 0 ] || \
      die "repository .sbtopts $sbtopts_case_name override unexpectedly passed"
    if ! grep -F -- 'Repository .sbtopts contains forbidden' \
      "$TMP_ROOT/stderr" >/dev/null; then
      sed -n '1,160p' "$TMP_ROOT/stderr" >&2
      die "repository .sbtopts $sbtopts_case_name override did not fail closed"
    fi
    [ ! -e "$sbtopts_docker_marker" ] || \
      die "repository .sbtopts $sbtopts_case_name override reached Docker before rejection"
    if grep -F -- "$sbtopts_secret" "$TMP_ROOT/stdout" "$TMP_ROOT/stderr" >/dev/null; then
      die "repository .sbtopts $sbtopts_case_name rejection leaked a property value"
    fi
    pass "repository .sbtopts $sbtopts_case_name override fails closed before Docker"
  done <<EOF
flyway-system-property	-Dflyway.url=jdbc:postgresql://$sbtopts_secret.invalid/foreign
flyway-J-system-property	-J-Dflyway.locations=filesystem:/$sbtopts_secret
flyway-ignore-pattern	-Dflyway.ignoreMigrationPatterns=*:$sbtopts_secret
flyway-J-ignore-pattern	-J-Dflyway.ignoreMigrationPatterns=*:$sbtopts_secret
double-dash-client	--client
single-dash-client	-client
double-dash-no-server	--no-server
single-dash-no-server	-no-server
jvm-client	--jvm-client
EOF
  [ "$sbtopts_case_number" -eq 9 ] || die 'repository .sbtopts rejection matrix is incomplete'

  real_sbt=$(command -v sbt)
  mkdir -p "$sbt_ambient_bin"
  printf '%s\n' \
    "-Dflyway.url=jdbc:postgresql://$ambient_secret.invalid/machine" \
    "-Dflyway.ignoreMigrationPatterns=*:$ambient_secret" \
    >"$ambient_machine_opts"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'set -euo pipefail' \
    'marker=$(dirname -- "$0")/../ambient-flyway-contact' \
    'invocations=$(dirname -- "$0")/../ambient-sbt-opts' \
    'for carrier in JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS JAVA_OPTS JVM_OPTS; do' \
    '  if printenv "$carrier" >/dev/null 2>&1; then touch "$marker"; exit 86; fi' \
    'done' \
    'for carrier in SBT_NATIVE_CLIENT SBTN_CMD; do' \
    '  if printenv "$carrier" >/dev/null 2>&1; then touch "$marker"; exit 92; fi' \
    'done' \
    '[ -f "${SBT_ETC_FILE:-}" ] && [ -r "$SBT_ETC_FILE" ] && [ ! -s "$SBT_ETC_FILE" ] || { touch "$marker"; exit 93; }' \
    'case " $* " in *" --server "*) ;; *) touch "$marker"; exit 94 ;; esac' \
    'case ${SBT_OPTS:-} in *stack_non_task_secret*) touch "$marker"; exit 87 ;; esac' \
    'printf "%s\\n" "${SBT_OPTS:-}" >>"$invocations"' \
    "exec $(printf '%q' "$real_sbt") \"\$@\"" \
    >"$sbt_ambient_bin/sbt"
  chmod +x "$sbt_ambient_bin/sbt"
  run_id="ambient_$$_${RANDOM}"
  status=0
  env \
    JAVA_TOOL_OPTIONS="-Dflyway.url=jdbc:postgresql://$ambient_secret.invalid/foreign -Dflyway.ignorePendingMigrations=true -Dflyway.ignoreMigrationPatterns=*:$ambient_secret" \
    JDK_JAVA_OPTIONS="-Dflyway.locations=filesystem:/$ambient_secret" \
    _JAVA_OPTIONS="-Dflyway.schemas=$ambient_secret" \
    JAVA_OPTS="-Dflyway.table=$ambient_secret" \
    JVM_OPTS="-Dflyway.url=jdbc:postgresql://$ambient_secret.invalid/foreign" \
    SBT_ETC_FILE="$ambient_machine_opts" \
    SBT_NATIVE_CLIENT=true \
    SBTN_CMD="/$ambient_secret/sbtn" \
    PATH="$sbt_ambient_bin:$PATH" \
    bash "$verifier" --run-id "$run_id" \
    >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr" || status=$?
  if [ "$status" -ne 0 ]; then
    sed -n '1,160p' "$TMP_ROOT/stderr" >&2
    die 'ambient Flyway JVM properties were not isolated from controlled sbt'
  fi
  [ ! -e "$ambient_marker" ] || die 'ambient Flyway JVM properties reached sbt or a non-task target'
  [ "$(wc -l <"$ambient_invocations")" -eq 3 ] || \
    die 'ambient isolation did not exercise all three Flyway sbt processes'
  [ "$(grep -F -c -- '-Dflyway.ignoreMigrationPatterns=*:pending' "$ambient_invocations")" -eq 1 ] || \
    die 'ambient isolation did not preserve exactly one controlled preflight'
  [ "$(grep -F -c -- '-Dflyway.ignoreMigrationPatterns=' "$ambient_invocations")" -eq 1 ] || \
    die 'ambient isolation leaked ignoreMigrationPatterns into a strict process'
  if grep -F -- '-Dflyway.ignorePendingMigrations=' "$ambient_invocations" >/dev/null; then
    die 'ambient isolation leaked removed ignorePendingMigrations configuration'
  fi
  if grep -F -- "$ambient_secret" "$TMP_ROOT/stdout" "$TMP_ROOT/stderr" "$ambient_invocations" >/dev/null; then
    die 'ambient Flyway diagnostic leaked a rejected property value'
  fi
  assert_no_migration_resources "$run_id" 'ambient Flyway isolation'
  pass 'ambient Flyway JVM properties cannot redirect controlled sbt'

  mkdir -p \
    "$jvmopts_repo/scripts/backend-verification/accepted" \
    "$jvmopts_repo/src/main/resources/db/migration" \
    "$jvmopts_bin"
  cp "$verifier" "$jvmopts_repo/scripts/backend-verification/verify-migrations.sh"
  cp "$REPO_ROOT/scripts/backend-verification/snapshot-database.sh" "$jvmopts_repo/scripts/backend-verification/snapshot-database.sh"
  cp "$REPO_ROOT/scripts/backend-verification/accepted/data.tsv" "$jvmopts_repo/scripts/backend-verification/accepted/data.tsv"
  cp "$REPO_ROOT/scripts/backend-verification/accepted/migrations.sha256" "$jvmopts_repo/scripts/backend-verification/accepted/migrations.sha256"
  cp "$REPO_ROOT/scripts/backend-verification/accepted/pre-refresh.sql.gz" "$jvmopts_repo/scripts/backend-verification/accepted/pre-refresh.sql.gz"
  cp "$REPO_ROOT"/src/main/resources/db/migration/*.sql \
    "$jvmopts_repo/src/main/resources/db/migration/"
  printf '%s\n' \
    "-Dflyway.url=jdbc:postgresql://$jvmopts_secret.invalid/foreign" \
    "-Dflyway.ignoreMigrationPatterns=*:$jvmopts_secret" \
    >"$jvmopts_repo/.jvmopts"
  printf '#!/usr/bin/env bash\ntouch %q\nexit 88\n' \
    "$jvmopts_docker_marker" >"$jvmopts_bin/docker"
  printf '#!/usr/bin/env bash\nexit 89\n' >"$jvmopts_bin/sbt"
  chmod +x "$jvmopts_bin/docker" "$jvmopts_bin/sbt"
  status=0
  (
    cd "$jvmopts_repo"
    PATH="$jvmopts_bin:$PATH" bash scripts/backend-verification/verify-migrations.sh \
      --run-id "jvmopts_$$_${RANDOM}"
  ) >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr" || status=$?
  [ "$status" -ne 0 ] || die 'repository .jvmopts Flyway override unexpectedly passed'
  if ! grep -F -- 'Repository .jvmopts contains forbidden Flyway system properties' \
    "$TMP_ROOT/stderr" >/dev/null; then
    sed -n '1,160p' "$TMP_ROOT/stderr" >&2
    die 'repository .jvmopts Flyway override did not fail closed'
  fi
  [ ! -e "$jvmopts_docker_marker" ] || \
    die 'repository .jvmopts Flyway override reached Docker before rejection'
  if grep -F -- "$jvmopts_secret" "$TMP_ROOT/stdout" "$TMP_ROOT/stderr" >/dev/null; then
    die 'repository .jvmopts rejection leaked a property value'
  fi
  pass 'repository .jvmopts Flyway overrides fail closed before Docker'

  awk -F '\t' 'BEGIN { OFS = "\t" }
    NR == 2 { sub(/^./, substr($3, 1, 1) == "0" ? "1" : "0", $3) }
    { print }
  ' "$REPO_ROOT/scripts/backend-verification/accepted/data.tsv" >"$changed_data"
  cmp -s "$changed_data" "$REPO_ROOT/scripts/backend-verification/accepted/data.tsv" && \
    die 'migration-data-drift fixture did not change the accepted data hash'

  run_id="data_$$_${RANDOM}"
  status=0
  bash "$verifier" --accepted-data "$changed_data" --run-id "$run_id" \
    >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr" || status=$?
  [ "$status" -ne 0 ] || die 'migration-data-drift unexpectedly passed'
  if ! grep -F -- 'Restored data snapshot does not match accepted data snapshot' \
    "$TMP_ROOT/stderr" >/dev/null; then
    sed -n '1,160p' "$TMP_ROOT/stderr" >&2
    die 'migration-data-drift did not report the expected data mismatch'
  fi
  assert_no_migration_resources "$run_id" 'migration-data-drift'
  pass 'migration data drift rejected with failure cleanup'

  mkdir -p "$sbt_failure_bin"
  printf '#!/usr/bin/env bash\nexit 73\n' >"$sbt_failure_bin/sbt"
  chmod +x "$sbt_failure_bin/sbt"
  run_id="sbt_failure_$$_${RANDOM}"
  status=0
  PATH="$sbt_failure_bin:$PATH" bash "$verifier" --run-id "$run_id" \
    >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr" || status=$?
  [ "$status" -ne 0 ] || die 'migration sbt failure unexpectedly passed'
  if ! grep -F -- 'Flyway command failed during empty preflight; see ' \
    "$TMP_ROOT/stderr" >/dev/null; then
    sed -n '1,160p' "$TMP_ROOT/stderr" >&2
    die 'migration sbt failure did not report its retained log'
  fi
  assert_no_migration_resources "$run_id" 'migration sbt failure'
  pass 'migration sbt failure reports retained log with failure cleanup'

  mkdir -p "$sbt_signal_bin"
  printf '#!/usr/bin/env bash\nkill -TERM "$PPID"\nexit 0\n' >"$sbt_signal_bin/sbt"
  chmod +x "$sbt_signal_bin/sbt"
  run_id="signal_$$_${RANDOM}"
  status=0
  PATH="$sbt_signal_bin:$PATH" bash "$verifier" --run-id "$run_id" \
    >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr" || status=$?
  [ "$status" -eq 143 ] || die "migration termination returned $status instead of 143"
  assert_no_migration_resources "$run_id" 'migration termination'
  pass 'migration termination preserves failure status with cleanup'

  gzip -dc "$REPO_ROOT/scripts/backend-verification/accepted/pre-refresh.sql.gz" |
    awk 'BEGIN {
      expected = "0\t\\N\t<< Flyway Schema Creation >>\tSCHEMA\t\"quartz\"\t\\N\tpostgres\t2026-08-12 15:23:50.298167\t0\tt"
      replacement = "0\t\\N\t<< Flyway Schema Creation >>\tSCHEMA\t\"public\"\t\\N\tpostgres\t2026-08-12 15:23:50.298167\t0\tt"
    }
    $0 == expected { print replacement; changed += 1; next }
    { print }
    END { if (changed != 1) exit 42 }
    ' >"$changed_dump_sql"
  gzip -n -c "$changed_dump_sql" >"$changed_dump"
  gzip -t "$changed_dump"
  run_id="lineage_$$_${RANDOM}"
  status=0
  bash "$verifier" --dump "$changed_dump" --run-id "$run_id" \
    >"$TMP_ROOT/stdout" 2>"$TMP_ROOT/stderr" || status=$?
  [ "$status" -ne 0 ] || die 'migration-lineage drift unexpectedly passed'
  if ! grep -F -- 'Flyway lineage differs between restored and empty database paths' \
    "$TMP_ROOT/stderr" >/dev/null; then
    sed -n '1,160p' "$TMP_ROOT/stderr" >&2
    die 'migration-lineage drift did not report the expected mismatch'
  fi
  assert_no_migration_resources "$run_id" 'migration-lineage drift'
  pass 'migration lineage drift rejected with failure cleanup'
}

if [ "$SELECTED_CASE" = all ] || [ "$SELECTED_CASE" = fast ]; then
  bash "$REPO_ROOT/scripts/backend-verification/check-names.sh"
fi

if [ "$SELECTED_CASE" = all ] || [ "$SELECTED_CASE" = fast ] || [ "$SELECTED_CASE" = dependency-policy ]; then
  run_dependency_cases
fi
if [ "$SELECTED_CASE" = all ] || [ "$SELECTED_CASE" = fast ] || [ "$SELECTED_CASE" = database-snapshot ]; then
  run_database_cases
fi
if [ "$SELECTED_CASE" = all ] || [ "$SELECTED_CASE" = contracts ]; then
  run_contract_cases
fi
if [ "$SELECTED_CASE" = all ] || [ "$SELECTED_CASE" = fast ] || [ "$SELECTED_CASE" = jvm-images ]; then
  run_jvm_image_cases
fi
if [ "$SELECTED_CASE" = all ] || [ "$SELECTED_CASE" = migration-data-drift ]; then
  run_migration_cases
fi
if [ "$SELECTED_CASE" = all ] || [ "$SELECTED_CASE" = fast ] || [ "$SELECTED_CASE" = runner ]; then
  run_runner_cases
fi
if [ "$SELECTED_CASE" = all ] || [ "$SELECTED_CASE" = fast ] || [ "$SELECTED_CASE" = runtime-jdbc ]; then
  run_runtime_jdbc_cases
fi
