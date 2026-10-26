-- A scope: the app's own row (a shop), known to access by its kind and id,
-- under its parent scope (a company) or none.
INSERT INTO groundwork_access.scopes (scope__kind, scope__id, scope__parent_kind, scope__parent_id)
VALUES (param('scope/kind'), param('scope/id'), param('scope/parent-kind'), param('scope/parent-id'))
