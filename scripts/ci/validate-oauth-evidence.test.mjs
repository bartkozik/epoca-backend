import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import {
  EVIDENCE_COMMAND,
  OAUTH_E2E_SCENARIO_IDS,
  readAndValidateOAuthEvidence,
  scanEvidenceOutputs,
  validateOAuthEvidence,
} from './validate-oauth-evidence.mjs';

const sha = '0123456789abcdef0123456789abcdef01234567';
const fixtureScenarioIds = ['OAUTH-E2E-001', 'OAUTH-E2E-033'];
const combinedScenarioIds = [
  ...Array.from({ length: 22 }, (_, index) => `OAUTH-E2E-${String(index + 1).padStart(3, '0')}`),
  ...Array.from({ length: 4 }, (_, index) => `OAUTH-E2E-${String(index + 30).padStart(3, '0')}`),
  ...Array.from({ length: 8 }, (_, index) => `CT-537-E2E-${String(index + 1).padStart(3, '0')}`),
];

const evidence = (overrides = {}) => ({
  schemaVersion: 1,
  taskId: 'CT-520',
  commitSha: sha,
  startedAt: '2026-08-12T10:00:00Z',
  finishedAt: '2026-08-12T10:10:00Z',
  command: EVIDENCE_COMMAND,
  scenarioIds: fixtureScenarioIds,
  postgresVersion: '13.4',
  backendInstances: 2,
  outcome: 'passed',
  ...overrides,
});

const options = {
  expectedCommitSha: sha,
  expectedScenarioIds: fixtureScenarioIds,
};

test('accepts the closed two-scenario unit fixture', () => {
  assert.deepEqual(validateOAuthEvidence(evidence(), options), evidence());
});

test('rejects unknown, missing, and inherited evidence fields', () => {
  assert.throws(() => validateOAuthEvidence(evidence({ extra: true }), options), /unknown evidence field: extra/);
  const missing = evidence();
  delete missing.outcome;
  assert.throws(() => validateOAuthEvidence(missing, options), /missing evidence field: outcome/);
  assert.throws(() => validateOAuthEvidence(Object.create({ schemaVersion: 1 }), options), /plain JSON object/);
});

test('requires the exact task, schema, command, PostgreSQL version, instance count, and outcome', () => {
  assert.throws(() => validateOAuthEvidence(evidence({ schemaVersion: 2 }), options), /schemaVersion/);
  assert.throws(() => validateOAuthEvidence(evidence({ taskId: 'CT-521' }), options), /taskId/);
  assert.throws(() => validateOAuthEvidence(evidence({ command: `${EVIDENCE_COMMAND} -- -v` }), options), /command/);
  assert.throws(() => validateOAuthEvidence(evidence({ postgresVersion: '13.5' }), options), /postgresVersion/);
  assert.throws(() => validateOAuthEvidence(evidence({ backendInstances: 1 }), options), /backendInstances/);
  assert.throws(() => validateOAuthEvidence(evidence({ outcome: 'failed' }), options), /outcome/);
});

test('binds evidence to one exact lowercase commit SHA', () => {
  assert.throws(() => validateOAuthEvidence(evidence({ commitSha: sha.toUpperCase() }), options), /commitSha/);
  assert.throws(() => validateOAuthEvidence(evidence(), { ...options, expectedCommitSha: 'f'.repeat(40) }), /expected commit SHA/);
  assert.throws(() => validateOAuthEvidence(evidence(), { ...options, expectedCommitSha: undefined }), /expectedCommitSha/);
});

test('requires strict UTC timestamps in chronological order', () => {
  assert.throws(() => validateOAuthEvidence(evidence({ startedAt: '2026-08-12T10:00:00+00:00' }), options), /startedAt/);
  assert.throws(() => validateOAuthEvidence(evidence({ startedAt: '2026-02-30T10:00:00Z' }), options), /startedAt/);
  assert.throws(() => validateOAuthEvidence(evidence({ startedAt: '2026-08-12T10:00:00.123456Z' }), options), /startedAt/);
  assert.throws(() => validateOAuthEvidence(evidence({ finishedAt: '2026-08-12T09:59:59Z' }), options), /timestamp order/);
});

test('requires the exact unique scenario inventory in numeric order', () => {
  assert.throws(() => validateOAuthEvidence(evidence({ scenarioIds: fixtureScenarioIds.slice(0, 1) }), options), /scenarioIds/);
  assert.throws(() => validateOAuthEvidence(evidence({ scenarioIds: [...fixtureScenarioIds].reverse() }), options), /scenarioIds/);
  assert.throws(() => validateOAuthEvidence(evidence({ scenarioIds: [fixtureScenarioIds[0], fixtureScenarioIds[0]] }), options), /scenarioIds/);
});

test('requires every ordered CT-537 lifecycle scenario in the default combined inventory', () => {
  const defaultOptions = { expectedCommitSha: sha };
  const combinedEvidence = evidence({ scenarioIds: combinedScenarioIds });
  assert.deepEqual(OAUTH_E2E_SCENARIO_IDS, combinedScenarioIds);
  assert.deepEqual(validateOAuthEvidence(combinedEvidence, defaultOptions), combinedEvidence);

  const firstCt537Index = combinedScenarioIds.indexOf('CT-537-E2E-001');
  const missing = combinedScenarioIds.filter((id) => id !== 'CT-537-E2E-004');
  const reordered = [...combinedScenarioIds];
  [reordered[firstCt537Index], reordered[firstCt537Index + 1]] = [reordered[firstCt537Index + 1], reordered[firstCt537Index]];
  const duplicated = [...combinedScenarioIds];
  duplicated[duplicated.length - 1] = 'CT-537-E2E-007';

  assert.throws(() => validateOAuthEvidence(evidence({ scenarioIds: missing }), defaultOptions), /scenarioIds/);
  assert.throws(() => validateOAuthEvidence(evidence({ scenarioIds: reordered }), defaultOptions), /scenarioIds/);
  assert.throws(() => validateOAuthEvidence(evidence({ scenarioIds: duplicated }), defaultOptions), /scenarioIds/);
});

test('reads only a bounded regular non-symlink JSON artifact', () => {
  const directory = mkdtempSync(join(tmpdir(), 'ct520-evidence-'));
  try {
    const artifact = join(directory, 'oauth-e2e-evidence.json');
    writeFileSync(artifact, JSON.stringify(evidence()));
    assert.deepEqual(readAndValidateOAuthEvidence(artifact, options), evidence());

    const link = join(directory, 'evidence-link.json');
    symlinkSync(artifact, link);
    assert.throws(() => readAndValidateOAuthEvidence(link, options), /regular non-symlink/);

    const oversized = join(directory, 'oversized.json');
    writeFileSync(oversized, ' '.repeat(16 * 1024 + 1));
    assert.throws(() => readAndValidateOAuthEvidence(oversized, options), /size limit/);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('rejects credential, token, hash, and signing-key material in scanned outputs', () => {
  const forbidden = [
    'epoca_oauth_secret_v1_leaked',
    'Authorization: Basic dXNlcjpzZWNyZXQ=',
    '{"Authorization":"Basic dXNlcjpzZWNyZXQ="}',
    'request failed (Basic dXNlcjpzZWNyZXQ=).',
    'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJsZWFrIn0.c2lnbmF0dXJlMTIzNDU2',
    '$2a$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNO1234567890',
    'ct520-e2e-signing-key-sentinel',
  ];

  for (const value of forbidden) {
    assert.throws(() => scanEvidenceOutputs([{ label: 'test output', text: value }]), /forbidden secret material/);
  }
  assert.doesNotThrow(() => scanEvidenceOutputs([{ label: 'test output', text: 'OAUTH-E2E-001 passed; Authorization header redacted' }]));
  assert.doesNotThrow(() => scanEvidenceOutputs([{ label: 'test output', text: 'invalid clients share the same redacted Basic challenge' }]));
});

test('wires the dedicated OAuth E2E evidence gate into GitLab CI', () => {
  const ci = readFileSync(new URL('../../.gitlab-ci.yml', import.meta.url), 'utf8');
  const block = ci.match(/^oauth_e2e:\n(?:(?:  |    ).*\n|\n)*/m)?.[0];
  assert.ok(block, 'oauth_e2e job must exist');
  assert.match(block, /image: iterators\/docker-git-sbt:17\.0\.5_1\.8\.2_2\.13\.10/);
  assert.match(block, /- apk add --no-cache nodejs=18\.20\.1-r0/);
  assert.match(block, /- node --test scripts\/ci\/validate-oauth-evidence\.test\.mjs/);
  assert.match(block, /- sbt "testOnly pl\.iterators\.epoca\.auth\.OAuthClientCredentialsE2ESpec" > oauth-e2e\.log 2>&1/);
  assert.doesNotMatch(block, /\btee\b/);
  assert.match(
    block,
    /- node scripts\/ci\/validate-oauth-evidence\.mjs --evidence oauth-e2e-evidence\.json --expected-sha "\$CI_COMMIT_SHA" --scan oauth-e2e\.log/,
  );
  assert.match(block, /when: on_success/);
  assert.match(block, /expire_in: 7 days/);
  assert.match(block, /- oauth-e2e-evidence\.json/);
});
