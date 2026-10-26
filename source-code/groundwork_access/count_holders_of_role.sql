-- How many memberships hold a role.
SELECT count(*) AS role__held_by FROM groundwork_access.membership_roles WHERE membership_role__role_id = param('role/id')
