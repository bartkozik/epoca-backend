import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import pg from 'pg';
import { Store } from '../store.mjs';
import { route, validateReferences, tokenFor } from '../security.mjs';

const enabled = Boolean(process.env.TEST_DATABASE_URL);
test('PostgreSQL isolates sessions, serializes reset, preserves expiry and cleans actual edits', { skip: !enabled }, async () => {
  const pool = new pg.Pool({ connectionString: process.env.TEST_DATABASE_URL, max: 4, connectionTimeoutMillis: 5000 });
  const store = new Store(pool, { maxSessions: 100, secret: randomBytes(32).toString('hex') });
  const sessions = [];
  let failure;
  try {
    await store.init();
    const first = await store.create('integration-one');
    sessions.push(first.session);
    const second = await store.create('integration-two');
    sessions.push(second.session);
    assert.notEqual(first.session.organization_id, second.session.organization_id);
    assert.notEqual(first.session.user_id, second.session.user_id);
    const foreign = await pool.query('SELECT id FROM public.project WHERE organization_id=$1 LIMIT 1', [second.session.organization_id]);
    const path = `/api/v1/organizations/${first.session.organization_slug}/projects/${foreign.rows[0].id}`;
    await assert.rejects(validateReferences(pool, first.session, route('PATCH', path, first.session), new URL('https://demo.example' + path), {}), { status: 403 });
    const project = (await pool.query('SELECT id FROM public.project WHERE organization_id=$1 LIMIT 1', [first.session.organization_id])).rows[0];
    await pool.query('UPDATE public.project SET name=$1 WHERE id=$2', ['Changed demo project', project.id]);
    await pool.query('INSERT INTO public.work_unit_order (organization_id,user_id,date,work_unit_ids) VALUES ($1,$2,current_date,ARRAY[]::uuid[]) ON CONFLICT DO NOTHING', [first.session.organization_id, first.session.user_id]);
    const workUnit = (await pool.query('SELECT id FROM public.work_unit WHERE organization_id=$1 LIMIT 1', [first.session.organization_id])).rows[0];
    const historyId = randomUUID();
    await pool.query(`INSERT INTO public.work_unit_override_history (id,work_unit_id,version,operation,actor_id,actor_display_name,changed_at,provenance,before_snapshot,after_snapshot) VALUES ($1,$2,1,'Replace',$3,'Demo Guest',now(),'WorkUnitOverride','{}','{}')`, [historyId, workUnit.id, first.session.user_id]);
    await pool.query(`INSERT INTO public.work_unit_override_history (id,work_unit_id,version,operation,actor_id,actor_display_name,changed_at,provenance,before_snapshot,after_snapshot,reverts_event_id) VALUES ($1,$2,2,'Undo',$3,'Demo Guest',now(),'WorkUnitOverride','{}','{}',$4)`, [randomUUID(), workUnit.id, first.session.user_id, historyId]);
    await pool.query(`INSERT INTO public.work_unit_idempotency (claim_id,organization_id,actor_id,idempotency_key,operation,fingerprint_version,fingerprint,state,created_at) VALUES ($1,$2,$3,$4,'Create',1,'demo','Processing',now())`, [randomUUID(), first.session.organization_id, first.session.user_id, randomUUID()]);
    const reset = await store.withSession(first.cookie, db => store.reset(db, first.session));
    sessions.push(reset.session);
    assert.equal(reset.session.expires_at.getTime(), first.session.expires_at.getTime());
    assert.notEqual(reset.cookie, first.cookie);
    assert.notEqual(tokenFor(reset.session, 'x'), tokenFor(first.session, 'x'));
    await assert.rejects(store.withSession(first.cookie, async () => null), { status: 401 });
    await store.cleanup(new Date(Date.now() + 6 * 60000));
    assert.equal((await pool.query('SELECT 1 FROM public.organization WHERE id=$1', [first.session.organization_id])).rowCount, 0);
    assert.equal((await pool.query('SELECT 1 FROM public.organization WHERE id=$1', [second.session.organization_id])).rowCount, 1);
    assert.equal((await store.withSession(reset.cookie, async (_db, s) => s)).organization_id, reset.session.organization_id);
    await pool.query('UPDATE demo.session SET expires_at=now()-interval \'1 second\' WHERE id=$1', [second.session.id]);
    await assert.rejects(store.withSession(second.cookie, async () => null), { status: 401 });
    await store.cleanup(new Date(Date.now() + 6 * 60000));
    assert.equal((await pool.query('SELECT 1 FROM public.organization WHERE id=$1', [second.session.organization_id])).rowCount, 0);
    await pool.query("UPDATE demo.session SET expires_at=now()+interval '30 seconds' WHERE id=$1", [reset.session.id]);
    await assert.rejects(store.withSession(reset.cookie, async () => null), { status: 401 });
    const renewed = await store.create('integration-near-expiry');
    sessions.push(renewed.session);
    assert.notEqual(renewed.session.organization_id, reset.session.organization_id);
    assert.ok(renewed.session.expires_at.getTime() > Date.now() + 23 * 3600000);
    assert.equal((await store.withSession(renewed.cookie, async (_db, s) => s)).id, renewed.session.id);
  } catch (error) { failure = error; throw error; } finally {
    try {
      for (const s of sessions) await pool.query('UPDATE demo.session SET expires_at=now()-interval \'10 minutes\' WHERE id=$1', [s.id]);
      if (sessions.length) await store.cleanup(new Date(Date.now() + 10 * 60000));
    } catch (error) { if (!failure) { failure = error; throw error; } }
    finally { try { await pool.end(); } catch (error) { if (!failure) throw error; } }
  }
});

test('PostgreSQL shared capacity and fixed-window limits survive a second gateway', { skip: !enabled }, async () => {
  const pool = new pg.Pool({ connectionString: process.env.TEST_DATABASE_URL, max: 4, connectionTimeoutMillis: 5000 });
  const a = new Store(pool, { maxSessions: 1, secret: 'integration-rates' });
  const b = new Store(pool, { maxSessions: 1, secret: 'integration-rates' });
  let session;
  let failure;
  try {
    await a.init();
    session = await a.create('integration-capacity');
    await assert.rejects(b.create('integration-capacity-other'), { status: 429 });
    await a.rate('integration-' + session.session.id, 1, 3600);
    await assert.rejects(b.rate('integration-' + session.session.id, 1, 3600), { status: 429 });
    let unlock;
    let entered;
    const ready = new Promise(resolve => { entered = resolve; });
    const pending = a.withSession(session.cookie, async () => { entered(); await new Promise(resolve => { unlock = resolve; }); });
    await ready;
    let resetFinished = false;
    const resetting = b.withSession(session.cookie, (db, s) => b.reset(db, s)).then(value => { resetFinished = true; return value; });
    try {
      await new Promise(resolve => setTimeout(resolve, 30));
      assert.equal(resetFinished, false);
    } finally { unlock(); await pending; }
    const reset = await resetting;
    await pool.query('UPDATE demo.session SET expires_at=now()-interval \'10 minutes\' WHERE id=$1', [reset.session.id]);
  } catch (error) { failure = error; throw error; } finally {
    try {
      if (session) {
        await pool.query('UPDATE demo.session SET expires_at=now()-interval \'10 minutes\' WHERE id=$1', [session.session.id]);
        await a.cleanup(new Date(Date.now() + 10 * 60000));
      }
    } catch (error) { if (!failure) { failure = error; throw error; } }
    finally { try { await pool.end(); } catch (error) { if (!failure) throw error; } }
  }
});
