-- One action a role allows.
INSERT INTO groundwork_access.role_actions (role_action__role_id, role_action__action)
VALUES (param('role-action/role-id'), param('role-action/action'))
