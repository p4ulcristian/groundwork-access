-- The session of a fingerprint, if it hasn't ended.
SELECT session__person_id, session__visitor_id, session__flags, session__ends_at FROM groundwork_access.sessions
WHERE session__fingerprint = param('session/fingerprint') AND session__ends_at > now()
