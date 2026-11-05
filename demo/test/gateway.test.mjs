import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { createServer } from 'node:http';
import { gateway } from '../gateway.mjs';
import { tokenFor, DemoError } from '../security.mjs';

const session = { id: 'test', organization_slug: 'demo-test', organization_id: '12345678-1234-4234-8234-123456789012', user_id: '12345678-1234-4234-8234-123456789013', identity_id: '12345678-1234-4234-8234-123456789014', created_at: new Date(), expires_at: new Date(Date.now() + 86400000) };
test('HTTP gateway requires cookie plus current bearer and strips upstream cookies', async () => {
  let forwarded = null;
  let upstreamMode = 'ok';
  const upstream = createServer((req, res) => { forwarded = req.headers; if (upstreamMode === 'error') { res.writeHead(500); res.end('secret SQL detail'); return; } if (upstreamMode === 'redirect') { res.writeHead(302, { location: 'https://example.invalid' }); res.end(); return; } if (upstreamMode === 'large') { res.end('x'.repeat(2 * 1024 * 1024 + 1)); return; } res.setHeader('set-cookie', 'bad=1'); res.end('{}'); }).listen(0, '127.0.0.1');
  await once(upstream, 'listening');
  const cookie = 'a'.repeat(43);
  const store = {
    pool: { query: async () => ({ rows: [] }) },
    rate: async () => {},
    withSession: async (value, fn) => { if (value !== cookie) throw new DemoError(401, 'Expired'); return fn({ query: async (_sql, params) => ({ rows: (params?.[1] ?? []).map(id => ({ id })) }) }, session); },
    checkCapacity: async () => {}
  };
  const config = { origin: 'https://demo.example', secret: 'x'.repeat(64), backend: `http://127.0.0.1:${upstream.address().port}`, trustProxy: false };
  const server = gateway(store, config).listen(0, '127.0.0.1');
  await once(server, 'listening');
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    const headers = { cookie: '__Host-epoca-demo=' + cookie, authorization: 'Bearer ' + tokenFor(session, config.secret) };
    assert.equal((await fetch(base + '/api/v1/organizations')).status, 401);
    assert.equal((await fetch(base + '/api/v1/organizations', { headers: { cookie: headers.cookie, authorization: 'Bearer stale' } })).status, 401);
    const response = await fetch(base + '/api/v1/organizations', { headers: { ...headers, 'x-forwarded-for': 'attacker', 'x-custom': 'attacker' } });
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('set-cookie'), null);
    assert.equal(forwarded.cookie, undefined);
    assert.equal(forwarded['x-custom'], undefined);
    assert.equal(forwarded['x-forwarded-for'], undefined);
    assert.equal(forwarded.authorization, headers.authorization);
    const key = '12345678-1234-4234-8234-123456789099';
    const mutationHeaders = { ...headers, origin: config.origin, 'content-type': 'application/json', 'idempotency-key': key };
    const workPath = '/api/v1/organizations/demo-test/work-units';
    assert.equal((await fetch(base + workPath, { method: 'POST', headers: mutationHeaders, body: '{}' })).status, 200);
    assert.equal(forwarded['idempotency-key'], key);
    assert.equal((await fetch(base + workPath + '/' + session.user_id, { method: 'PATCH', headers: mutationHeaders, body: '{}' })).status, 200);
    assert.equal(forwarded['idempotency-key'], key);
    assert.equal((await fetch(base + '/api/v1/organizations', { headers: { ...headers, 'idempotency-key': key } })).status, 200);
    assert.equal(forwarded['idempotency-key'], undefined);
    for (const value of ['not-a-uuid', key + ', ' + key]) assert.equal((await fetch(base + workPath, { method: 'POST', headers: { ...mutationHeaders, 'idempotency-key': value }, body: '{}' })).status, 400);

    assert.equal((await fetch(base + '/api/v1/auth/login', { method: 'POST', headers: { ...headers, origin: config.origin } })).status, 403);
    assert.equal((await fetch(base + '/api/demo/reset', { method: 'POST', headers })).status, 403);
    assert.equal((await fetch(base + '/api/demo/session', { headers })).status, 200);
    upstreamMode = 'error';
    const failure = await fetch(base + '/api/v1/organizations', { headers });
    assert.equal(failure.status, 502);
    assert.ok(!(await failure.text()).includes('SQL'));
    upstreamMode = 'redirect';
    assert.equal((await fetch(base + '/api/v1/organizations', { headers })).status, 502);
    upstreamMode = 'large';
    assert.equal((await fetch(base + '/api/v1/organizations', { headers })).status, 413);
    upstreamMode = 'ok';
    assert.equal((await fetch(base + '/api/v1/organizations', { headers: { ...headers, 'sec-fetch-site': 'cross-site' } })).status, 403);
  } finally { await Promise.all([new Promise(resolve => server.close(resolve)), new Promise(resolve => upstream.close(resolve))]); }
});
