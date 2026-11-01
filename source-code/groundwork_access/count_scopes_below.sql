-- How many scopes sit right under a scope.
SELECT count(*) AS scope__below FROM groundwork_access.scopes
WHERE scope__parent_kind = param('scope/kind') AND scope__parent_id = param('scope/id')
