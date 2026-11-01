-- Every role every member holds in a scope, taken away.
DELETE FROM groundwork_access.membership_roles
WHERE membership_role__scope_kind = param('membership-role/scope-kind') AND membership_role__scope_id = param('membership-role/scope-id')
