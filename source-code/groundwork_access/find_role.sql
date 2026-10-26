-- A role, if it is this scope's.
SELECT role__id, role__name FROM groundwork_access.roles
WHERE role__id = param('role/id') AND role__scope_kind = param('role/scope-kind') AND role__scope_id = param('role/scope-id')
