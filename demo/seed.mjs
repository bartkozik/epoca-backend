import { randomUUID } from 'node:crypto';

export async function seed(db) {
  const organizationId = randomUUID();
  const userIds = Array.from({ length: 5 }, () => randomUUID());
  const identityId = randomUUID();
  const slug = 'demo-' + randomUUID();
  const names = [['Demo', 'Guest'], ['Avery', 'Stone'], ['Morgan', 'Reed'], ['Jamie', 'Brooks'], ['Riley', 'North']];
  for (let i = 0; i < userIds.length; i++) {
    await db.query('INSERT INTO public."user" (id,first_name,last_name,color,is_super_admin,created_at,modified_at,account_type) VALUES ($1,$2,$3,$4,false,now(),now(),\'Human\')', [userIds[i], ...names[i], ['#3578E5', '#AC64D3', '#22A699', '#D97A37', '#6875D9'][i]]);
    await db.query('INSERT INTO public.user_identity (id,user_id,type,value,main,confirmed,created_at) VALUES ($1,$2,\'MailAddress\',$3,true,true,now())', [i === 0 ? identityId : randomUUID(), userIds[i], `${i === 0 ? 'guest' : 'member'}-${userIds[i]}@example.invalid`]);
  }
  await db.query('INSERT INTO public.organization (id,name,slug,created_at,created_by,modified_at,modified_by) VALUES ($1,\'Northstar Studio\',$2,now(),$3,now(),$3)', [organizationId, slug, userIds[0]]);
  for (let i = 0; i < userIds.length; i++) await db.query('INSERT INTO public.organization_user (id,organization_id,user_id,roles) VALUES ($1,$2,$3,$4)', [randomUUID(), organizationId, userIds[i], [i === 0 ? 'Owner' : 'Employee']]);
  const clients = [['Lumen Goods', '#8468D6'], ['Cedar Labs', '#2B9B83'], ['Northstar Internal', '#D58A43']];
  const projects = [];
  for (let i = 0; i < clients.length; i++) {
    const clientId = randomUUID();
    await db.query('INSERT INTO public.client (id,name,color,organization_id,created_at,created_by,modified_at,modified_by,type,logo,texture) VALUES ($1,$2,$3,$4,now(),$5,now(),$5,$6,\'\',\'\')', [clientId, ...clients[i], organizationId, userIds[0], i === 2 ? 'Internal' : 'External']);
    for (const name of [['Storefront refresh', 'Customer discovery'], ['Research portal', 'Product launch'], ['Studio operations']][i]) {
      const projectId = randomUUID();
      projects.push({ id: projectId, clientId });
      await db.query('INSERT INTO public.project (id,name,description,organization_id,client_id,created_at,created_by,modified_at,modified_by) VALUES ($1,$2,\'Fictional project for exploring EPOCA.\',$3,$4,now(),$5,now(),$5)', [projectId, name, organizationId, clientId, userIds[0]]);
      for (let j = 0; j < userIds.length; j++) await db.query('INSERT INTO public.project_user (id,project_id,user_id,role,created_at) VALUES ($1,$2,$3,$4,now())', [randomUUID(), projectId, userIds[j], j === 0 ? 'Owner' : 'Member']);
    }
  }
  const descriptions = ['Review design concepts', 'Prototype a new flow', 'Plan the next release', 'Research customer needs', 'Improve project documentation', 'Team review and planning'];
  for (let day = 0; day < 28; day++) {
    for (let member = 0; member < userIds.length; member++) {
      const p = projects[(day + member) % projects.length];
      await db.query('INSERT INTO public.work_unit (id,organization_id,user_id,project_id,client_id,date,duration,description,created_at,modified_at,state,entry_type,task_id) VALUES ($1,$2,$3,$4,$5,current_date-$6::integer,make_interval(secs=>$7),$8,now(),now(),\'Active\',\'Worklog\',$9)', [randomUUID(), organizationId, userIds[member], p.id, p.clientId, day, (1 + ((day + member) % 6)) * 1800, descriptions[(day + member) % descriptions.length], 'DEMO-' + (101 + day)]);
    }
  }
  return { organization_id: organizationId, organization_slug: slug, user_id: userIds[0], identity_id: identityId, user_ids: userIds };
}

export async function deleteDataset(db, dataset) {
  const org = [dataset.organization_id];
  await db.query('DELETE FROM public.work_unit_idempotency WHERE organization_id=$1', org);
  await db.query('DELETE FROM public.work_unit_order WHERE organization_id=$1', org);
  await db.query('DELETE FROM public.work_unit_override_history WHERE work_unit_id IN (SELECT id FROM public.work_unit WHERE organization_id=$1)', org);
  await db.query('DELETE FROM public.work_unit WHERE organization_id=$1', org);
  await db.query('DELETE FROM public.project_user WHERE project_id IN (SELECT id FROM public.project WHERE organization_id=$1)', org);
  await db.query('DELETE FROM public.project WHERE organization_id=$1', org);
  await db.query('DELETE FROM public.client WHERE organization_id=$1', org);
  await db.query('DELETE FROM public.organization_user WHERE organization_id=$1', org);
  await db.query('DELETE FROM public.organization WHERE id=$1', org);
  await db.query('DELETE FROM public.user_identity WHERE user_id=ANY($1::uuid[])', [dataset.user_ids]);
  await db.query('DELETE FROM public."user" WHERE id=ANY($1::uuid[])', [dataset.user_ids]);
}
