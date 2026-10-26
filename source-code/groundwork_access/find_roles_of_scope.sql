-- Every role of a scope with the actions it allows (none listed for Owner,
-- who holds every action of the kind), by name.
SELECT r.role__id AS role__id, r.role__name AS role__name, ra.role_action__action AS role_action__action
FROM groundwork_access.roles r
LEFT JOIN groundwork_access.role_actions ra ON ra.role_action__role_id = r.role__id
WHERE r.role__scope_kind = param('role/scope-kind') AND r.role__scope_id = param('role/scope-id')
ORDER BY r.role__name, ra.role_action__action
