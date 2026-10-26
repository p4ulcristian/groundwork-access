-- A person's membership of a scope, if they are in it.
SELECT membership__person_id FROM groundwork_access.memberships
WHERE membership__person_id = param('membership/person-id')
  AND membership__scope_kind = param('membership/scope-kind') AND membership__scope_id = param('membership/scope-id')
