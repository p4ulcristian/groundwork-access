-- How many scopes a kind has.
SELECT count(*) AS scopes__count FROM groundwork_access.scopes WHERE scope__kind = param('scope/kind')
