# Disposable OAuth E2E application

`oauthE2e` is a separate sbt application containing fixture control, synthetic clock,
run ownership, and cleanup tooling. The production backend does not depend on it.
Security and concurrency regression specifications remain tests; they were not
removed merely because they originated during an implementation task.

Build with JDK 17 and the approved `EPOCA_RUNTIME_IMAGE`:

```sh
sbt 'oauthE2e/test' 'oauthE2e/Universal/stage' 'oauthE2e/Docker/stage'
```

Start `oauth-e2e/target/universal/stage/bin/epoca-oauth-e2e`. The Docker build context
is `oauth-e2e/target/docker/stage`. Tag this image separately as `epoca-oauth-e2e`;
never substitute it for `epoca-backend` in deployment. CI builds the executable
stage and runs all fixture specifications, without publishing or deploying it.
The default backend entry point remains `pl.iterators.epoca.main.Main`.

`OAuthE2EMain` explicitly loads `oauth-e2e.conf`, then falls back to normal application
configuration. Only this entry point wires fixture time and the loopback controller.
Controller resources are acquired before startup jobs and released by the same
Resource scope if startup fails or the process stops.

## Names and migration

Stop and clean up old disposable runs with their original harness before switching.
There are no legacy aliases: a mixed-version harness fails validation rather than
silently targeting a different database or control endpoint.

| Former name | Current name |
| --- | --- |
| `ct522-oauth-e2e` environment | `oauth-e2e` |
| `ct522-disposable-v1` acknowledgement | `oauth-e2e-disposable-v1` |
| `ct522_oauth_e2e_` database prefix | `oauth_e2e_` |
| `ct522-v1.` control-token prefix | `oauth-e2e-v1.` |
| `/ct522-fixture/v1/runs` control routes | `/oauth-e2e-fixture/v1/runs` |
| `/run/ct522-fixture-supervisor/termination-required.v1` | `/run/oauth-e2e-fixture-supervisor/termination-required.v1` |
| `ct522-fixture-termination-required-v1` marker contract | `oauth-e2e-fixture-termination-required-v1` |
| `ct522-fixture-command-` session marker | `oauth-e2e-fixture-command-` |

All `OAUTH_E2E_FIXTURE_*` environment variable keys remain purpose-based and unchanged.
The deployment tier must still be `disposable`; bind only to `127.0.0.1`, port
19000–19999. Token randomness, canonical encoding, DB isolation, cleanup ownership,
command budgets and fail-closed behavior retain their existing validation/tests.
Mount the supervisor directory for the E2E process user if running a controller.

The remotely published `ai-skills` Epoca API branch at `ba5b005` contains a client
of normal business OAuth APIs, not a fixture controller/harness. DevOps `main` at
`facca37` has retired task-specific fixture pipelines. Neither tracked consumer
requires a control-protocol update. Any unpublished external harness must adopt
the table above and the dedicated E2E executable/image before use. Live external
harness rollout is not implied by this backend MR.
