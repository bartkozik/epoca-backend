import test from 'node:test';
import assert from 'node:assert/strict';
import { route, checkOrigin, validateInput, tokenFor, cookieValue, equalToken } from '../security.mjs';

const session = { organization_slug: 'demo-random', organization_id: '12345678-1234-4234-8234-123456789012', user_id: '12345678-1234-4234-8234-123456789013', identity_id: '12345678-1234-4234-8234-123456789014', created_at: new Date('2026-11-05T10:00:00Z'), expires_at: new Date('2026-11-06T10:00:00Z') };
const base = '/api/v1/organizations/demo-random';
test('exact allowlist confines organization and global user reads', () => {
  assert.equal(route('GET', base + '/projects', session).resource, 'project');
  assert.equal(route('GET', '/api/v1/users/' + session.user_id, session).resource, 'user');
  for (const [method, path] of [['POST', '/api/v1/organizations'], ['GET', base + '/invitations'], ['POST', base + '/exports'], ['GET', '/api/v1/organizations/foreign/projects'], ['GET', base + '/projects/../users'], ['GET', base + '/projects%2f'], ['DELETE', base + '/clients/' + session.user_id], ['POST', '/api/v1/auth/refresh']]) {
    assert.throws(() => route(method, path, session), { status: 403 });
  }
});
test('browser mutations require exact configured origin', () => {
  checkOrigin('POST', { origin: 'https://demo.example' }, 'https://demo.example');
  for (const origin of [undefined, 'null', 'https://demo.example.evil', 'http://demo.example']) assert.throws(() => checkOrigin('POST', { origin }, 'https://demo.example'), { status: 403 });
  assert.throws(() => checkOrigin('GET', { 'sec-fetch-site': 'cross-site' }, 'https://demo.example'), { status: 403 });
});
test('bounded inputs reject remote media, unknown fields and giant ranges', () => {
  const client = route('POST', base + '/clients', session);
  assert.throws(() => validateInput(client, new URL('https://demo.example' + base + '/clients'), { name: 'A', logo: 'https://evil.example/a' }), { status: 403 });
  assert.throws(() => validateInput(client, new URL('https://demo.example' + base + '/clients'), { organizationId: session.organization_id }), { status: 403 });
  const work = route('GET', base + '/work-units', session);
  assert.throws(() => validateInput(work, new URL('https://demo.example' + base + '/work-units?dateFrom=2000-01-01&dateTo=2026-01-01'), null), { status: 403 });
  assert.throws(() => validateInput(work, new URL('https://demo.example' + base + '/work-units?pageSize=9999'), null), { status: 403 });
});
test('JWT matches human AuthContext and fixed session expiration', () => {
  const token = tokenFor(session, 'a'.repeat(64));
  const payload = JSON.parse(Buffer.from(token.split('.')[1], 'base64url'));
  assert.equal(payload.id, session.user_id);
  assert.equal(payload.isSuperAdmin, false);
  assert.deepEqual(payload.roles, [{ organizationId: session.organization_id, roles: ['Owner'] }]);
  assert.equal(payload.exp, session.expires_at.getTime() / 1000);
  assert.equal(payload.identities[0].type, 'MailAddress');
  assert.equal(token, tokenFor(session, 'a'.repeat(64)));
});
test('cookie parsing rejects ambiguous and malformed tokens', () => {
  const value = 'a'.repeat(43);
  assert.equal(cookieValue('__Host-epoca-demo=' + value), value);
  assert.equal(cookieValue('__Host-epoca-demo=' + value + '; __Host-epoca-demo=' + value), null);
  assert.equal(cookieValue('__Host-epoca-demo=bad'), null);
});
test('legacy reports and archived member reads remain available', () => {
  const report = route('POST', base + '/pivot-tables/work-units', session);
  assert.doesNotThrow(() => validateInput(report, new URL('https://demo.example' + base + '/pivot-tables/work-units'), { keys: ['Project'], values: ['DurationSum'] }));
  assert.doesNotThrow(() => validateInput(route('GET', base + '/users', session), new URL('https://demo.example' + base + '/users?includeArchivedMembers=true'), null));
});
test('order records and typed identifiers cannot bypass demo bounds', () => {
  const order = route('PUT', base + '/work-units/order', session);
  assert.throws(() => validateInput(order, new URL('https://demo.example' + base + '/work-units/order'), { date: '1900-01-01', workUnitIds: [] }), { status: 403 });
  const project = route('POST', base + '/projects', session);
  assert.throws(() => validateInput(project, new URL('https://demo.example' + base + '/projects'), { clientId: 'bad' }), { status: 403 });
});

test('bearer comparison rejects non-ASCII without throwing', () => {
  assert.equal(equalToken('é', 'a'), false);
});
test('idempotency replay remains available at capacity while new claims are bounded', async () => {
  const { Store } = await import('../store.mjs');
  const store = new Store({}, { secret: 'test' });
  const key = '12345678-1234-4234-8234-123456789099';
  const r = route('POST', base + '/work-units', session);
  const full = { query: async sql => sql.includes('idempotency_key=') ? { rowCount: 0, rows: [] } : { rowCount: 1, rows: [{ n: 2000 }] } };
  await assert.rejects(store.checkCapacity(full, session, { ...r, method: 'PATCH', collection: false }, key), { status: 403 });
  const replay = { query: async sql => sql.includes('idempotency_key=') ? { rowCount: 1, rows: [{ claim_id: key }] } : { rowCount: 1, rows: [{ n: 2000 }] } };
  await assert.doesNotReject(store.checkCapacity(replay, session, r, key));
});
test('sessions inside the frontend refresh window require renewal', async () => {
  const { Store } = await import('../store.mjs');
  const store = new Store({}, { secret: 'test' });
  const nearExpiry = { ...session, expires_at: new Date(Date.now() + 30000), request_window: new Date(), request_count: 0 };
  store.transaction = async fn => fn({ query: async () => ({ rows: [nearExpiry] }) });
  await assert.rejects(store.withSession('a'.repeat(43), async () => 'must not resume'), { status: 401 });
});
