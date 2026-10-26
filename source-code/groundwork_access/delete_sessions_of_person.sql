-- Signing out everywhere, or a removed person: all their sessions end at once.
DELETE FROM groundwork_access.sessions WHERE session__person_id = param('session/person-id')
