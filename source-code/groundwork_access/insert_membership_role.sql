-- One role a member holds in the scope.
INSERT INTO groundwork_access.membership_roles (membership_role__person_id, membership_role__scope_kind, membership_role__scope_id, membership_role__role_id)
VALUES (param('membership-role/person-id'), param('membership-role/scope-kind'), param('membership-role/scope-id'), param('membership-role/role-id'))
