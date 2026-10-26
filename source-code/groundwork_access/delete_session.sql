-- Signing out: this session ends at once.
DELETE FROM groundwork_access.sessions WHERE session__fingerprint = param('session/fingerprint')
