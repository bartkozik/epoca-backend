import pg from 'pg';
import { Store } from './store.mjs';
import { gateway } from './gateway.mjs';

const { DATABASE_URL, JWT_SECRET, DEMO_ORIGIN, BACKEND_URL, DEMO_TRUST_PROXY, PORT = '8080', DEMO_MAX_SESSIONS = '100' } = process.env;
if (!DATABASE_URL || !/^postgres(?:ql)?:\/\//.test(DATABASE_URL)) throw new Error('DATABASE_URL must be a PostgreSQL URL for the dedicated demo database.');
if (!JWT_SECRET || JWT_SECRET.length < 32) throw new Error('JWT_SECRET must contain at least 32 characters.');
const origin = new URL(DEMO_ORIGIN);
if (origin.protocol !== 'https:' || origin.origin !== DEMO_ORIGIN) throw new Error('DEMO_ORIGIN must be an exact HTTPS origin.');
const backend = new URL(BACKEND_URL);
if (!['http:', 'https:'].includes(backend.protocol) || backend.username || backend.password || backend.pathname !== '/' || backend.search || backend.hash) throw new Error('BACKEND_URL must identify the private backend origin.');
if (!/^\d+$/.test(PORT) || +PORT < 1 || +PORT > 65535) throw new Error('PORT must be a valid TCP port.');
if (!/^\d+$/.test(DEMO_MAX_SESSIONS) || +DEMO_MAX_SESSIONS < 1 || +DEMO_MAX_SESSIONS > 500) throw new Error('DEMO_MAX_SESSIONS must be between 1 and 500.');
const pool = new pg.Pool({ connectionString: DATABASE_URL, max: 8, connectionTimeoutMillis: 5000, idleTimeoutMillis: 30000, statement_timeout: 30000, application_name: 'epoca-demo-gateway' });
pool.on('error', () => process.stderr.write('Demo database connection failed.\n'));
const store = new Store(pool, { maxSessions: +DEMO_MAX_SESSIONS, secret: JWT_SECRET });
await store.init();
await store.cleanup();
let cleaning = false;
const interval = setInterval(async () => {
  if (cleaning) return;
  cleaning = true;
  try { await store.cleanup(); } catch { process.stderr.write('Demo cleanup failed; it will retry.\n'); }
  finally { cleaning = false; }
}, 60000);
interval.unref();
const server = gateway(store, { origin: DEMO_ORIGIN, secret: JWT_SECRET, backend: backend.origin, trustProxy: DEMO_TRUST_PROXY === 'true' });
server.listen(+PORT, '0.0.0.0');
async function stop() {
  clearInterval(interval);
  server.close(async () => { await pool.end(); process.exit(0); });
  setTimeout(() => process.exit(1), 25000).unref();
}
process.once('SIGTERM', stop);
process.once('SIGINT', stop);
