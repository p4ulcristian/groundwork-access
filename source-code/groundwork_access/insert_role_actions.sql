-- Every action a role allows, in one statement: the actions are a JSON array of texts.
INSERT INTO groundwork_access.role_actions (role_action__role_id, role_action__action)
SELECT CAST(param('role-action/role-id') AS uuid), action
FROM jsonb_array_elements_text(CAST(param('role-action/actions') AS jsonb)) AS action
