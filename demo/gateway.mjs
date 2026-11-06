import { previewResponse } from './previews.mjs';
import { createServer } from 'node:http';
import { isIP } from 'node:net';
import { DemoError, blocked, checkOrigin, cookieValue, equalToken, route, tokenFor, validateInput, validateReferences, uuid } from './security.mjs';

function json(res, status, body) {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(body));
}
async function readBody(req) {
  if (Number(req.headers['content-length']) > 32768) throw new DemoError(413, 'Demo requests are limited to 32 KB.');
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 32768) throw new DemoError(413, 'Demo requests are limited to 32 KB.');
    chunks.push(chunk);
  }
  if (!size) return null;
  if (!/^application\/json(?:;|$)/i.test(req.headers['content-type'] ?? '')) throw new DemoError(415, 'Use JSON for demo requests.');
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw new DemoError(400, 'The request contains invalid JSON.'); }
}
function present(res, value, secret) {
  if (value.cookie) {
    const maxAge = Math.max(0, Math.floor((value.session.expires_at.getTime() - Date.now()) / 1000));
    res.setHeader('set-cookie', `__Host-epoca-demo=${value.cookie}; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=${maxAge}; Expires=${value.session.expires_at.toUTCString()}`);
  }
  json(res, 200, { accessToken: tokenFor(value.session, secret), organizationSlug: value.session.organization_slug, expiresAt: value.session.expires_at.toISOString() });
}
async function proxy(config, url, method, body, token, idempotencyKey) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch(config.backend + url.pathname + url.search, {
      method, redirect: 'manual', signal: controller.signal,
      headers: { authorization: 'Bearer ' + token, accept: 'application/json', ...(idempotencyKey ? { 'idempotency-key': idempotencyKey } : {}), ...(body === null ? {} : { 'content-type': 'application/json' }) },
      body: body === null ? undefined : JSON.stringify(body)
    });
    if (response.status >= 300 && response.status < 400) throw new DemoError(502, 'The demo service returned an unexpected redirect.');
    const chunks = [];
    let size = 0;
    if (response.body) for await (const chunk of response.body) {
      size += chunk.length;
      if (size > 2 * 1024 * 1024) { controller.abort(); throw new DemoError(413, 'This result is too large. Narrow the demo query.'); }
      chunks.push(chunk);
    }
    if (response.status >= 500) throw new DemoError(502, 'The demo service is temporarily unavailable.');
    return { status: response.status, body: Buffer.concat(chunks), type: response.headers.get('content-type')?.startsWith('application/json') ? 'application/json; charset=utf-8' : 'text/plain; charset=utf-8' };
  } catch (error) {
    if (error instanceof DemoError) throw error;
    throw new DemoError(502, 'The demo service is temporarily unavailable. Please try again.');
  } finally { clearTimeout(timer); }
}
export function gateway(store, config) {
  const server = createServer({ maxHeaderSize: 16384, requestTimeout: 20000, headersTimeout: 10000, keepAliveTimeout: 5000 }, async (req, res) => {
    res.setHeader('cache-control', 'no-store');
    res.setHeader('x-content-type-options', 'nosniff');
    res.setHeader('referrer-policy', 'no-referrer');
    try {
      if (!req.url?.startsWith('/') || req.url.startsWith('//') || req.url.length > 8192 || /[%\\]/.test(req.url.split('?')[0]) || req.url.split('?')[0].includes('..')) blocked();
      const url = new URL(req.url, config.origin);
      if (req.method === 'GET' && url.pathname === '/health' && !url.search) {
        await store.pool.query('SELECT 1');
        const response = await fetch(config.backend + '/api/v1/health-check', { signal: AbortSignal.timeout(3000), redirect: 'manual' });
        await response.body?.cancel();
        if (!response.ok) throw new DemoError(503, 'The demo is starting.');
        json(res, 200, { status: 'ok' });
        return;
      }
      checkOrigin(req.method, req.headers, config.origin);
      let ip = req.socket.remoteAddress ?? 'unknown';
      if (config.trustProxy) {
        const value = req.headers['x-real-ip'];
        if (typeof value !== 'string' || !isIP(value)) blocked('The demo proxy is not configured correctly.');
        ip = value;
      }
      await store.rate('requests-global', 1800, 60);
      await store.rate('requests-ip:' + ip, 360, 60);
      const body = await readBody(req);
      if (['GET', 'DELETE'].includes(req.method) && body !== null) blocked();
      const cookie = cookieValue(req.headers.cookie);
      if (url.pathname === '/api/demo/session' && !url.search && ['GET', 'POST'].includes(req.method)) {
        try {
          const value = await store.withSession(cookie, async (_db, session) => ({ session }));
          present(res, value, config.secret);
        } catch (error) {
          if (error.status !== 401 || req.method !== 'POST') throw error;
          present(res, await store.create(ip), config.secret);
        }
        return;
      }
      if (url.pathname === '/api/demo/reset' && !url.search && req.method === 'POST') {
        await store.rate('reset-global', 60, 3600);
        present(res, await store.withSession(cookie, (db, session) => store.reset(db, session)), config.secret);
        return;
      }
      const result = await store.withSession(cookie, async (db, session) => {
        const token = tokenFor(session, config.secret);
        if (!equalToken(req.headers.authorization, 'Bearer ' + token)) throw new DemoError(401, 'Your demo session changed. Reload the demo to continue.');
        const allowed = route(req.method, url.pathname, session);
        let idempotencyKey;
        if (allowed.resource === 'work_unit' && ['POST', 'PATCH'].includes(req.method) && req.headers['idempotency-key'] !== undefined) {
          const keys = req.rawHeaders.filter((value, index) => index % 2 === 0 && value.toLowerCase() === 'idempotency-key');
          const value = req.headers['idempotency-key'];
          if (keys.length !== 1 || typeof value !== 'string' || !uuid.test(value)) throw new DemoError(400, 'Provide one UUID Idempotency-Key for this work entry.');
          idempotencyKey = value;
        }
        validateInput(allowed, url, body);
        if (allowed.preview) return { status: 200, type: 'application/json; charset=utf-8', body: JSON.stringify(previewResponse(session, allowed, url)) };
        await validateReferences(db, session, allowed, url, body);
        await store.checkCapacity(db, session, allowed, idempotencyKey);
        return proxy(config, url, req.method, body, token, idempotencyKey);
      });
      res.writeHead(result.status, { 'content-type': result.type });
      res.end(result.body);
    } catch (error) {
      const status = error instanceof DemoError ? error.status : 503;
      if (status === 429) res.setHeader('retry-after', '60');
      if (!res.headersSent) json(res, status, { code: status === 401 ? 'DEMO_SESSION_EXPIRED' : status === 403 ? 'DEMO_ACTION_BLOCKED' : 'DEMO_UNAVAILABLE', message: error instanceof DemoError ? error.message : 'The demo is temporarily unavailable. Please try again.' });
      else res.destroy();
      if (!(error instanceof DemoError)) process.stderr.write('Demo request failed.\n');
    }
  });
  server.maxConnections = 100;
  return server;
}
