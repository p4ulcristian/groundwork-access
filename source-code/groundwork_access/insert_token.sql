-- A token a person made: its fingerprint, never its text; the characters
-- its list shows; its name and end.
INSERT INTO groundwork_access.tokens (token__id, token__fingerprint, token__person_id, token__name, token__shown_start, token__ends_at)
VALUES (param('token/id'), param('token/fingerprint'), param('token/person-id'), param('token/name'), param('token/shown-start'), param('token/ends-at'))
