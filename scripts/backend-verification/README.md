# Backend verification

Reusable checks for dependency policy, database snapshots, API contracts and JVM images. Names describe the checks rather than the ticket that originally introduced them.

## Fast tooling tests

```sh
bash scripts/backend-verification/tests/run.sh --case fast
```

This is part of `test_backend` CI. It checks naming, dependency policy, database snapshot handling, image metadata validation, runner preflight and runtime-JDBC cleanup using disposable local files and fake external commands. It does not start Docker, access a database, invoke sbt or contact a live application. The remaining real integration gates are explicit:

```sh
bash scripts/backend-verification/tests/run.sh --case contracts
bash scripts/backend-verification/tests/run.sh --case migration-data-drift
```

Contracts need JDK 17 and the pinned oasdiff executable (downloaded and checksum-verified on first use). Migration tests need Docker, sbt and the configured immutable builder image. Running without `--case` includes these heavier gates.

## Build evidence and release checks

```sh
sbt writeBackendDependencyManifest writeBackendToolchainManifest
bash scripts/backend-verification/run.sh checkpoint --focus 'testOnly pl.iterators.epoca.main.ApplicationLoaderOAuthSpec'
bash scripts/backend-verification/run.sh final
```

Evidence is written beneath `target/backend-verification/`. The final gate requires `EPOCA_BUILDER_IMAGE`, `EPOCA_RUNTIME_IMAGE`, `EPOCA_REGRESSION_REPOSITORY`, `EPOCA_REGRESSION_COMMIT_SHA` (exact commit), and `EPOCA_REGRESSION_COMMAND` for the approved cross-stack harness. Shell commands in the latter variable must come from trusted release configuration.

The explicit `verify-runtime-jdbc.sh` gate reads `EPOCA_VERIFY_APPLICATION_IMAGE` and `EPOCA_VERIFY_RUNTIME_JDBC_{URL,USER,PASSWORD,LOGIN_EMAIL,LOGIN_PASSWORD}`. Its URL must target a disposable database such as `epoca_stack_runtime_gate`, through `host.docker.internal` with an explicit port. Pass secrets through the environment; do not store them in this repository. The independent migration harness creates its own `stack_migration_*` databases and owns containers through run and nonce labels.

## Migration from historical names

| Previous name | Current name |
| --- | --- |
| `scripts/ct526/` | `scripts/backend-verification/` |
| `CT526Evidence` | `BackendVerification` |
| `ct526EvidenceDirectory` | `backendVerificationDirectory` |
| `ct526WriteDependencyManifest` | `writeBackendDependencyManifest` |
| `ct526WriteToolchainManifest` | `writeBackendToolchainManifest` |
| `target/ct526-evidence/` | `target/backend-verification/` |
| `CT526_*` environment variables | `EPOCA_VERIFY_*` |
| `CT530_REPOSITORY`, `CT530_COMMIT_SHA`, `CT530_HARNESS_COMMAND` | `EPOCA_REGRESSION_REPOSITORY`, `EPOCA_REGRESSION_COMMIT_SHA`, `EPOCA_REGRESSION_COMMAND` |

Update local/manual callers together; old aliases are not retained. The existing `scripts/ci/verify-jvm-images.sh` entrypoint remains stable. Finish any old migration-harness invocation with its original version before switching, so it retains ownership of its resources.

`accepted/` contains byte-preserved historical evidence, including diagnostic compiler snapshots. These are data, not separately executed test suites. They have not been regenerated or claimed to describe the latest application contract. Updating an accepted baseline remains a reviewed release decision; test cleanup must not silently accept a changed schema or contract. Historical documents retain original commands and hashes; use this page for current command names.

The naming check covers executable tooling in this directory, the build helper and the CI wrapper. Historical Markdown and accepted evidence are excluded. Other test/runtime areas are outside this first cleanup slice and are tracked in CT-569.
