-- A session seen again lasts longer; one another transaction is pushing
-- out right now is left to it (SKIP LOCKED), so nothing waits for its row.
UPDATE groundwork_access.sessions SET session__ends_at = param('session/ends-at')
WHERE session__fingerprint = (SELECT session__fingerprint FROM groundwork_access.sessions
                              WHERE session__fingerprint = param('session/fingerprint')
                              FOR UPDATE SKIP LOCKED)
