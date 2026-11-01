-- Every action every role of a scope allows, taken away.
DELETE FROM groundwork_access.role_actions
WHERE role_action__role_id IN (SELECT role__id FROM groundwork_access.roles
                               WHERE role__scope_kind = param('role/scope-kind') AND role__scope_id = param('role/scope-id'))
