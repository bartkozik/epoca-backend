# Local development stack

`run.sh` starts Época locally without Docker: a native PostgreSQL, the backend through `sbt run` and the frontend through the Vite dev server, with the demo seed from [`../demo-seed`](../demo-seed/README.md) loaded into a disposable local database.

## Prerequisites

- PostgreSQL 13+ running locally and reachable on `127.0.0.1:5432` (for example `brew services start postgresql@16`), with `psql`, `createdb` and `dropdb` on `PATH`.
- JDK 17 and sbt 1.8.3. The script uses `JAVA_HOME` when it points at JDK 17, otherwise it looks for one with `/usr/libexec/java_home` or SDKMAN.
- For the frontend: the `epoca-frontend` clone next to this repository (or `EPOCA_FRONTEND_DIR`), its dependencies installed with the pinned Yarn, and the Node version from its `.nvmrc`.
- `htpasswd` (ships with macOS) if you set `LOCAL_DEMO_PASSWORD`.

## Commands

| Command | Effect |
| --- | --- |
| `scripts/local-dev/run.sh reset` | Drops and recreates the local database (all its data is lost), runs `sbt flywayMigrate`, applies the demo seed |
| `scripts/local-dev/run.sh reseed` | Re-applies the demo seed; on a later date it rolls the 12-week activity window forward |
| `scripts/local-dev/run.sh up` | Starts the backend and the frontend in the background; logs go to `$TMPDIR/epoca-local/` |
| `scripts/local-dev/run.sh down` | Stops every process that listens on the backend and frontend ports, including ones the script did not start |
| `scripts/local-dev/run.sh status` | Shows whether both are up |
| `scripts/local-dev/run.sh users` | Lists the seeded demo logins with their roles |

## Local settings

Put overrides in `scripts/local-dev/local.env`, which git ignores:

```bash
# Seeded accounts get this password instead of the shared demo password.
LOCAL_DEMO_PASSWORD='choose-a-local-password'
# Use when port 9000 is taken locally, for example by a MinIO/SILO container.
BACKEND_PORT=9100
```

Other supported overrides: `EPOCA_LOCAL_DB` (default `epoca`; the demo seed accepts only `epoca` and `epoca_ct532`), `DB_USER` (default: your OS user), `DB_PASSWORD` (default `local`), `FRONTEND_PORT`, `EPOCA_FRONTEND_DIR`, `EPOCA_LOCAL_STATE`, `SBT_MEM` (sbt heap in MB, default `4096`), `JWT_SECRET` and `SECRET_KEY` (local-only defaults are set by the script).

The frontend dev server proxies `/api` to the local backend through `EPOCA_LOCAL_PROXY_TARGET`; without it Vite would proxy to staging. If the frontend has no `.env.local`, the script creates one with `VITE_API_BASE_URL=/api/v1`, because a CRA-era `.env` (`REACT_APP_*`) is ignored by Vite.

## Known limitations

- The local database uses the `public` schema. The demo seed hardcodes `public.` and cannot target a non-public schema such as production's `epoca`.
