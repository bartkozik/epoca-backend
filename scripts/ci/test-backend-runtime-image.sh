#!/usr/bin/env bash
set -euo pipefail

image=${1:?backend image is required}
migration_image=${2:?migration image is required}
expected_revision=${CI_COMMIT_SHA:?CI_COMMIT_SHA is required for the runtime-image revision check}

actual_revision=$(docker image inspect \
  --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' \
  "$image")
[[ "$actual_revision" == "$expected_revision" ]] || {
  echo "backend image revision does not match the exact commit" >&2
  exit 1
}

actual_migration_revision=$(docker image inspect \
  --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' \
  "$migration_image")
[[ "$actual_migration_revision" == "$expected_revision" ]] || {
  echo "migration image revision does not match the exact commit" >&2
  exit 1
}

exposed_ports=$(docker image inspect --format '{{json .Config.ExposedPorts}}' "$image")
for control_port in $(seq 19000 19999); do
  [[ "$exposed_ports" != *"\"$control_port/tcp\""* ]] || {
    echo "fixture control port must not be exposed" >&2
    exit 1
  }
done

baked_environment=$(docker image inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$image")
if grep -Eq '^(OAUTH_E2E_FIXTURE_[^=]*=|EPOCA_DEPLOYMENT_TIER=disposable$)' <<<"$baked_environment"; then
  echo "fixture configuration must not be baked into the backend image" >&2
  exit 1
fi

health_start_period_ns=$(docker image inspect \
  --format '{{printf "%d" .Config.Healthcheck.StartPeriod}}' \
  "$image")
((health_start_period_ns >= 30000000000)) || {
  echo "backend health check must ignore startup failures for at least 30 seconds" >&2
  exit 1
}

docker run --rm --entrypoint sh "$image" -ceu '
  test "$(id -u)" = "1001"
  test "$(id -un)" = "epoca"
  test "$(getent passwd 1001 | cut -d: -f1)" = "epoca"
  test "$HOME" = "/home/epoca"
  test -d /home/epoca
  test -w /tmp
  test -x /opt/docker/bin/epoca-backend
  java -version >/dev/null 2>&1
'

runtime_suffix="${CI_COMMIT_SHA:0:12}-${RANDOM}-$$"
runtime_network="backend-runtime-image-$runtime_suffix"
postgres_container="backend-runtime-postgres-$runtime_suffix"
runtime_database="backend_runtime_${CI_COMMIT_SHA:0:12}_${RANDOM}_$$"
runtime_db_user=postgres
runtime_db_password="backend-runtime-postgres-synthetic-$runtime_suffix"
runtime_db_url="jdbc:postgresql://$postgres_container:5432/$runtime_database"
runtime_jwt_secret="backend-runtime-image-jwt-synthetic-$runtime_suffix"
runtime_operational_secret="backend-runtime-image-operational-synthetic-$runtime_suffix"
runtime_container="backend-runtime-backend-$runtime_suffix"
uid_probe_container="backend-runtime-uid-$runtime_suffix"
runtime_port=18080
runtime_probe_root=$(mktemp -d)
runtime_probe_source="$runtime_probe_root/source"
runtime_probe_classes="$runtime_probe_root/classes"
mkdir -p "$runtime_probe_source" "$runtime_probe_classes"

cleanup_runtime_resources() {
  docker rm -f "$uid_probe_container" >/dev/null 2>&1 || true
  docker rm -f "$runtime_container" >/dev/null 2>&1 || true
  docker rm -f "$postgres_container" >/dev/null 2>&1 || true
  docker network rm "$runtime_network" >/dev/null 2>&1 || true
  rm -rf -- "$runtime_probe_root"
}
trap cleanup_runtime_resources EXIT

cat > "$runtime_probe_source/PivotExportUidProbe.java" <<'JAVA'
public class PivotExportUidProbe {
  public static void main(String[] args) throws Exception {
    Class<?> type = Class.forName("pl.iterators.epoca.export.domain.EffectiveUserId$");
    Object module = type.getField("MODULE$").get(null);
    long uid = ((Number) type.getMethod("current").invoke(module)).longValue();
    if (uid != 12345L) throw new IllegalStateException("unexpected filesystem UID: " + uid);
  }
}
JAVA

cat > "$runtime_probe_source/FixtureControlListenerNegativeProbe.java" <<'JAVA'
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

public class FixtureControlListenerNegativeProbe {
  public static void main(String[] args) throws Exception {
    try (var listener = new ServerSocket(19001, 1, InetAddress.getLoopbackAddress())) {
      Files.createFile(Path.of(args[0]));
      Thread.sleep(Long.MAX_VALUE);
    }
  }
}
JAVA

cat > "$runtime_probe_source/FixtureTableAbsenceProbe.java" <<'JAVA'
import java.sql.DriverManager;
import java.util.Set;

public class FixtureTableAbsenceProbe {
  public static void main(String[] args) throws Exception {
    Set<String> forbidden = Set.of("oauth_e2e_fixture_run", "oauth_e2e_fixture_audit", "oauth_e2e_fixture_ownership");
    try (var connection = DriverManager.getConnection(
             System.getenv("DB_URL"), System.getenv("DB_USER"), System.getenv("DB_PASSWORD"));
         var statement = connection.prepareStatement(
             "select table_name from information_schema.tables where table_schema = 'public' and table_name = any (?)")) {
      var values = connection.createArrayOf("text", forbidden.toArray());
      statement.setArray(1, values);
      try (var rows = statement.executeQuery()) {
        if (rows.next()) throw new IllegalStateException("fixture table exists in normal mode");
      }
    }
  }
}
JAVA

javac --release 17 -d "$runtime_probe_classes" "$runtime_probe_source"/*.java

docker create --name "$uid_probe_container" --user 12345:12345 --entrypoint sh "$image" -ceu '
  test "$(id -u)" = "12345"
  ! id -un >/dev/null 2>&1
  test -r /proc/self/status
  test "$(awk '\''$1 == "Uid:" { print $5; exit }'\'' /proc/self/status)" = "12345"

  probe=$(mktemp)
  trap '\''rm -f -- "$probe"'\'' EXIT
  test "$(stat -c %u "$probe")" = "12345"

  test -w /tmp
  test -x /opt/docker/bin/epoca-backend
  java -version >/dev/null 2>&1
  java --class-path "/runtime-probes:/opt/docker/lib/*" PivotExportUidProbe
' >/dev/null
docker cp "$runtime_probe_classes/." "$uid_probe_container:/runtime-probes"
docker start --attach "$uid_probe_container"
docker rm "$uid_probe_container" >/dev/null

docker pull postgres:13.4 >/dev/null
docker network create "$runtime_network" >/dev/null
docker run --detach --name "$postgres_container" \
  --network "$runtime_network" \
  --env POSTGRES_DB="$runtime_database" \
  --env POSTGRES_USER="$runtime_db_user" \
  --env POSTGRES_PASSWORD="$runtime_db_password" \
  postgres:13.4 >/dev/null

# The image's temporary socket-only server can answer pg_isready before POSTGRES_DB exists.
postgres_ready=false
for _ in $(seq 1 60); do
  if postgres_ready_result=$(docker exec \
      --env PGPASSWORD="$runtime_db_password" \
      --env PGCONNECT_TIMEOUT=2 \
      "$postgres_container" \
      psql --host 127.0.0.1 --no-psqlrc --no-password \
        --username "$runtime_db_user" --dbname "$runtime_database" \
        --tuples-only --no-align --command 'select 1' 2>/dev/null | tr -d '[:space:]') &&
      [[ "$postgres_ready_result" == 1 ]]; then
    postgres_ready=true
    break
  fi
  sleep 1
done
[[ "$postgres_ready" == true ]] || {
  echo "disposable runtime-image PostgreSQL did not become ready" >&2
  exit 1
}

postgres_version_number=$(docker exec \
  --env PGPASSWORD="$runtime_db_password" \
  --env PGCONNECT_TIMEOUT=2 \
  "$postgres_container" \
  psql --host 127.0.0.1 --no-psqlrc --no-password \
    --username "$runtime_db_user" --dbname "$runtime_database" \
    --tuples-only --no-align --command 'show server_version_num' | tr -d '[:space:]')
[[ "$postgres_version_number" == 130004 ]] || {
  echo "runtime-image PostgreSQL version does not match 13.4" >&2
  exit 1
}

DB_URL=$runtime_db_url \
DB_USER=$runtime_db_user \
DB_PASSWORD=$runtime_db_password \
docker run --rm \
  --network "$runtime_network" \
  --env DB_URL \
  --env DB_USER \
  --env DB_PASSWORD \
  "$migration_image" \
  bash -ceu '
    exec flyway-10.20.0/flyway migrate \
      "-url=$DB_URL" \
      "-user=$DB_USER" \
      "-password=$DB_PASSWORD" \
      -table=schema_version \
      -locations=filesystem:/sql
  ' >/dev/null

DB_URL=$runtime_db_url \
DB_USER=$runtime_db_user \
DB_PASSWORD=$runtime_db_password \
JWT_SECRET=$runtime_jwt_secret \
SECRET_KEY=$runtime_operational_secret \
PORT=$runtime_port \
docker run --detach --name "$runtime_container" \
  --network "$runtime_network" \
  --env DB_URL \
  --env DB_USER \
  --env DB_PASSWORD \
  --env JWT_SECRET \
  --env SECRET_KEY \
  --env PORT \
  "$image" >/dev/null
docker cp "$runtime_probe_classes/." "$runtime_container:/runtime-probes"

for _ in $(seq 1 60); do
  if ! docker inspect --format '{{.State.Running}}' "$runtime_container" | grep -Fx true >/dev/null; then
    echo "normal-default runtime-image process exited before becoming healthy" >&2
    exit 1
  fi
  if docker exec "$runtime_container" curl --fail --silent --show-error "http://127.0.0.1:$runtime_port/api/v1/health-check" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

docker exec "$runtime_container" curl --fail --silent --show-error "http://127.0.0.1:$runtime_port/api/v1/health-check" >/dev/null

docker exec "$runtime_container" sh -ceu '
  assert_non_disposable_deployment_tier() {
    if test "${EPOCA_DEPLOYMENT_TIER:-normal}" = "disposable"; then
      echo "disposable fixture deployment tier is forbidden in the normal runtime probe" >&2
      return 1
    fi
  }

  assert_no_fixture_control_listener() {
    for sockets in /proc/net/tcp /proc/net/tcp6; do
      while read -r slot local_address remote_address state rest; do
        test "$state" = "0A" || continue
        port_hex=${local_address##*:}
        port=$((0x$port_hex))
        if test "$port" -ge 19000 && test "$port" -le 19999; then
          echo "fixture control listener must remain closed" >&2
          return 1
        fi
      done < "$sockets"
    done
  }

  for forbidden_fixture_variable in \
    OAUTH_E2E_FIXTURE_TIME_ENABLED \
    OAUTH_E2E_FIXTURE_CONTROL_LISTENER_ENABLED \
    OAUTH_E2E_FIXTURE_CONTROL_INTERFACE \
    OAUTH_E2E_FIXTURE_CONTROL_PORT \
    OAUTH_E2E_FIXTURE_CONTROL_TOKEN \
    OAUTH_E2E_FIXTURE_DISPOSABLE_ACK; do
    ! env | grep -Eq "^${forbidden_fixture_variable}="
  done

  assert_non_disposable_deployment_tier
  tier_was_set=false
  if test "${EPOCA_DEPLOYMENT_TIER+x}" = x; then
    tier_was_set=true
    original_deployment_tier=$EPOCA_DEPLOYMENT_TIER
  fi
  EPOCA_DEPLOYMENT_TIER=disposable
  if assert_non_disposable_deployment_tier 2>/dev/null; then
    echo "runtime deployment-tier sensitivity probe accepted disposable" >&2
    exit 1
  fi
  EPOCA_DEPLOYMENT_TIER=normal
  assert_non_disposable_deployment_tier
  if test "$tier_was_set" = true; then
    EPOCA_DEPLOYMENT_TIER=$original_deployment_tier
  else
    unset EPOCA_DEPLOYMENT_TIER
  fi

  assert_no_fixture_control_listener

  listener_ready=/tmp/FixtureControlListenerNegativeProbe.ready
  listener_pid=
  cleanup_listener_probe() {
    if test -n "$listener_pid"; then
      kill "$listener_pid" >/dev/null 2>&1 || true
      wait "$listener_pid" >/dev/null 2>&1 || true
    fi
    rm -f -- "$listener_ready"
  }
  trap cleanup_listener_probe EXIT
  java --class-path /runtime-probes FixtureControlListenerNegativeProbe "$listener_ready" &
  listener_pid=$!
  for ignored in $(seq 1 100); do
    test -f "$listener_ready" && break
    kill -0 "$listener_pid" >/dev/null 2>&1 || break
    sleep 0.05
  done
  test -f "$listener_ready"
  if assert_no_fixture_control_listener 2>/dev/null; then
    echo "runtime listener sensitivity probe did not detect the fixture port" >&2
    exit 1
  fi
  kill "$listener_pid"
  wait "$listener_pid" >/dev/null 2>&1 || true
  listener_pid=
  rm -f -- "$listener_ready"
  assert_no_fixture_control_listener

  java --class-path "/runtime-probes:/opt/docker/lib/*" FixtureTableAbsenceProbe
'

published_ports=$(docker inspect --format '{{json .NetworkSettings.Ports}}' "$runtime_container")
for control_port in $(seq 19000 19999); do
  [[ "$published_ports" != *"\"$control_port/tcp\""* ]] || {
    echo "fixture control port must not be published" >&2
    exit 1
  }
done
