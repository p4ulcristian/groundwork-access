-- The session of a fingerprint, if it hasn't ended; whether it is pushed out when seen again.
SELECT session__person_id, session__visitor_id, session__flags, session__renews, session__ends_at FROM groundwork_access.sessions
WHERE session__fingerprint = param('session/fingerprint') AND session__ends_at > now()
