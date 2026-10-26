-- The role of a scope that has this name, if there is one.
SELECT role__id FROM groundwork_access.roles
WHERE role__scope_kind = param('role/scope-kind') AND role__scope_id = param('role/scope-id') AND role__name = param('role/name')
