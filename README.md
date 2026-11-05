# EPOCA backend

The real Scala backend of EPOCA, a commercial time-tracking and reporting application. This sanitized source snapshot is paired with `epoca-frontend`. It retains the API, domain services, PostgreSQL persistence, migrations and tests of the integrated application.

Related repository: [epoca-frontend](https://github.com/bartkozik/epoca-frontend).

## My contribution

I worked on EPOCA through my contract with Iterators, primarily as its main frontend developer for approximately three years. During the last year I also contributed to the existing Scala backend through AI-assisted development. This repository includes the team's architecture and implementation; it is not a claim that I independently designed or wrote the entire backend.

## Application and architecture

The service manages organizations, users, memberships, clients, projects and recorded work. It provides pivot reporting, spreadsheet exports, worklog corrections/history, JWT/session handling, SAML, invitations and OAuth integration clients. Optional integrations cover mail, Firebase authentication, object storage and workspace avatars.

`src/main/scala/pl/iterators/epoca/main` owns process startup and module composition. Feature modules use `routers`, `services`, `domain` and `repositories` boundaries; Slick tables live alongside their repositories. Tests mirror these boundaries under `src/test/scala`. `oauth-e2e` contains the controlled OAuth test server. Both database migration lineages are retained in `src/main/resources/db/migration` and `src/main/resources/db/configured`.

The integrated snapshot removes the parked billing-configuration and invitation-cooperation features. Their historical schema migrations remain in sequence; they are not advertised as available functionality.

## Stack

Scala **2.13.18**, JDK **17**, sbt **1.12.14**, Apache Pekko HTTP **1.4.0** and Pekko Streams **1.7.0**, Cats Effect, PostgreSQL, Slick **3.6.1**, Flyway **12**, Circe, Kebs, JWT, SAML and Quartz. Tests use Specs2, Mockito and ScalaCheck; Baklava generates API documentation. The application previously used Akka; this snapshot contains the integrated Pekko migration.

## Local setup

Use JDK 17, the sbt version pinned in `project/build.properties`, and a dedicated local PostgreSQL database. Create two separate empty databases named `epoca_local` and `epoca_test` using your own local PostgreSQL installation. Do not point these commands at a shared database: migrations change its schema.

Set the application and test database variables in your shell. Supply your own local database credentials and generate a fresh JWT signing secret:

```sh
export DB_URL='jdbc:postgresql://127.0.0.1:5432/epoca_local'
export DB_USER='your_local_database_user'
export DB_PASSWORD='your_local_database_password'
export TEST_DB_URL='jdbc:postgresql://127.0.0.1:5432/epoca_test'
export TEST_DB_USER="$DB_USER"
export TEST_DB_PASSWORD="$DB_PASSWORD"
export JWT_SECRET="$(openssl rand -hex 32)"
export APP_FE_URL='http://127.0.0.1:5173/'
export KAMON_ENABLED='false'
sbt flywayMigrate
DB_URL="$TEST_DB_URL" DB_USER="$TEST_DB_USER" DB_PASSWORD="$TEST_DB_PASSWORD" sbt flywayMigrate
sbt run
```

The API listens on port 9000 by default, under `/api/v1`. `GET /api/v1/health-check` is a public liveness check. Start the paired frontend separately. The ordinary authentication and authorization flows are preserved; no bypass or automatic administrator account is added.

Account provisioning and optional services require explicit configuration. The application's `USER_CREATE_ADMIN_SECRET_KEY` option supports guarded initial administrator creation; keep that value outside Git and disable it after local provisioning. Mail/invitations, SAML, Firebase and S3 need your own service configuration. None of the original organization's credentials or production/staging setup is provided. The retained local-development and seed helpers are application source, but their full environment workflow has not been certified by this export; do not run a reset/seed command against an existing database.

## Verify

```sh
sbt compile Test/compile
sbt test
sbt scalafmtCheckAll scalafmtSbtCheck
sbt baklavaGenerate
```

Validation of this snapshot: production sources and all test sources compiled; 33 selected examples for pivot processing, query JSON and signed SAML responses passed. Full test compilation required `sbt -J-Xmx8G compile Test/compile` on the validation machine. The complete PostgreSQL-backed suite and external integrations were not run.

Repository and service specifications need the separately migrated `epoca_test` database and the `TEST_DB_*` variables. The test database must never be the application database: tests may truncate or reset their data. Domain and cryptographic tests can be selected independently. Tests for signed SAML responses use a fresh, explicitly synthetic local certificate/key pair with no external identity provider or account.

Docker packaging requires an explicitly supplied runtime base image; private delivery pipelines and infrastructure are excluded. Compilation and tests do not establish that production deployment or external integrations are configured.

## Sanitization and attribution

Original Git history, private CI/deployment configuration, environment values and internal task/operational notes are excluded. Non-public endpoint and identity literals were anonymized; the SAML test certificate and key were regenerated for this snapshot. Application modules, route behavior, both migration lineages and the team's tests are retained.

Original source namespaces and attribution remain. In particular, `utils/db/DBIOInstances.scala` retains the Risk Management Solutions copyright and upstream BSD-style license reference. No new project-wide license is added. This source snapshot does not include the original production environment, real accounts or client data.

## Public demo

The `demo/` gateway provisions temporary organizations with fictional people, clients, projects and time entries. It validates a server-side guest session before forwarding requests to the Scala API. Reset replaces the organization and credentials; the original 24-hour expiration remains unchanged. Account registration, SSO, invitations, uploads and external integrations are unavailable through the demo gateway.

The gateway and API share a dedicated PostgreSQL database and a newly generated signing key. Neither the database nor the API is publicly exposed. Runtime configuration and deployment are maintained in [portfolio-infra](https://github.com/bartkozik/portfolio-infra).

```sh
cd demo
npm ci
npm test
```

Integration tests additionally require `TEST_DATABASE_URL`, `TEST_BACKEND_URL` and `JWT_SECRET` for a disposable, fully migrated database and matching Scala runtime. GitHub Actions runs these checks before publishing immutable API and gateway images.
