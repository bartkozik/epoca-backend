import { randomBytes, randomUUID, createHash, createHmac } from 'node:crypto';
import { DemoError } from './security.mjs';
import { seed, deleteDataset } from './seed.mjs';

const hash = value => createHash('sha256').update(value).digest('hex');
export class Store {
  constructor(pool, { maxSessions = 100, secret }) { this.pool = pool; this.maxSessions = maxSessions; this.secret = secret; }
  async transaction(fn) {
    const db = await this.pool.connect();
    try {
      await db.query('BEGIN');
      await db.query("SET LOCAL lock_timeout = '20s'");
      await db.query("SET LOCAL statement_timeout = '30s'");
      const result = await fn(db);
      await db.query('COMMIT');
      return result;
    } catch (error) { await db.query('ROLLBACK'); throw error; }
    finally { db.release(); }
  }
  async init() {
    await this.transaction(async db => {
      await db.query('SELECT pg_advisory_xact_lock(7639201)');
      const migrated = await db.query("SELECT 1 FROM public.flyway_schema_history WHERE version='37' AND success");
      if (!migrated.rowCount) throw new Error('The demo database must be migrated through V37.');
      await db.query(`CREATE SCHEMA IF NOT EXISTS demo;
        CREATE TABLE IF NOT EXISTS demo.session (
          id uuid PRIMARY KEY, token_hash text NOT NULL UNIQUE, organization_id uuid NOT NULL UNIQUE,
          organization_slug text NOT NULL, user_id uuid NOT NULL, identity_id uuid NOT NULL,
          user_ids uuid[] NOT NULL, created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
          request_count integer NOT NULL DEFAULT 0, request_window timestamptz NOT NULL DEFAULT now(),
          reset_count integer NOT NULL DEFAULT 0, reset_window timestamptz NOT NULL DEFAULT now()
        );
        CREATE INDEX IF NOT EXISTS session_expiry_idx ON demo.session(expires_at);
        CREATE TABLE IF NOT EXISTS demo.retired (organization_id uuid PRIMARY KEY, user_ids uuid[] NOT NULL, delete_after timestamptz NOT NULL);
        CREATE TABLE IF NOT EXISTS demo.rate (key text PRIMARY KEY, window_start timestamptz NOT NULL, count integer NOT NULL);
      `);
    });
  }
  async rate(key, limit, seconds) {
    const bucket = createHmac('sha256', this.secret).update(key).digest('hex');
    const result = await this.pool.query(`INSERT INTO demo.rate AS r (key,window_start,count) VALUES ($1,to_timestamp(floor(extract(epoch FROM clock_timestamp())/$2)*$2),1)
      ON CONFLICT(key) DO UPDATE SET window_start=EXCLUDED.window_start,count=CASE WHEN r.window_start=EXCLUDED.window_start THEN r.count+1 ELSE 1 END RETURNING count`, [bucket, seconds]);
    if (result.rows[0].count > limit) throw new DemoError(429, 'The demo is busy. Please try again later.');
  }
  async create(ip) {
    await this.rate('create-global', 30, 3600);
    await this.rate('create-ip:' + ip, 5, 3600);
    return this.transaction(async db => {
      await db.query('SELECT pg_advisory_xact_lock(7639202)');
      const count = await db.query('SELECT count(*)::integer AS n FROM demo.session');
      if (count.rows[0].n >= this.maxSessions) throw new DemoError(429, 'All demo spaces are in use. Please try again later.');
      const data = await seed(db);
      const cookie = randomBytes(32).toString('base64url');
      const result = await db.query(`INSERT INTO demo.session (id,token_hash,organization_id,organization_slug,user_id,identity_id,user_ids,created_at,expires_at)
        VALUES ($1,$2,$3,$4,$5,$6,$7,date_trunc('second',now()),date_trunc('second',now())+interval '24 hours') RETURNING *`, [randomUUID(), hash(cookie), data.organization_id, data.organization_slug, data.user_id, data.identity_id, data.user_ids]);
      return { session: result.rows[0], cookie };
    });
  }
  async withSession(cookie, fn) {
    if (!cookie) throw new DemoError(401, 'Your demo session has ended. Start a new demo to continue.');
    return this.transaction(async db => {
      const result = await db.query('SELECT * FROM demo.session WHERE token_hash=$1 FOR UPDATE', [hash(cookie)]);
      const session = result.rows[0];
      if (!session || session.expires_at.getTime() <= Date.now()) throw new DemoError(401, 'Your demo session has ended. Start a new demo to continue.');
      const now = Date.now();
      const count = now - session.request_window.getTime() >= 60000 ? 1 : session.request_count + 1;
      if (count > 180) throw new DemoError(429, 'Please wait a minute before making more demo requests.');
      await db.query('UPDATE demo.session SET request_count=$2,request_window=$3 WHERE id=$1', [session.id, count, count === 1 ? new Date(now) : session.request_window]);
      return fn(db, session);
    });
  }
  async reset(db, session) {
    const now = Date.now();
    const count = now - session.reset_window.getTime() >= 3600000 ? 1 : session.reset_count + 1;
    if (count > 5) throw new DemoError(429, 'You can reset your demo five times per hour.');
    await db.query('SELECT pg_advisory_xact_lock(7639202)');
    await db.query("INSERT INTO demo.retired (organization_id,user_ids,delete_after) VALUES ($1,$2,now()+interval '5 minutes')", [session.organization_id, session.user_ids]);
    const capacity = await db.query('SELECT (SELECT count(*) FROM demo.session)+(SELECT count(*) FROM demo.retired) AS n');
    if (+capacity.rows[0].n >= this.maxSessions * 3) throw new DemoError(429, 'Demo cleanup is catching up. Please try again later.');
    const data = await seed(db);
    const cookie = randomBytes(32).toString('base64url');
    const result = await db.query(`UPDATE demo.session SET token_hash=$2,organization_id=$3,organization_slug=$4,user_id=$5,identity_id=$6,user_ids=$7,reset_count=$8,reset_window=$9 WHERE id=$1 RETURNING *`, [session.id, hash(cookie), data.organization_id, data.organization_slug, data.user_id, data.identity_id, data.user_ids, count, count === 1 ? new Date(now) : session.reset_window]);
    return { session: result.rows[0], cookie };
  }
  async cleanup(now = new Date()) {
    return this.transaction(async db => {
      const sessions = await db.query("SELECT * FROM demo.session WHERE expires_at<$1::timestamptz-interval '5 minutes' ORDER BY expires_at LIMIT 20 FOR UPDATE SKIP LOCKED", [now]);
      for (const session of sessions.rows) { await deleteDataset(db, session); await db.query('DELETE FROM demo.session WHERE id=$1', [session.id]); }
      const retired = await db.query('SELECT * FROM demo.retired WHERE delete_after<$1 ORDER BY delete_after LIMIT 20 FOR UPDATE SKIP LOCKED', [now]);
      for (const dataset of retired.rows) { await deleteDataset(db, dataset); await db.query('DELETE FROM demo.retired WHERE organization_id=$1', [dataset.organization_id]); }
      await db.query("DELETE FROM demo.rate WHERE window_start<now()-interval '2 days'");
    });
  }
  async checkCapacity(db, session, r, idempotencyKey) {
    if (idempotencyKey && r.resource === 'work_unit' && ['POST', 'PATCH'].includes(r.method)) {
      const existing = await db.query('SELECT claim_id FROM public.work_unit_idempotency WHERE organization_id=$1 AND actor_id=$2 AND idempotency_key=$3', [session.organization_id, session.user_id, idempotencyKey]);
      if (existing.rowCount) return;
      const claims = await db.query('SELECT count(*)::integer AS n FROM public.work_unit_idempotency WHERE organization_id=$1', [session.organization_id]);
      if (claims.rows[0].n >= 2000) throw new DemoError(403, 'This demo has reached its work-entry change limit. Reset it to continue.');
    }
    if (r.method !== 'POST' || !r.collection || !['client', 'project', 'work_unit'].includes(r.resource)) return;
    const limits = { client: 30, project: 60, work_unit: 1000 };
    const result = await db.query(`SELECT count(*)::integer AS n FROM public.${r.resource} WHERE organization_id=$1`, [session.organization_id]);
    if (result.rows[0].n >= limits[r.resource]) throw new DemoError(403, 'This demo has reached its record limit. Reset it to continue.');
  }
}
