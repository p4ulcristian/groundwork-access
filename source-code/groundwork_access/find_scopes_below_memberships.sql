-- Every scope below a scope a person is a member of, at any depth, with
-- the scope of the membership it is below.
WITH RECURSIVE below (kind, id, top_kind, top_id) AS (
  SELECT s.scope__kind, s.scope__id, m.membership__scope_kind, m.membership__scope_id
  FROM groundwork_access.memberships m
  JOIN groundwork_access.scopes s ON s.scope__parent_kind = m.membership__scope_kind
                       AND s.scope__parent_id   = m.membership__scope_id
  WHERE m.membership__person_id = param('membership/person-id')
  UNION
  SELECT s.scope__kind, s.scope__id, b.top_kind, b.top_id
  FROM groundwork_access.scopes s
  JOIN below b ON s.scope__parent_kind = b.kind AND s.scope__parent_id = b.id
)
SELECT kind AS scope__kind, id AS scope__id, top_kind AS membership__scope_kind, top_id AS membership__scope_id
FROM below
