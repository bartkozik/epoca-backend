import { createHmac, timingSafeEqual } from 'node:crypto';

export const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
export class DemoError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}
export const blocked = (message = 'This action is unavailable in the public demo.') => { throw new DemoError(403, message); };
export function checkOrigin(method, headers, origin) {
  if (headers['sec-fetch-site'] === 'cross-site' || (headers.origin && headers.origin !== origin) || (method !== 'GET' && headers.origin !== origin)) blocked('Open the demo on its original website to continue.');
}
export function cookieValue(header = '') {
  const values = header.split(';').map(v => v.trim()).filter(v => v.startsWith('__Host-epoca-demo='));
  if (values.length !== 1) return null;
  const token = values[0].slice('__Host-epoca-demo='.length);
  return /^[A-Za-z0-9_-]{43}$/.test(token) ? token : null;
}
export function equalToken(a, b) {
  if (typeof a !== 'string') return false;
  const actual = Buffer.from(a);
  const expected = Buffer.from(b);
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}
export function tokenFor(s, secret) {
  const payload = {
    id: s.user_id, isSuperAdmin: false,
    identities: [{ id: s.identity_id, type: 'MailAddress', value: `guest-${s.user_id}@example.invalid`, main: true, deviceId: null }],
    authedBy: [{ type: 'MailAddress', id: s.identity_id }],
    roles: [{ organizationId: s.organization_id, roles: ['Owner'] }],
    lastAuthedAt: new Date(s.created_at).toISOString(), fresh: true, accountAuthentication: null,
    iat: Math.floor(new Date(s.created_at).getTime() / 1000), exp: Math.floor(new Date(s.expires_at).getTime() / 1000)
  };
  const input = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url') + '.' + Buffer.from(JSON.stringify(payload)).toString('base64url');
  return input + '.' + createHmac('sha256', secret).update(input).digest('base64url');
}
export function route(method, path, s) {
  if (path.includes('%') || path.includes('..') || path.includes('\\') || path.includes('//')) blocked();
  if (method === 'GET' && path === '/api/v1/organizations') return { resource: 'organization', method };
  if (method === 'GET' && path === `/api/v1/users/${s.user_id}`) return { resource: 'user', method };
  const prefix = `/api/v1/organizations/${s.organization_slug}`;
  if (method === 'GET' && path === prefix) return { resource: 'organization', method };
  if (!path.startsWith(prefix + '/')) blocked();
  const segments = path.slice(prefix.length + 1).split('/');
  const [name, id, extra] = segments;
  if (method === 'GET') {
    if (segments.join('/') === 'saml/configs') return { resource: 'preview', preview: 'saml', method };
    if (name === 'invitations' && segments.length === 1) return { resource: 'preview', preview: 'invitations', method };
    if (name === 'oauth-clients' && (segments.length === 1 || (uuid.test(id) && (segments.length === 2 || (segments.length === 3 && extra === 'audit-events'))))) return { resource: 'preview', preview: 'oauth', method, id, audit: extra === 'audit-events' };
  }
  if (method === 'POST' && name === 'users' && segments.length === 2 && ['assign', 'deassign'].includes(id)) return { resource: 'membership', method, action: id };
  const resource = { clients: 'client', projects: 'project', 'work-units': 'work_unit', users: 'user' }[name];
  if (segments.length === 1 && resource && (method === 'GET' || (method === 'POST' && resource !== 'user'))) return { resource, method, collection: true };
  if (segments.length === 2 && uuid.test(id)) {
    if ((resource === 'project' && ['GET', 'PATCH', 'DELETE'].includes(method)) || (resource === 'client' && ['PATCH', 'DELETE'].includes(method)) || (resource === 'work_unit' && ['PATCH', 'DELETE'].includes(method))) return { resource, method, id };
  }
  if (['client', 'project'].includes(resource) && segments.length === 3 && uuid.test(id) && extra === 'unarchive' && method === 'POST') return { resource, method, id, restore: true };
  if (name === 'users' && segments.length === 3 && uuid.test(id) && extra === 'details' && method === 'GET') return { resource: 'user', method, id };
  if (name === 'work-units' && id === 'order' && segments.length === 2 && method === 'PUT') return { resource: 'work_unit', method, order: true };
  if (name === 'pivot-tables' && id === 'work-units' && method === 'POST' && (segments.length === 2 || (segments.length === 3 && extra === 'query'))) return { resource: 'pivot', method };
  blocked();
}
const fields = {
  client: ['name', 'color', 'logo', 'texture', 'type'],
  project: ['name', 'description', 'clientId', 'ownerIds', 'memberIds'],
  work_unit: ['projectId', 'date', 'duration', 'description', 'taskId', 'isDraft', 'entryType', 'userId', 'shouldFinalize'],
  pivot: ['keys', 'rows', 'columns', 'values', 'filters', 'options']
};
const queryFields = new Set(['id', 'dateFrom', 'dateTo', 'projectId', 'userId', 'clientId', 'state', 'includeArchived', 'includeArchivedConnections', 'includeArchivedMembers', 'sortBy', 'shouldReturnDraftIds', 'type', 'pageSize', 'pageNumber', 'search', 'role']);
export function validateInput(r, url, body) {
  if (url.search.length > 6000 || [...url.searchParams].length > 100) blocked('The demo query is too large.');
  if (r.resource === 'membership' && url.search) blocked();
  const previewQueries = r.preview === 'invitations' ? ['pageSize', 'pageNumber', 'version'] : r.preview === 'oauth' ? (r.id ? (r.audit ? ['pageSize', 'pageNumber'] : []) : ['pageSize', 'pageNumber', 'status']) : [];
  for (const [key, value] of url.searchParams) {
    if (!(r.preview ? previewQueries.includes(key) : queryFields.has(key)) || value.length > 255) blocked('This query is unavailable in the demo.');
    if (['pageSize', 'pageNumber'].includes(key) && (!/^\d+$/.test(value) || +value < 1 || +value > (r.preview === 'invitations' && key === 'pageSize' ? 200 : 100))) blocked('Use a supported demo page size and number.');
    if (r.preview && url.searchParams.getAll(key).length !== 1) blocked();
    if (r.preview && key === 'version' && value !== '2') blocked();
    if (r.preview && key === 'status' && !['Active', 'Expired', 'Revoked'].includes(value)) blocked();
  }
  if (r.resource === 'pivot' || r.resource === 'work_unit') {
    const today = new Date().toISOString().slice(0, 10);
    const from = url.searchParams.get('dateFrom') ?? new Date(Date.now() - 90 * 86400000).toISOString().slice(0, 10);
    const to = url.searchParams.get('dateTo') ?? today;
    for (const date of [from, to]) if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !Number.isFinite(Date.parse(date)) || new Date(date).toISOString().slice(0, 10) !== date) blocked('Use valid calendar dates.');
    if (Date.parse(to) < Date.parse(from) || Date.parse(to) - Date.parse(from) > 366 * 86400000) blocked('Demo reports support a maximum of one year.');
    if (r.method === 'GET' || r.resource === 'pivot') { url.searchParams.set('dateFrom', from); url.searchParams.set('dateTo', to); }
  }
  if (r.resource === 'membership') {
    const required = r.action === 'assign' ? ['userId', 'roles'] : ['userId'];
    if (!body || Array.isArray(body) || typeof body !== 'object' || Object.keys(body).length !== required.length || required.some(key => !Object.hasOwn(body, key)) || typeof body.userId !== 'string' || !uuid.test(body.userId)) blocked('Use a valid demo member.');
    if (r.action === 'assign' && (!Array.isArray(body.roles) || body.roles.length !== 1 || !['Owner', 'Manager', 'Employee', 'External'].includes(body.roles[0]))) blocked('Choose one supported member role.');
  }
  if (body !== null) {
    if (!body || Array.isArray(body) || typeof body !== 'object') blocked('A JSON object is required.');
    const allowed = r.resource === 'membership' ? (r.action === 'assign' ? ['userId', 'roles'] : ['userId']) : r.restore ? [] : r.order ? ['date', 'workUnitIds'] : fields[r.resource] ?? [];
    if (Object.keys(body).some(k => !allowed.includes(k))) blocked('This field is unavailable in the demo.');
    for (const key of ['projectId', 'clientId', 'userId']) if (body[key] != null && (typeof body[key] !== 'string' || !uuid.test(body[key]))) blocked('Use a valid demo record.');
    for (const key of ['ownerIds', 'memberIds', 'workUnitIds']) if (body[key] != null && (!Array.isArray(body[key]) || body[key].some(id => typeof id !== 'string' || !uuid.test(id)))) blocked('Use valid demo records.');
    if (body.date != null && (typeof body.date !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(body.date) || !Number.isFinite(Date.parse(body.date)) || Math.abs(Date.parse(body.date) - Date.now()) > 366 * 86400000)) blocked('Demo entries are limited to dates within one year of today.');
    if (body.texture && !/^[a-zA-Z0-9_-]{1,40}$/.test(body.texture)) blocked('Use a built-in demo background.');
    if (body.logo && body.logo !== '') blocked('External images and uploads are disabled in the demo.');
    walk(body, (value) => { if (typeof value === 'string' && value.length > 4000) blocked('Demo text fields are limited to 4,000 characters.'); });
  }
}
export function walk(value, visit, depth = 0) {
  if (depth > 8 || (Array.isArray(value) && value.length > 100)) blocked('The demo request is too large.');
  visit(value);
  if (value && typeof value === 'object') for (const child of Object.values(value)) walk(child, visit, depth + 1);
}
export async function validateReferences(db, s, r, url, body) {
  if (r.resource === 'membership') {
    const id = body.userId.toLowerCase();
    if (id === s.user_id.toLowerCase() || !s.user_ids.some(userId => userId.toLowerCase() === id)) blocked('The guest Owner and other sessions cannot be changed.');
    const member = await db.query(`SELECT membership.user_id AS id FROM public.organization_user membership JOIN public."user" member ON member.id=membership.user_id WHERE membership.organization_id=$1 AND membership.user_id=$2 AND membership.archived_at IS NULL AND member.archived_at IS NULL AND member.account_type='Human'`, [s.organization_id, id]);
    if (member.rows.length !== 1 || member.rows[0].id.toLowerCase() !== id) blocked('Choose an active member of this demo team.');
  }
  const ids = new Set();
  if (r.id) ids.add(r.id.toLowerCase());
  for (const [key, value] of url.searchParams) if (key === 'id' || key.endsWith('Id')) { if (!uuid.test(value)) blocked('Use a valid demo record.'); ids.add(value.toLowerCase()); }
  walk(body, value => { if (typeof value === 'string' && uuid.test(value)) ids.add(value.toLowerCase()); });
  if (!ids.size) return;
  const result = await db.query(`SELECT id FROM public.client WHERE organization_id=$1 AND id=ANY($2::uuid[])
    UNION SELECT id FROM public.project WHERE organization_id=$1 AND id=ANY($2::uuid[])
    UNION SELECT id FROM public.work_unit WHERE organization_id=$1 AND id=ANY($2::uuid[])
    UNION SELECT id FROM public.organization WHERE id=$1 AND id=ANY($2::uuid[])
    UNION SELECT user_id AS id FROM public.organization_user WHERE organization_id=$1 AND user_id=ANY($2::uuid[])`, [s.organization_id, [...ids]]);
  if (new Set(result.rows.map(row => row.id)).size !== ids.size) blocked('This record belongs to another demo session.');
}
