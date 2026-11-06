CREATE TEMP TABLE demo_seed_target_project_user
(
    project_user_id UUID PRIMARY KEY,
    project_order INTEGER NOT NULL,
    project_key TEXT NOT NULL,
    project_id UUID NOT NULL,
    project_status TEXT NOT NULL CHECK (project_status IN ('active', 'archived')),
    team_size INTEGER NOT NULL CHECK (team_size BETWEEN 3 AND 12),
    profile_order INTEGER NOT NULL,
    user_key TEXT NOT NULL,
    user_id UUID NOT NULL,
    organization_role TEXT NOT NULL CHECK (organization_role IN ('Owner', 'Manager', 'Employee', 'External')),
    project_role TEXT NOT NULL CHECK (project_role IN ('Owner', 'Member')),
    is_primary BOOLEAN NOT NULL,
    UNIQUE (project_id, user_id)
) ON COMMIT DROP;

WITH project_candidates AS (
    SELECT project.*,
           client.client_type,
           pg_catalog.row_number() OVER (
               PARTITION BY project.status
               ORDER BY project.project_order
           )::INTEGER AS status_order,
           pg_catalog.count(*) OVER (
               PARTITION BY project.status
           )::INTEGER AS status_count,
           pg_catalog.count(*) FILTER (WHERE client.client_type = 'External') OVER (
               PARTITION BY project.status
               ORDER BY project.project_order
           )::INTEGER AS external_order,
           pg_catalog.count(*) FILTER (WHERE client.client_type = 'External') OVER (
               PARTITION BY project.status
           )::INTEGER AS external_count
    FROM demo_seed_project_catalog project
    JOIN demo_seed_client_catalog client ON client.client_id = project.client_id
    WHERE project.catalog_state = 'active'
      AND client.catalog_state = 'active'
), member_candidates AS (
    SELECT target.*,
           catalog.logical_key AS user_key,
           pg_catalog.row_number() OVER (ORDER BY target.profile_order)::INTEGER AS member_order,
           pg_catalog.count(*) FILTER (WHERE target.role = 'External') OVER (
               ORDER BY target.profile_order
           )::INTEGER AS external_order
    FROM demo_seed_target_member target
    JOIN demo_seed_user_catalog catalog ON catalog.profile_order = target.profile_order
), owner_candidates AS (
    SELECT member.*,
           pg_catalog.row_number() OVER (
               ORDER BY CASE member.role WHEN 'Manager' THEN 0 ELSE 1 END, member.profile_order
           )::INTEGER AS owner_order,
           pg_catalog.count(*) OVER ()::INTEGER AS owner_count
    FROM member_candidates member
    WHERE member.role IN ('Manager', 'Employee')
), owner_assignment AS (
    SELECT project.project_id,
           owner.user_id
    FROM project_candidates project
    JOIN owner_candidates owner
      ON owner.owner_order = ((project.project_order - 1) % owner.owner_count) + 1
), primary_assignment AS (
    SELECT project.project_id,
           member.user_id
    FROM member_candidates member
    JOIN project_candidates project
      ON project.status = 'active'
     AND (
          (member.role <> 'External'
           AND project.status_order = ((member.member_order - 1) % project.status_count) + 1)
          OR
          (member.role = 'External'
           AND project.client_type = 'External'
           AND project.external_order = ((member.external_order - 1) % project.external_count) + 1)
     )
), mandatory_assignment AS (
    SELECT combined.project_id,
           combined.user_id,
           pg_catalog.bool_or(combined.assignment_type = 'owner') AS is_owner,
           pg_catalog.bool_or(combined.assignment_type = 'primary') AS is_primary
    FROM (
        SELECT project_id, user_id, 'owner'::TEXT AS assignment_type FROM owner_assignment
        UNION ALL
        SELECT project_id, user_id, 'primary'::TEXT AS assignment_type FROM primary_assignment
    ) combined
    GROUP BY combined.project_id, combined.user_id
), ranked_assignment AS (
    SELECT project.project_order,
           project.logical_key AS project_key,
           project.project_id,
           project.status AS project_status,
           project.team_size,
           member.profile_order,
           member.user_key,
           member.user_id,
           member.role AS organization_role,
           COALESCE(mandatory.is_owner, false) AS is_owner,
           COALESCE(mandatory.is_primary, false) AS is_primary,
           pg_catalog.row_number() OVER (
               PARTITION BY project.project_id
               ORDER BY (mandatory.project_id IS NOT NULL) DESC,
                        ((member.profile_order + project.project_order * 11) % 31),
                        member.profile_order
           )::INTEGER AS team_order
    FROM project_candidates project
    CROSS JOIN member_candidates member
    LEFT JOIN mandatory_assignment mandatory
      ON mandatory.project_id = project.project_id
     AND mandatory.user_id = member.user_id
    WHERE member.role = 'Employee'
       OR COALESCE(mandatory.is_owner, false)
       OR COALESCE(mandatory.is_primary, false)
)
INSERT INTO demo_seed_target_project_user (
    project_user_id,
    project_order,
    project_key,
    project_id,
    project_status,
    team_size,
    profile_order,
    user_key,
    user_id,
    organization_role,
    project_role,
    is_primary
)
SELECT catalog.project_user_id,
       ranked.project_order,
       ranked.project_key,
       ranked.project_id,
       ranked.project_status,
       ranked.team_size,
       ranked.profile_order,
       ranked.user_key,
       ranked.user_id,
       ranked.organization_role,
       CASE WHEN ranked.is_owner THEN 'Owner' ELSE 'Member' END,
       ranked.is_primary
FROM ranked_assignment ranked
JOIN demo_seed_project_user_catalog catalog
  ON catalog.project_id = ranked.project_id
 AND catalog.user_id = ranked.user_id
 AND catalog.catalog_state = 'active'
WHERE ranked.team_order <= ranked.team_size;
