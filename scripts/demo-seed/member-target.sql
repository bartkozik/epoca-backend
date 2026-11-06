CREATE TEMP TABLE demo_seed_target_member
(
    profile_order INTEGER PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE,
    identity_id UUID NOT NULL UNIQUE,
    membership_id UUID NOT NULL UNIQUE,
    role TEXT NOT NULL CHECK (role IN ('Owner', 'Manager', 'Employee', 'External')),
    is_super_admin BOOLEAN NOT NULL
) ON COMMIT DROP;

WITH counts AS (
    SELECT pg_catalog.count(*) FILTER (WHERE role = 'Owner') AS owner_count,
           pg_catalog.count(*) FILTER (WHERE role = 'Manager') AS manager_count,
           pg_catalog.count(*) FILTER (WHERE role = 'Employee') AS employee_count,
           pg_catalog.count(*) FILTER (WHERE role = 'External') AS external_count,
           pg_catalog.count(*) FILTER (WHERE is_super_admin) AS superadmin_count
    FROM demo_seed_nonseed_active_member
), role_capacity AS (
    SELECT role, target_count - existing_count AS remaining_count, role_priority, slot_group
    FROM (
        SELECT 'Owner'::TEXT, 2::BIGINT, owner_count, 1, 1 FROM counts
        UNION ALL SELECT 'Manager', 2, manager_count, 2, 1 FROM counts
        UNION ALL SELECT 'Employee', 22, employee_count, 3, 2 FROM counts
        UNION ALL SELECT 'External', 4, external_count, 4, 3 FROM counts
    ) capacity(role, target_count, existing_count, role_priority, slot_group)
), role_slots AS (
    SELECT capacity.role,
           pg_catalog.row_number() OVER (
               ORDER BY capacity.slot_group, slot.slot_number, capacity.role_priority
           )::INTEGER AS target_order
    FROM role_capacity capacity
    CROSS JOIN LATERAL pg_catalog.generate_series(1::BIGINT, capacity.remaining_count) slot(slot_number)
), profiles AS (
    SELECT catalog.*,
           pg_catalog.row_number() OVER (ORDER BY catalog.profile_order)::INTEGER AS target_order
    FROM demo_seed_user_catalog catalog
    WHERE catalog.catalog_state = 'active'
), target AS (
    SELECT profiles.*, role_slots.role, counts.superadmin_count
    FROM profiles
    JOIN role_slots USING (target_order)
    CROSS JOIN counts
)
INSERT INTO demo_seed_target_member (profile_order, user_id, identity_id, membership_id, role, is_super_admin)
SELECT profile_order, user_id, identity_id, membership_id, role,
       target_order <= 2 - superadmin_count
FROM target;
