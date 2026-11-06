import { createHash } from 'node:crypto';
import { blocked } from './security.mjs';

function fixtureId(session, name) {
  const hash = createHash('sha256').update(`${session.organization_id}:${name}`).digest('hex');
  return `${hash.slice(0, 8)}-${hash.slice(8, 12)}-4${hash.slice(13, 16)}-8${hash.slice(17, 20)}-${hash.slice(20, 32)}`;
}
function page(results, url) {
  const size = Number(url.searchParams.get('pageSize') ?? 20);
  const number = Number(url.searchParams.get('pageNumber') ?? 1);
  return { count: results.length, results: results.slice((number - 1) * size, number * size) };
}
export function previewResponse(session, route, url) {
  const createdAt = new Date(session.created_at).toISOString();
  const expiresAt = new Date(session.expires_at).toISOString();
  if (route.preview === 'saml') return {
    organizationId: session.organization_id, createdAt, allowAccountInit: false, isEnabled: false,
    idpCert: '', idpEntityId: 'https://identity.example.invalid/demo', idpSsoUrl: 'https://identity.example.invalid/sso',
    langCode: 'en', validFor: 'PT8H'
  };
  if (route.preview === 'invitations') return {
    ...page([
      { id: fixtureId(session, 'invitation-pending'), email: 'alex.morgan@example.invalid', firstName: 'Alex', lastName: 'Morgan', roles: ['Employee'], protocolVersion: 2, revision: '1', status: 'Pending', expiresAt, delivery: { status: 'NotSent', lastAttemptAt: null, retryAfterSeconds: 0 }, createdAt, modifiedAt: createdAt },
      { id: fixtureId(session, 'invitation-expired'), email: 'taylor.lee@example.invalid', firstName: 'Taylor', lastName: 'Lee', roles: ['Manager'], protocolVersion: 2, revision: '2', status: 'Expired', expiresAt: createdAt, delivery: { status: 'NotSent', lastAttemptAt: null, retryAfterSeconds: 0 }, createdAt, modifiedAt: createdAt }
    ], url), featureEnabled: true
  };
  if (route.preview === 'oauth') {
    const actor = { userId: session.user_id, displayName: 'Demo Guest' };
    const client = {
      clientId: fixtureId(session, 'oauth-reporting'), name: 'Reporting integration (preview)', status: 'Active',
      scopes: ['organization:read', 'projects:read', 'reports:read'], projectAccess: { mode: 'AllProjects' },
      version: 1, policyVersion: 1, secretVersion: 1,
      serviceAccount: { userId: fixtureId(session, 'oauth-service-account'), displayName: 'Reporting service (preview)' },
      createdAt, createdBy: actor, modifiedAt: createdAt, modifiedBy: actor,
      secretRotatedAt: createdAt, secretRotatedBy: actor, expiresAt: null, revokedAt: null, revokedBy: null, lastUsedAt: null
    };
    if (route.id) {
      if (route.id !== client.clientId) blocked('This preview belongs to another demo session.');
      if (route.audit) return page([{ id: fixtureId(session, 'oauth-created'), occurredAt: createdAt, actorType: 'Human', actorUserId: session.user_id, actor, eventType: 'Create', outcome: 'Success', metadata: { scopes: client.scopes, projectMode: 'AllProjects', version: 1, policyVersion: 1, secretVersion: 1 } }], url);
      return client;
    }
    return page(!url.searchParams.has('status') || url.searchParams.get('status') === client.status ? [client] : [], url);
  }
  blocked();
}
