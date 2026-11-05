import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { randomUUID } from 'node:crypto';
import pg from 'pg';
import { Store } from '../store.mjs';
import { gateway } from '../gateway.mjs';

const enabled = Boolean(process.env.TEST_DATABASE_URL && process.env.TEST_BACKEND_URL && process.env.JWT_SECRET);
test('real Scala backend supports isolated demo CRUD, reports, reset and expiry', { skip: !enabled }, async () => {
  const pool = new pg.Pool({ connectionString: process.env.TEST_DATABASE_URL, max: 4, connectionTimeoutMillis: 5000 });
  const secret = process.env.JWT_SECRET;
  const store = new Store(pool, { secret, maxSessions: 100 });
  const origin = 'https://demo.example';
  let server;
  let base;
  let failure;
  const sessions = [];
  async function request(path, method = 'GET', session, body, extraHeaders = {}) {
    const response = await fetch(base + path, { method, headers: { origin, ...extraHeaders, ...(session ? { cookie: session.cookie, authorization: 'Bearer ' + session.accessToken } : {}), ...(body ? { 'content-type': 'application/json' } : {}) }, body: body ? JSON.stringify(body) : undefined });
    const text = await response.text();
    let data;
    try { data = text ? JSON.parse(text) : null; } catch { data = text; }
    return { status: response.status, data, cookie: response.headers.get('set-cookie')?.split(';')[0] };
  }
  async function create() {
    const response = await request('/api/demo/session', 'POST');
    assert.equal(response.status, 200, JSON.stringify(response.data));
    const session = { ...response.data, cookie: response.cookie };
    sessions.push(session);
    return session;
  }
  try {
    await store.init();
    server = gateway(store, { origin, secret, backend: process.env.TEST_BACKEND_URL, trustProxy: false }).listen(0, '127.0.0.1');
    await once(server, 'listening');
    base = `http://127.0.0.1:${server.address().port}`;
    assert.equal((await request('/health')).status, 200);
    const first = await create();
    const second = await create();
    assert.notEqual(first.organizationSlug, second.organizationSlug);
    const prefix = '/api/v1/organizations/' + first.organizationSlug;
    const organizations = await request('/api/v1/organizations', 'GET', first);
    assert.equal(organizations.status, 200, JSON.stringify(organizations.data));
    assert.equal(organizations.data.results.length, 1);
    const identity = JSON.parse(Buffer.from(first.accessToken.split('.')[1], 'base64url'));
    assert.equal((await request('/api/v1/users/' + identity.id, 'GET', first)).status, 200);
    const users = await request(prefix + '/users', 'GET', first);
    assert.equal(users.status, 200, JSON.stringify(users.data));
    assert.equal(users.data.length, 5);
    const clients = await request(prefix + '/clients', 'GET', first);
    assert.equal(clients.status, 200, JSON.stringify(clients.data));
    assert.equal(clients.data.results.length, 3);
    const client = await request(prefix + '/clients', 'POST', first, { name: 'New fictional client', color: '#123456', logo: '', texture: '', type: 'External' });
    assert.equal(client.status, 201, JSON.stringify(client.data));
    const project = await request(prefix + '/projects', 'POST', first, { name: 'Gateway smoke project', description: 'Fictional smoke data', clientId: client.data.id, ownerIds: [identity.id], memberIds: [] });
    assert.equal(project.status, 201, JSON.stringify(project.data));
    const day = new Date().toISOString().slice(0, 10);
    const createBody = { projectId: project.data.id, date: day, duration: 'PT1H', description: 'Explore the real backend', isDraft: false, entryType: 'Worklog' };
    const createHeaders = { 'Idempotency-Key': randomUUID() };
    const work = await request(prefix + '/work-units', 'POST', first, createBody, createHeaders);
    assert.equal(work.status, 201, JSON.stringify(work.data));
    const replay = await request(prefix + '/work-units', 'POST', first, createBody, createHeaders);
    assert.equal(replay.status, 201, JSON.stringify(replay.data));
    assert.deepEqual(replay.data, work.data);
    const list = await request(prefix + '/work-units?dateFrom=' + day + '&dateTo=' + day + '&projectId=' + project.data.id, 'GET', first);
    assert.equal(list.status, 200, JSON.stringify(list.data));
    assert.equal(list.data.workUnits.results.length, 1);
    const report = await request(prefix + '/pivot-tables/work-units/query?dateFrom=' + day + '&dateTo=' + day, 'POST', first, { rows: ['ProjectKey'], columns: [], values: ['DurationSum'], filters: [], options: { rowSubtotals: false, columnSubtotals: false, grandTotals: true } });
    assert.equal(report.status, 200, JSON.stringify(report.data));
    assert.ok(report.data.cells.length > 0);
    assert.equal((await request('/api/v1/organizations/' + second.organizationSlug + '/projects/' + project.data.id, 'GET', second)).status, 403);
    assert.equal((await request(prefix + '/projects', 'POST', second, { clientId: client.data.id })).status, 403);
    const updateHeaders = { 'Idempotency-Key': randomUUID() };
    const updateBody = { description: 'Updated smoke entry', shouldFinalize: false };
    const updated = await request(prefix + '/work-units/' + work.data.id, 'PATCH', first, updateBody, updateHeaders);
    assert.equal(updated.status, 200, JSON.stringify(updated.data));
    const updateReplay = await request(prefix + '/work-units/' + work.data.id, 'PATCH', first, updateBody, updateHeaders);
    assert.equal(updateReplay.status, 200, JSON.stringify(updateReplay.data));
    assert.deepEqual(updateReplay.data, updated.data);
    const storedCount = await pool.query('SELECT count(*)::integer AS n FROM public.work_unit WHERE project_id=$1', [project.data.id]);
    assert.equal(storedCount.rows[0].n, 1);
    assert.equal((await request(prefix + '/work-units/' + work.data.id, 'DELETE', first)).status, 204);
    const otherPrefix = '/api/v1/organizations/' + second.organizationSlug;
    assert.equal((await request(otherPrefix + '/clients/' + client.data.id, 'DELETE', second)).status, 403);
    assert.equal((await request(otherPrefix + '/clients/' + client.data.id + '/unarchive', 'POST', second)).status, 403);
    assert.equal((await request(otherPrefix + '/projects/' + project.data.id + '/unarchive', 'POST', second)).status, 403);
    assert.equal((await pool.query('SELECT archived_at FROM public.client WHERE id=$1', [client.data.id])).rows[0].archived_at, null);
    assert.equal((await request(prefix + '/projects/' + project.data.id, 'DELETE', first)).status, 204);
    const projectRestored = await request(prefix + '/projects/' + project.data.id + '/unarchive', 'POST', first);
    assert.equal(projectRestored.status, 201, JSON.stringify(projectRestored.data));
    assert.equal((await pool.query('SELECT archived_at FROM public.project WHERE id=$1', [project.data.id])).rows[0].archived_at, null);
    assert.equal((await request(prefix + '/clients/' + client.data.id, 'DELETE', first)).status, 204);
    assert.ok((await pool.query('SELECT archived_at FROM public.client WHERE id=$1', [client.data.id])).rows[0].archived_at);
    assert.ok((await pool.query('SELECT archived_at FROM public.project WHERE id=$1', [project.data.id])).rows[0].archived_at);
    const clientRestored = await request(prefix + '/clients/' + client.data.id + '/unarchive', 'POST', first);
    assert.equal(clientRestored.status, 201, JSON.stringify(clientRestored.data));
    assert.equal((await pool.query('SELECT archived_at FROM public.client WHERE id=$1', [client.data.id])).rows[0].archived_at, null);
    assert.ok((await pool.query('SELECT archived_at FROM public.project WHERE id=$1', [project.data.id])).rows[0].archived_at);
    const childRestored = await request(prefix + '/projects/' + project.data.id + '/unarchive', 'POST', first);
    assert.equal(childRestored.status, 201, JSON.stringify(childRestored.data));
    assert.equal((await request(prefix + '/clients/' + client.data.id, 'DELETE', first)).status, 204);
    const parentAndChildRestored = await request(prefix + '/projects/' + project.data.id + '/unarchive', 'POST', first);
    assert.equal(parentAndChildRestored.status, 201, JSON.stringify(parentAndChildRestored.data));
    assert.equal((await pool.query('SELECT archived_at FROM public.client WHERE id=$1', [client.data.id])).rows[0].archived_at, null);
    assert.equal((await pool.query('SELECT archived_at FROM public.project WHERE id=$1', [project.data.id])).rows[0].archived_at, null);
    const reset = await request('/api/demo/reset', 'POST', first);
    assert.equal(reset.status, 200, JSON.stringify(reset.data));
    assert.equal(reset.data.expiresAt, first.expiresAt);
    const current = { ...reset.data, cookie: reset.cookie };
    sessions.push(current);
    assert.equal((await request('/api/v1/organizations', 'GET', first)).status, 401);
    assert.equal((await request('/api/v1/organizations', 'GET', { ...current, accessToken: first.accessToken })).status, 401);
    const resumed = await request('/api/demo/session', 'GET', current);
    assert.equal(resumed.data.organizationSlug, current.organizationSlug);
    await pool.query("UPDATE demo.session SET expires_at=now()+interval '30 seconds' WHERE organization_slug=$1", [current.organizationSlug]);
    assert.equal((await request('/api/v1/organizations', 'GET', current)).status, 401);
    const renewedResponse = await request('/api/demo/session', 'POST', current);
    assert.equal(renewedResponse.status, 200, JSON.stringify(renewedResponse.data));
    const renewed = { ...renewedResponse.data, cookie: renewedResponse.cookie };
    sessions.push(renewed);
    assert.notEqual(renewed.organizationSlug, current.organizationSlug);
    assert.ok(Date.parse(renewed.expiresAt) > Date.now() + 23 * 3600000);
    assert.equal((await request('/api/v1/organizations', 'GET', renewed)).status, 200);
    await pool.query("UPDATE demo.session SET expires_at=now()-interval '1 second' WHERE organization_slug=$1", [renewed.organizationSlug]);
    assert.equal((await request('/api/v1/organizations', 'GET', renewed)).status, 401);
  } catch (error) { failure = error; throw error; } finally {
    try {
      if (server) await new Promise(resolve => server.close(resolve));
      for (const session of sessions) await pool.query('UPDATE demo.session SET expires_at=now()-interval \'10 minutes\' WHERE organization_slug=$1', [session.organizationSlug]);
      if (sessions.length) await store.cleanup(new Date(Date.now() + 10 * 60000));
    } catch (error) { if (!failure) { failure = error; throw error; } }
    finally { try { await pool.end(); } catch (error) { if (!failure) throw error; } }
  }
});
