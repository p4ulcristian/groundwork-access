-- A scope and every scope above it, nearest first, whether the person is
-- a member of each, and each role they hold there with its actions (none
-- listed for Owner).
WITH RECURSIVE up (kind, id, depth) AS (
  SELECT CAST(param('scope/kind') AS text), CAST(param('scope/id') AS uuid), 0
  UNION
  SELECT s.scope__parent_kind, s.scope__parent_id, u.depth + 1
  FROM groundwork_access.scopes s JOIN up u ON s.scope__kind = u.kind AND s.scope__id = u.id
  WHERE s.scope__parent_id IS NOT NULL
)
SELECT u.kind AS scope__kind, u.id AS scope__id, u.depth AS scope__depth,
       m.membership__person_id IS NOT NULL AS membership__found,
       r.role__id AS role__id, r.role__name AS role__name, ra.role_action__action AS role_action__action
FROM up u
LEFT JOIN groundwork_access.memberships m ON m.membership__scope_kind = u.kind AND m.membership__scope_id = u.id
                               AND m.membership__person_id = param('membership/person-id')
LEFT JOIN groundwork_access.membership_roles mr
       ON mr.membership_role__person_id  = m.membership__person_id
      AND mr.membership_role__scope_kind = m.membership__scope_kind
      AND mr.membership_role__scope_id   = m.membership__scope_id
LEFT JOIN groundwork_access.roles r ON r.role__id = mr.membership_role__role_id
LEFT JOIN groundwork_access.role_actions ra ON ra.role_action__role_id = r.role__id
ORDER BY u.depth, r.role__name, ra.role_action__action
