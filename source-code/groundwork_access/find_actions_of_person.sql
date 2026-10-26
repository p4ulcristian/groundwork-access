-- Every scope a person is in, each role they hold there (none: a row with
-- no role), and the actions it allows (none listed for Owner, who holds
-- every action of the kind).
SELECT m.membership__scope_kind AS membership__scope_kind,
       m.membership__scope_id   AS membership__scope_id,
       r.role__name             AS role__name,
       ra.role_action__action   AS role_action__action
FROM groundwork_access.memberships m
LEFT JOIN groundwork_access.membership_roles mr
       ON mr.membership_role__person_id  = m.membership__person_id
      AND mr.membership_role__scope_kind = m.membership__scope_kind
      AND mr.membership_role__scope_id   = m.membership__scope_id
LEFT JOIN groundwork_access.roles r ON r.role__id = mr.membership_role__role_id
LEFT JOIN groundwork_access.role_actions ra ON ra.role_action__role_id = r.role__id
WHERE m.membership__person_id = param('membership/person-id')
