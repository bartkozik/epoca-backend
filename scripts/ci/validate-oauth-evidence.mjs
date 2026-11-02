import { Buffer } from 'node:buffer';
import { lstatSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const EVIDENCE_COMMAND = 'sbt "testOnly pl.iterators.epoca.auth.OAuthClientCredentialsE2ESpec"';
export const CT520_SCENARIO_IDS = [
  ...Array.from({ length: 22 }, (_, index) => `OAUTH-E2E-${String(index + 1).padStart(3, '0')}`),
  ...Array.from({ length: 4 }, (_, index) => `OAUTH-E2E-${String(index + 30).padStart(3, '0')}`),
];
export const CT537_LIFECYCLE_SCENARIO_IDS = Array.from(
  { length: 8 },
  (_, index) => `CT-537-E2E-${String(index + 1).padStart(3, '0')}`,
);
export const OAUTH_E2E_SCENARIO_IDS = [...CT520_SCENARIO_IDS, ...CT537_LIFECYCLE_SCENARIO_IDS];

const EVIDENCE_FIELDS = [
  'schemaVersion',
  'taskId',
  'commitSha',
  'startedAt',
  'finishedAt',
  'command',
  'scenarioIds',
  'postgresVersion',
  'backendInstances',
  'outcome',
];
const COMMIT_SHA = /^[0-9a-f]{40}$/;
const UTC_TIMESTAMP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/;
const MAX_EVIDENCE_BYTES = 16 * 1024;
const MAX_SCAN_BYTES = 8 * 1024 * 1024;
const FORBIDDEN_OUTPUT_PATTERNS = [
  /epoca_oauth_secret_v1_[A-Za-z0-9_-]+/i,
  /eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}/,
  /\$2[aby]\$\d{2}\$[./A-Za-z0-9]{20,}/,
  /ct520-e2e-signing-key-sentinel/i,
];

const containsBasicCredential = (text) => {
  const candidates = text.matchAll(/\bbasic\s+([A-Za-z0-9+/]+={0,2})(?![A-Za-z0-9+/=])/gi);
  for (const [, encoded] of candidates) {
    if (encoded.length < 8 || encoded.length % 4 === 1) continue;
    const decoded = Buffer.from(encoded, 'base64');
    const normalized = encoded.replace(/=+$/, '');
    const canonical = decoded.toString('base64').replace(/=+$/, '');
    if (canonical === normalized && decoded.includes(':'.charCodeAt(0))) return true;
  }
  return false;
};

const fail = (message) => {
  throw new Error(message);
};

const assertExactValue = (actual, expected, field) => {
  if (actual !== expected) fail(`${field} must equal ${JSON.stringify(expected)}`);
};

const assertUtcTimestamp = (value, field) => {
  const parsed = typeof value === 'string' && UTC_TIMESTAMP.test(value) ? new Date(value) : undefined;
  const canonicalInput = typeof value === 'string' && value.endsWith('Z') && !value.includes('.') ? value.replace(/Z$/, '.000Z') : value;
  if (!parsed || Number.isNaN(parsed.getTime()) || parsed.toISOString() !== canonicalInput) {
    fail(`${field} must be a strict UTC timestamp`);
  }
};

const assertExactArray = (actual, expected, field) => {
  if (!Array.isArray(actual) || actual.length !== expected.length || actual.some((value, index) => value !== expected[index])) {
    fail(`${field} must equal the exact ordered inventory`);
  }
  if (new Set(actual).size !== actual.length) fail(`${field} must not contain duplicates`);
};

export const validateOAuthEvidence = (
  evidence,
  { expectedCommitSha, expectedScenarioIds = OAUTH_E2E_SCENARIO_IDS } = {},
) => {
  if (evidence === null || typeof evidence !== 'object' || Array.isArray(evidence) || Object.getPrototypeOf(evidence) !== Object.prototype) {
    fail('evidence must be a plain JSON object');
  }

  const fields = Object.keys(evidence);
  const unknown = fields.filter((field) => !EVIDENCE_FIELDS.includes(field));
  if (unknown.length > 0) fail(`unknown evidence field: ${unknown[0]}`);
  const missing = EVIDENCE_FIELDS.filter((field) => !Object.hasOwn(evidence, field));
  if (missing.length > 0) fail(`missing evidence field: ${missing[0]}`);

  assertExactValue(evidence.schemaVersion, 1, 'schemaVersion');
  assertExactValue(evidence.taskId, 'CT-520', 'taskId');
  if (typeof evidence.commitSha !== 'string' || !COMMIT_SHA.test(evidence.commitSha)) fail('commitSha must be a lowercase 40-character SHA');
  if (typeof expectedCommitSha !== 'string' || !COMMIT_SHA.test(expectedCommitSha)) fail('expectedCommitSha must be a lowercase 40-character SHA');
  if (evidence.commitSha !== expectedCommitSha) fail('commitSha must match the expected commit SHA');

  assertUtcTimestamp(evidence.startedAt, 'startedAt');
  assertUtcTimestamp(evidence.finishedAt, 'finishedAt');
  if (Date.parse(evidence.finishedAt) < Date.parse(evidence.startedAt)) fail('timestamp order must be startedAt then finishedAt');

  assertExactValue(evidence.command, EVIDENCE_COMMAND, 'command');
  assertExactArray(evidence.scenarioIds, expectedScenarioIds, 'scenarioIds');
  assertExactValue(evidence.postgresVersion, '13.4', 'postgresVersion');
  assertExactValue(evidence.backendInstances, 2, 'backendInstances');
  assertExactValue(evidence.outcome, 'passed', 'outcome');
  scanEvidenceOutputs([{ label: 'evidence', text: JSON.stringify(evidence) }]);

  return evidence;
};

const readBoundedRegularFile = (path, maxBytes) => {
  const stat = lstatSync(path);
  if (stat.isSymbolicLink() || !stat.isFile()) fail(`${path} must be a regular non-symlink file`);
  if (stat.size > maxBytes) fail(`${path} exceeds the size limit`);
  return readFileSync(path, 'utf8');
};

export const readAndValidateOAuthEvidence = (path, options) => {
  const text = readBoundedRegularFile(path, MAX_EVIDENCE_BYTES);
  let evidence;
  try {
    evidence = JSON.parse(text);
  } catch {
    fail(`${path} must contain valid JSON`);
  }
  return validateOAuthEvidence(evidence, options);
};

export const scanEvidenceOutputs = (outputs) => {
  for (const { label, text } of outputs) {
    if (typeof label !== 'string' || typeof text !== 'string') fail('scan inputs must contain string labels and text');
    if (FORBIDDEN_OUTPUT_PATTERNS.some((pattern) => pattern.test(text)) || containsBasicCredential(text)) {
      fail(`${label} contains forbidden secret material`);
    }
  }
};

const parseArguments = (argv) => {
  const parsed = { scanPaths: [] };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    const value = argv[index + 1];
    if (argument === '--evidence' && value) parsed.evidencePath = value;
    else if (argument === '--expected-sha' && value) parsed.expectedCommitSha = value;
    else if (argument === '--scan' && value) parsed.scanPaths.push(value);
    else fail(`unknown or incomplete argument: ${argument}`);
    index += 1;
  }
  if (!parsed.evidencePath) fail('--evidence is required');
  if (!parsed.expectedCommitSha) fail('--expected-sha is required');
  return parsed;
};

const main = () => {
  const { evidencePath, expectedCommitSha, scanPaths } = parseArguments(process.argv.slice(2));
  const evidence = readAndValidateOAuthEvidence(evidencePath, { expectedCommitSha });
  const outputs = scanPaths.map((path) => ({ label: path, text: readBoundedRegularFile(path, MAX_SCAN_BYTES) }));
  scanEvidenceOutputs(outputs);
  process.stdout.write(`Validated combined CT-520/CT-537 OAuth evidence for ${evidence.commitSha}.\n`);
};

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  try {
    main();
  } catch (error) {
    process.stderr.write(`OAuth evidence validation failed: ${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = 1;
  }
}
