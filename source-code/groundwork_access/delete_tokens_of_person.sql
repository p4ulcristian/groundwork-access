-- Signing out everywhere, or a removed person: all their tokens end at once.
DELETE FROM groundwork_access.tokens WHERE token__person_id = param('token/person-id')
