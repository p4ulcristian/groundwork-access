-- A scope gone; its roles and memberships went first.
DELETE FROM groundwork_access.scopes
WHERE scope__kind = param('scope/kind') AND scope__id = param('scope/id')
