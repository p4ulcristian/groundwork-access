-- A session: the cookie's fingerprint, never the cookie itself. A person's
-- has its person (and its flags); a visitor's, the visitor's id.
INSERT INTO groundwork_access.sessions (session__fingerprint, session__person_id, session__visitor_id, session__flags, session__ends_at)
VALUES (param('session/fingerprint'), param('session/person-id'), param('session/visitor-id'), param('session/flags'), param('session/ends-at'))
