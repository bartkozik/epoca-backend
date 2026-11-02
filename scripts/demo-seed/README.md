# Demo data seed runbook

The demo seed is an explicit operator tool for a local Época database and the approved `iteratorstesting` staging organization. It is not a migration, application-start hook, or deployment step. Production is not a supported target.

## Seeded dataset and behavior

After a successful apply, the target organization has exactly 30 active people: 2 Owner, 2 Manager, 22 Employee, and 4 External, plus exactly two active superadmins in total. Existing active superadmins count toward that total; the seed flags only the missing number of internal mock profiles. On an empty local database those profiles are an Owner and a Manager. Every active mock profile has one confirmed MailAddress login and the same shared demo password, distributed separately from this repository. Existing nonseed accounts, passwords, roles, superadmin flags, and sessions are never changed.

The seed owns 10 clients, 16 projects, their deterministic project teams, 1,200 active Worklogs, 100 active Notes, and 60 archived historical Worklogs. The Project Owner is the Project Manager for seed semantics; all other project assignments are Members. External people are assigned only to active projects for active external clients.

Active Worklogs and Notes cover a 12-week rolling window ending yesterday. It contains all 60 weekdays plus six sampled weekend days, spans current and previous reporting months when the calendar permits, and exercises reports, calendar, pivot, and export views. The archived history belongs to archived projects and remains outside active totals.

A dry-run performs the complete preflight, prints the projected change plan and verification summary, then rolls the transaction back. Apply is catalog-driven and idempotent: a same-day rerun is byte-stable and reports zero changes. On a later date, the tool rolls the owned activity window forward, updates the deterministic target, and archives retired seed-owned Worklogs or Notes instead of deleting them. It never adopts unrelated rows, and standalone verification is read-only.

### Historical placeholder project cleanup

Only the 12 historical project rows explicitly mapped by stable UUID in the cleanup catalog are eligible for cleanup; the seed never discovers candidates with regex or heuristic matching. A local database may contain any subset from zero through 12, while staging must contain all 12.

Cleanup changes only `project.name` and `project.description`. Project audit fields, client and other parent relationships, archive state, memberships, and Worklogs remain unchanged. Old-state drift, cross-organization ownership, an incomplete staging set, or a target-name collision fails closed before persistent writes. Dry-run and standalone verification print cleanup results as aggregate counts only; raw cleanup UUIDs and old placeholder content must not appear in CI or Jira logs. Reruns are idempotent.

Dry-run and apply take an `EXCLUSIVE` lock on `public.project`; standalone verification intentionally takes the same lock for its repeatable-read assertion transaction. The lock still permits ordinary `SELECT` reads (`ACCESS SHARE`), but application `SELECT ... FOR UPDATE` (`ROW SHARE`) and project `INSERT`, `UPDATE`, and `DELETE` statements wait until the seed or verification transaction commits or rolls back. Acquiring `EXCLUSIVE` before seed decisions also serializes a transaction that locked a project row first, avoiding a relation-lock upgrade deadlock between that application transaction and the seed.

## Local runbook

### Prerequisites

- PostgreSQL client `psql 13+` is available on `PATH`.
- The target database has the complete Flyway schema through Flyway V21, including `V21__add_work_unit_entry_type.sql`. Apply all repository migrations with `sbt flywayMigrate` before using the seed.
- The database is a real local PostgreSQL server reached directly over loopback. Do not use a tunnel or proxy: a tunnel can look like loopback while forwarding to a non-local database.

From the repository root, configure the approved local database:

```bash
export DB_URL='jdbc:postgresql://127.0.0.1:5432/epoca'
export DB_USER='postgres'
export DB_PASSWORD='postgres'
```

Preview the exact plan. Dry-run must complete successfully before apply:

```bash
scripts/demo-seed/run.sh local --dry-run
```

> **WARNING: Do not run local apply through a tunnel or proxy.**

```bash
scripts/demo-seed/run.sh local --apply --confirm-local-database epoca
```

Run the independent, read-only verification after apply:

```bash
scripts/demo-seed/run.sh verify local
```

Review the printed identity before every operation. The host must be loopback, the database must be the confirmed local allow-listed name, and the final summary must show the exact dataset counts above. A failed preflight, conflict, or verification rolls back the apply.

## Staging runbook

Staging is a controlled rollout. Follow these steps in order without skipping, combining, or reordering them. Keep the approved connection settings and viewer address in the operator's secret environment; do not paste them into this file, shell history, CI variables, logs, or Jira.

### 1. Capture and review the fingerprint read-only

Using the separately approved staging connection, open a read-only transaction and capture `current_database()`, `(pg_control_system()).system_identifier`, `inet_server_addr()`, `inet_server_port()`, and the active `iteratorstesting` organization identifier. Roll the transaction back. Have the five values independently reviewed against the approved target, then place them in `staging-fingerprint.conf` in a separate reviewed change. Do not run any seed command while that file contains `UNCONFIGURED`.

### 2. Confirm a real login

Log in through the application with the approved existing account. Confirm it is active, nonseed, belongs to `iteratorstesting`, and is a superadmin or has exactly one Owner or Manager role. Set `DEMO_VIEWER_EMAIL` only in the operator's secret shell environment. The viewer guard never changes this account or its credentials.

### 3. Run the staging dry-run

```bash
scripts/demo-seed/run.sh staging --dry-run \
  --confirm-organization iteratorstesting \
  --demo-viewer-email "$DEMO_VIEWER_EMAIL"
```

The runner must match the reviewed fingerprint and viewer before SQL planning begins. Dry-run executes the same preflight and verification as apply but ends with `ROLLBACK`.

### 4. Review the projected counts

Stop unless the plan projects exactly 30 active members with the 2/2/22/4 role distribution, two active superadmins in total, one managed password for every active mock, 10 seed clients, 16 seed projects, 1,200 active Worklogs, 100 active Notes, and 60 archived historical Worklogs. Review every non-zero insert, update, activation, and archive count; unexplained change is a blocker.

### 5. Apply the reviewed plan

Run apply only against the same connection, fingerprint, viewer, seed revision, and plan that were reviewed:

```bash
scripts/demo-seed/run.sh staging --apply \
  --confirm-organization iteratorstesting \
  --demo-viewer-email "$DEMO_VIEWER_EMAIL"
```

### 6. Run standalone verification

After the apply commits, run verification as a separate read-only operation:

```bash
scripts/demo-seed/run.sh verify staging \
  --confirm-organization iteratorstesting \
  --demo-viewer-email "$DEMO_VIEWER_EMAIL"
```

Do not continue until the summary confirms the exact people, role, superadmin, client, project, project-team, activity, and date-range invariants.

### 7. Smoke-test the API and UI

With the same approved real login, exercise authenticated API and UI paths for the organization team, active and archived clients/projects, project teams, calendar, reports, pivot, and export. Confirm that Worklogs and Notes appear across the rolling dates and archived history stays out of active totals. Then use the separately delivered shared demo password to confirm login for active mock accounts from each available organization role.

### 8. Record Jira evidence

Record the rollout timestamp, seed commit SHA, approved target organization identifier, seed date/date range, exact final counts, standalone verification result, and completed API/UI smoke checks in CT-532. Do not include connection settings, system identifier, operator credentials, viewer address, or raw fingerprint values.

Staging checks fail closed: an unconfigured or mismatched fingerprint, missing/invalid viewer, schema gap, ownership collision, or count mismatch stops before commit or rolls the transaction back. Do not take over or manually rewrite conflicting rows to make the seed pass. Preserve the evidence, diagnose the conflict, fix the seed or catalog through review, repeat dry-run, and obtain a new approval before apply.

## Troubleshooting

- `CT-532 requires Flyway V21` means the database schema is incomplete. Apply every migration through V21, then repeat dry-run.
- Identity or confirmation failures mean the selected connection is not the reviewed target. Stop and correct the connection or approval; do not weaken the guards.
- A change-plan or verification conflict means existing state violates the seed's ownership or cardinality contract. Keep the rollback intact and investigate the named invariant.
- The runner accepts only `local` and `staging`; it cannot be used for production. CI uses only disposable `epoca_ct532_case_<pid>_<n>` databases on its PostgreSQL service and never loads staging configuration or credentials.
