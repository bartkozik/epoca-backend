import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { createServer } from 'node:http';
import { gateway } from '../gateway.mjs';
import { route, validateInput, validateReferences, tokenFor, DemoError, uuid } from '../security.mjs';
import { previewResponse } from '../previews.mjs';

const owner = 'a2345678-1234-4234-8234-123456789011';
const member = 'a2345678-1234-4234-8234-123456789012';
const foreign = 'a2345678-1234-4234-8234-123456789013';
const session = { organization_slug: 'demo-team', organization_id: 'a2345678-1234-4234-8234-123456789014', user_id: owner, user_ids: [owner, member], identity_id: 'a2345678-1234-4234-8234-123456789015', created_at: new Date('2026-11-06T10:00:00Z'), expires_at: new Date('2026-11-07T10:00:00Z') };
const prefix = '/api/v1/organizations/demo-team';
const url = path => new URL('https://demo.example' + prefix + path);
const preview = (path, current = session) => {
  const target = url(path);
  const allowed = route('GET', target.pathname, current);
  validateInput(allowed, target, null);
  return previewResponse(current, allowed, target);
};

test('membership commands require exact payloads and one supported role', () => {
  for (const action of ['assign', 'deassign']) {
    const r = route('POST', prefix + '/users/' + action, session);
    const valid = { userId: member, ...(action === 'assign' ? { roles: ['Manager'] } : {}) };
    assert.doesNotThrow(() => validateInput(r, url('/users/' + action), valid));
    for (const body of [null, {}, [], { ...valid, unknown: true }, { ...valid, userId: null }, { ...valid, userId: 'bad' }]) assert.throws(() => validateInput(r, url('/users/' + action), body), { status: 403 });
    assert.throws(() => validateInput(r, url('/users/' + action + '?userId=' + member), valid), { status: 403 });
    for (const method of ['GET', 'PATCH', 'DELETE']) assert.throws(() => route(method, prefix + '/users/' + action, session), { status: 403 });
  }
  const r = route('POST', prefix + '/users/assign', session);
  for (const roles of [null, [], ['Owner', 'Manager'], ['Admin'], 'Manager', [null]]) assert.throws(() => validateInput(r, url('/users/assign'), { userId: member, roles }), { status: 403 });
  for (const role of ['Owner', 'Manager', 'Employee', 'External']) assert.doesNotThrow(() => validateInput(r, url('/users/assign'), { userId: member, roles: [role] }));
});

test('membership writes reject owner case variants, foreign ids and inactive or wrongly typed members', async () => {
  for (const action of ['assign', 'deassign']) {
    const r = route('POST', prefix + '/users/' + action, session);
    const target = url('/users/' + action);
    const noQuery = { query: async () => assert.fail('Invalid session member must be rejected before SQL') };
    for (const userId of [owner, owner.toUpperCase(), foreign, session.organization_id]) await assert.rejects(validateReferences(noQuery, session, r, target, { userId }), { status: 403 });
    let queries = 0;
    const db = { query: async (sql, params) => {
      queries++;
      assert.equal(params[0], session.organization_id);
      if (sql.includes('membership.user_id')) {
        assert.match(sql, /membership.archived_at IS NULL/);
        assert.match(sql, /member.archived_at IS NULL/);
        assert.match(sql, /member.account_type='Human'/);
        assert.equal(params[1], member);
      }
      return { rows: [{ id: member }] };
    } };
    await validateReferences(db, session, r, target, { userId: member.toUpperCase() });
    assert.equal(queries, 2);
    await assert.rejects(validateReferences({ query: async () => ({ rows: [] }) }, session, r, target, { userId: member }), { status: 403 });
  }
});

test('preview contracts paginate and scope fixture identities to the current dataset', () => {
  const saml = preview('/saml/configs');
  assert.equal(saml.organizationId, session.organization_id);
  assert.equal(saml.isEnabled, false);
  assert.equal(saml.idpCert, '');
  assert.equal(saml.validFor, 'PT8H');
  const invitations = preview('/invitations?version=2&pageSize=200&pageNumber=1');
  assert.equal(invitations.featureEnabled, true);
  assert.equal(invitations.count, 2);
  for (const item of invitations.results) {
    assert.ok(uuid.test(item.id));
    assert.match(item.revision, /^(0|[1-9]\d*)$/);
    assert.equal(item.protocolVersion, 2);
    assert.ok(['Pending', 'Expired'].includes(item.status));
    assert.equal(item.delivery.status, 'NotSent');
    assert.equal(item.delivery.lastAttemptAt, null);
    assert.equal(item.createdAt, session.created_at.toISOString());
  }
  assert.equal(preview('/invitations?version=2&pageSize=1&pageNumber=2').results[0].id, invitations.results[1].id);
  assert.deepEqual(preview('/invitations?pageNumber=3').results, []);
  const clients = preview('/oauth-clients');
  const client = clients.results[0];
  assert.equal(clients.count, 1);
  assert.deepEqual(preview('/oauth-clients/' + client.clientId), client);
  assert.deepEqual(preview('/oauth-clients?status=Revoked'), { count: 0, results: [] });
  const audit = preview('/oauth-clients/' + client.clientId + '/audit-events');
  assert.equal(audit.count, 1);
  assert.equal(audit.results[0].actorUserId, owner);
  assert.equal(audit.results[0].outcome, 'Success');
  assert.equal(audit.results[0].eventType, 'Create');
  assert.equal(client.version, 1);
  assert.equal(client.policyVersion, 1);
  assert.equal(client.secretVersion, 1);
  assert.deepEqual(client.projectAccess, { mode: 'AllProjects' });
  assert.equal(client.createdAt, session.created_at.toISOString());
  assert.equal(client.serviceAccount.userId, client.serviceAccount.userId.toLowerCase());
  assert.ok(uuid.test(client.serviceAccount.userId));
  assert.doesNotMatch(JSON.stringify({ clients, audit, invitations }), /"(?:clientSecret|secretHash|accessToken|authorization)"/);
  const reset = { ...session, organization_id: foreign };
  assert.notEqual(preview('/oauth-clients', reset).results[0].clientId, client.clientId);
  assert.notEqual(preview('/invitations', reset).results[0].id, invitations.results[0].id);
  for (const suffix of ['', '/audit-events']) {
    assert.throws(() => preview('/oauth-clients/' + client.clientId + suffix, reset), { status: 403 });
    assert.throws(() => preview('/oauth-clients/' + member + suffix), { status: 403 });
  }
  for (const path of ['/saml/configs?userId=' + owner, '/invitations?version=1', '/invitations?pageSize=201', '/invitations?pageSize=1&pageSize=2', '/oauth-clients?status=Unknown', '/oauth-clients?clientId=' + client.clientId, '/oauth-clients/' + client.clientId + '?pageSize=1']) assert.throws(() => preview(path), { status: 403 });
});

test('authenticated HTTP previews never reach the backend and all external actions remain denied', async () => {
  let forwarded = [];
  const upstream = createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    forwarded.push({ method: req.method, path: req.url, body: JSON.parse(Buffer.concat(chunks).toString()) });
    res.writeHead(204); res.end();
  }).listen(0, '127.0.0.1');
  await once(upstream, 'listening');
  const cookie = 'a'.repeat(43);
  const secret = 'x'.repeat(64);
  let current = session;
  let activeMember = true;
  const db = { query: async () => ({ rows: activeMember ? [{ id: member }] : [] }) };
  const store = { rate: async () => {}, checkCapacity: async () => {}, withSession: async (value, fn) => {
    if (value !== cookie) throw new DemoError(401, 'Expired');
    return fn(db, current);
  } };
  const server = gateway(store, { origin: 'https://demo.example', secret, backend: `http://127.0.0.1:${upstream.address().port}` }).listen(0, '127.0.0.1');
  await once(server, 'listening');
  const base = `http://127.0.0.1:${server.address().port}`;
  const headers = { cookie: '__Host-epoca-demo=' + cookie, authorization: 'Bearer ' + tokenFor(session, secret), origin: 'https://demo.example', 'content-type': 'application/json' };
  const request = (path, method = 'GET', body, customHeaders = headers) => fetch(base + prefix + path, { method, headers: customHeaders, body: body === undefined ? undefined : JSON.stringify(body) });
  try {
    const clientId = preview('/oauth-clients').results[0].clientId;
    for (const path of ['/saml/configs', '/invitations?version=2&pageSize=200', '/oauth-clients', '/oauth-clients/' + clientId, '/oauth-clients/' + clientId + '/audit-events']) {
      assert.equal((await request(path)).status, 200);
      assert.equal((await request(path, 'GET', undefined, {})).status, 401);
      assert.equal((await request(path, 'GET', undefined, { ...headers, authorization: 'Bearer stale' })).status, 401);
    }
    const blockedActions = [['POST', '/saml/configs'], ['POST', '/saml/login'], ['GET', '/saml/auth-check'], ['POST', '/invitations'], ['PATCH', '/invitations/' + member], ['DELETE', '/invitations/' + member], ['POST', '/invitations/' + member + '/resend'], ['POST', '/oauth-clients'], ['PATCH', '/oauth-clients/' + clientId], ['POST', '/oauth-clients/' + clientId + '/rotate-secret'], ['POST', '/oauth-clients/' + clientId + '/revoke']];
    for (const [method, path] of blockedActions) assert.equal((await request(path, method)).status, 403, path);
    assert.equal(forwarded.length, 0);
    for (const action of ['assign', 'deassign']) {
      const body = { userId: member, ...(action === 'assign' ? { roles: ['Manager'] } : {}) };
      assert.equal((await request('/users/' + action, 'POST', body)).status, 204);
      assert.deepEqual(forwarded.at(-1), { method: 'POST', path: prefix + '/users/' + action, body });
      for (const userId of [owner, owner.toUpperCase(), foreign, session.organization_id]) assert.equal((await request('/users/' + action, 'POST', { ...body, userId })).status, 403);
    }
    activeMember = false;
    assert.equal((await request('/users/assign', 'POST', { userId: member, roles: ['Owner'] })).status, 403);
    assert.equal(forwarded.length, 2);
    current = { ...session, organization_id: foreign };
    assert.equal((await request('/oauth-clients')).status, 401);
    const renewed = { ...headers, authorization: 'Bearer ' + tokenFor(current, secret) };
    assert.equal((await request('/oauth-clients/' + clientId, 'GET', undefined, renewed)).status, 403);
    assert.equal((await request('/oauth-clients/' + clientId + '/audit-events', 'GET', undefined, renewed)).status, 403);
    assert.equal((await request('/oauth-clients', 'GET', undefined, renewed)).status, 200);
  } finally { await Promise.all([new Promise(resolve => server.close(resolve)), new Promise(resolve => upstream.close(resolve))]); }
});
