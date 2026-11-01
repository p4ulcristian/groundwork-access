-- Every role of a scope gone, Owner too; their actions went first, and nobody holds them.
DELETE FROM groundwork_access.roles
WHERE role__scope_kind = param('role/scope-kind') AND role__scope_id = param('role/scope-id')
