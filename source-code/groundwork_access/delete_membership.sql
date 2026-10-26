-- A person leaves a scope.
DELETE FROM groundwork_access.memberships
WHERE membership__person_id = param('membership/person-id')
  AND membership__scope_kind = param('membership/scope-kind') AND membership__scope_id = param('membership/scope-id')
